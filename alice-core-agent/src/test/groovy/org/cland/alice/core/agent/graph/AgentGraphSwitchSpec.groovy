package org.cland.alice.core.agent.graph

import io.vertx.core.Future
import org.cland.alice.core.agent.Agent
import org.cland.alice.core.agent.AgentConfig
import org.cland.alice.core.agent.executor.AgentExecutor
import org.cland.alice.core.agent.kernel.EventStream
import org.cland.alice.core.agent.kernel.Loop
import org.cland.alice.core.agent.kernel.SessionRequest
import org.cland.alice.core.agent.kernel.SessionResult
import org.cland.alice.core.agent.kernel.SessionStatus
import org.cland.alice.core.planner.Plan
import org.cland.alice.core.planner.PlannerService
import org.cland.alice.core.planner.strategy.StrategySelector
import org.cland.alice.model.Call
import org.cland.alice.model.Model
import org.cland.alice.model.ModelProvider
import org.cland.alice.model.ModelSupplier
import org.cland.alice.tool.gateway.ToolRegistry
import spock.lang.Specification
import spock.lang.Title

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Agent 组合根换轨接线测试：config.graphKernelEnabled=true 时 kernel()/events()/cancel() 指向
 * GraphSessionKernel（含事件桥接 thought/action/observe）；legacy ask() 业务门面仍可共存。
 */
@Title("Agent 图内核开关")
class AgentGraphSwitchSpec extends Specification {

    def cleanup() {
        ModelProvider.reset()
    }

    private PlannerService staticPlanner(List<Plan.Step> steps) {
        def selector = StrategySelector.builder()
                .fastPath(Stub(org.cland.alice.core.planner.strategy.DecisionStrategy))
                .slowPath(Stub(org.cland.alice.core.planner.strategy.DecisionStrategy))
                .build()
        PlannerService.builder().strategySelector(selector)
                .staticPlannerFn({ Map ctx -> Plan.staticPlan("steps", steps) } as java.util.function.Function)
                .build()
    }

    private void registerEchoTool(ToolRegistry registry, Closure impl) {
        def bean = new EchoToolBean(impl)
        def lookup = java.lang.invoke.MethodHandles.lookup()
        def mapper = new com.fasterxml.jackson.databind.ObjectMapper()
        def schema = mapper.createObjectNode()
        schema.put("type", "object")
        def props = schema.putObject("properties")
        props.putObject("msg").put("type", "string")
        def req = schema.putArray("required")
        req.add("msg")
        registry.register(org.cland.alice.tool.gateway.metadata.ToolMetadata.builder()
            .name("echo")
            .description("Echo")
            .inputSchema(schema)
            .targetMethod(lookup.findVirtual(EchoToolBean, "echoOp",
                java.lang.invoke.MethodType.methodType(String, String)))
            .targetBean(bean)
            .paramNames(["msg"] as String[])
            .build())
    }

    private Agent graphAgent(List supplierResponses, Closure effectRecorder) {
        def agent = new Agent("graph-switch", AgentConfig.builder()
            .defaultModelId("graph-model")
            .graphKernelEnabled(true)
            .maxMicroDepth(10)
            .build())
        agent.withPlannerService(staticPlanner([Plan.Step.of(Plan.Intent.ANALYZE, "graph-model")]))

        def registry = new ToolRegistry()
        def calls = []
        registerEchoTool(registry, { String msg -> calls << msg; effectRecorder?.call() ; "echoed:$msg" })
        agent.withToolRegistry(registry)

        def supplier = Stub(ModelSupplier) {
            request(_ as Call) >> { Call c ->
                supplierResponses.size() > 0 ? supplierResponses.remove(0) as Call.Response : null
            }
        }
        ModelProvider.getInstance()
            .registerSupplier(supplier)
            .registerModel(Model.builder()
                .modelId("graph-model")
                .supplierName(supplier.name())
                .capability(Model.Capability.FUNCTION_CALL)
                .pricing(new Model.Pricing(0, 0))
                .build())
        return agent
    }

    private SessionResult await(Future resultFuture) {
        def latch = new CountDownLatch(1)
        def ref = new AtomicReference<SessionResult>()
        resultFuture.onSuccess { r -> ref.set(r); latch.countDown() }
        assert latch.await(10, TimeUnit.SECONDS), "kernel execute timed out"
        return ref.get()
    }

    def "graph kernel switch routes kernel()/events() and bridges action/observe events"() {
        given: "hermetic 模型先 tool_call 后文本"
        def toolRaw = '{"choices":[{"message":{"content":"call"}}],"finish_reason":"tool_calls"}'
        def toolCalls = [new Call.ToolCall("echo", '{"msg": "hi"}')]
        def events = Collections.synchronizedList([])
        def agent = graphAgent([
            new Call.Response("I will echo.", new Call.TokenUsage(1, 1, 2), ["raw": toolRaw], toolCalls),
            Call.Response.textOnly("图内核回答", new Call.TokenUsage(1, 1, 2), ["raw": '{"choices":[{"message":{"content":"图内核回答"}}]}']),
            Call.Response.textOnly("[legacy]", new Call.TokenUsage(1, 1, 2), ["raw": '{"choices":[{"message":{"content":"[legacy]"}}]}'])
        ], null)

        expect: "开关关闭默认仍为 legacy"
        def legacy = new Agent("legacy-default", AgentConfig.builder().defaultModelId("m").build())
        legacy.kernel() instanceof AgentExecutor
        !(legacy.kernel() instanceof GraphSessionKernel)

        when: "开启开关：kernel() 为图会话内核并订阅事件"
        agent.kernel() instanceof Loop
        agent.kernel() instanceof GraphSessionKernel
        agent.events().subscribe(new EventStream.Listener() {
            @Override
            void onThought(String reasoning) { events << "thought:$reasoning" }
            @Override
            void onAction(String target, Map params) { events << "action:$target" }
            @Override
            void onObserve(String raw, String summary, long ms) { events << "observe:$summary" }
        })
        def result = await(agent.kernel().execute(SessionRequest.of("do it")))

        then: "图会话闭环 + answer 收敛"
        result.status() == SessionStatus.FINISHED
        result.answer() == "图内核回答"
        agent.kernel().state().phase() == "FINISH"

        and: "事件桥接：strategize/仲裁 thought、echo action、observe 回流"
        events.any { it.startsWith("thought:") }
        events.any { it == "action:echo" }
        events.any { it == "observe:echo" }

        and: "legacy ask 业务门面与图内核共存"
        agent.ask("legacy path") == "[legacy]"
    }

    def "cancel routes to graph kernel when switch enabled"() {
        given:
        def agent = graphAgent([
            Call.Response.textOnly("ok", new Call.TokenUsage(1, 1, 2), ["raw": '{"choices":[{"message":{"content":"ok"}}]}'])
        ], null)

        when:
        agent.cancel()
        def result = await(agent.kernel().execute(SessionRequest.of("x")))

        then:
        result.status() == SessionStatus.CANCELLED
    }
}
