package org.cland.alice.core.agent.graph

import org.cland.alice.core.agent.kernel.graph.EffectOutcome
import org.cland.alice.core.agent.kernel.graph.EffectGateway
import org.cland.alice.core.agent.kernel.graph.GoalRef
import org.cland.alice.core.agent.kernel.graph.Ledger
import org.cland.alice.core.agent.kernel.graph.SessionOutcome
import org.cland.alice.core.agent.kernel.graph.StandardSkeleton
import org.cland.alice.core.agent.kernel.graph.ToolCallReq
import org.cland.alice.core.planner.Plan
import org.cland.alice.core.planner.PlannerService
import org.cland.alice.core.planner.strategy.StrategySelector
import org.cland.alice.tool.gateway.ToolRegistry
import spock.lang.Specification

import java.util.function.Function
import spock.lang.Title

/**
 * 生产装配适配器测试（graph 包）：PlannerGoalBrain / ToolRegistryEffectGateway /
 * LlmActorBrain（经 TextLlmPipeline + hermetic 模型供应商），以及标准骨架的离线端到端会话。
 */
@Title("Agent 图装配适配器")
class AgentGraphAssemblySpec extends Specification {

    // ========================================================================
    // PlannerGoalBrain：Plan 步骤 → goal 图素材
    // ========================================================================

    private PlannerService plannerWithStatic(Function fn) {
        def selector = StrategySelector.builder()
                .fastPath(Stub(org.cland.alice.core.planner.strategy.DecisionStrategy))
                .slowPath(Stub(org.cland.alice.core.planner.strategy.DecisionStrategy))
                .build()
        PlannerService.builder().strategySelector(selector).staticPlannerFn(fn).build()
    }

    def "planner steps map to goal queue, FINISH filtered (P2)"() {
        given:
        def planner = plannerWithStatic({ Map<String, Object> ctx ->
            Plan.staticPlan("three-step", [
                Plan.Step.of(Plan.Intent.SEARCH, "web"),
                Plan.Step.of(Plan.Intent.ANALYZE, "gpt-4o"),
                Plan.Step.of(Plan.Intent.FINISH, "FINISH"),
            ])
        } as Function)
        def brain = new PlannerGoalBrain(planner)

        when:
        def plan = brain.strategize("search and write", new Ledger())

        then:
        plan.goals().size() == 2
        plan.goals()[0].id() == "g0"
        plan.goals()[0].summary() == "SEARCH:web"
        plan.goals()[1].summary() == "ANALYZE:gpt-4o"
        plan.meta()["planType"] == "STATIC"
    }

    def "re-plan after revision feedback carries lastFeedback into planner context (P5)"() {
        given:
        def seenCtx = []
        def planner = plannerWithStatic({ Map<String, Object> ctx ->
            seenCtx << ctx
            Plan.staticPlan("done", [Plan.Step.of(Plan.Intent.FINISH, "FINISH")])
        } as Function)
        def brain = new PlannerGoalBrain(planner)
        def ledger = new Ledger()
        ledger.recordArbitration([feedback: "route was wrong"])

        when:
        def plan = brain.strategize("task", ledger)

        then: "修订反馈经账本 route 槽位回填规划上下文"
        plan.goals().isEmpty()
        seenCtx.size() == 1
        seenCtx[0]["prompt"] == "task"
        seenCtx[0]["lastFeedback"] == "route was wrong"
    }

    // ========================================================================
    // ToolRegistryEffectGateway：真实 ExecutionEngine 效果执行
    // ========================================================================

    def "effect gateway executes registry tools and returns observations"() {
        given:
        def registry = new org.cland.alice.tool.gateway.ToolRegistry()
        def calls = []
        registerEchoTool(registry, { String msg -> calls << msg; "echo: $msg" })
        def gateway = new ToolRegistryEffectGateway(registry)

        when:
        def outcome = gateway.invoke(ToolCallReq.of("echo", [msg: "hi"]))

        then:
        outcome.success()
        outcome.observation() == "echo: hi"
        calls == ["hi"]
    }

    def "effect gateway reports unknown tool as failure"() {
        given:
        def gateway = new ToolRegistryEffectGateway(new org.cland.alice.tool.gateway.ToolRegistry())

        when:
        def outcome = gateway.invoke(ToolCallReq.of("no_such_tool"))

        then:
        !outcome.success()
    }

    private void registerEchoTool(ToolRegistry registry, Closure impl) {
        def bean = new EchoToolBean(impl)
        def lookup = java.lang.invoke.MethodHandles.lookup()
        registry.register(org.cland.alice.tool.gateway.metadata.ToolMetadata.builder()
            .name("echo")
            .description("Echo a message")
            .inputSchema(schemaOf(["msg"] as String[]))
            .targetMethod(lookup.findVirtual(EchoToolBean, "echoOp",
                java.lang.invoke.MethodType.methodType(String, String)))
            .targetBean(bean)
            .paramNames(["msg"] as String[])
            .build())
    }

    private static com.fasterxml.jackson.databind.JsonNode schemaOf(String[] props) {
        def mapper = new com.fasterxml.jackson.databind.ObjectMapper()
        def root = mapper.createObjectNode()
        root.put("type", "object")
        def p = root.putObject("properties")
        for (String name : props) {
            p.putObject(name).put("type", "string")
        }
        def req = root.putArray("required")
        for (String name : props) { req.add(name) }
        return root
    }

    // ========================================================================
    // 端到端：PlannerGoalBrain + LlmActorBrain + ToolRegistryEffectGateway 跑标准骨架
    // ========================================================================

    def "offline end-to-end: plan goals -> TAO acts via LLM -> session finishes"() {
        given: "真实 PlannerService（静态计划两步骤）+ hermetic 模型（先 tool_call 后文本）+ 真实 echo 工具"
        def planner = plannerWithStatic({ Map ctx ->
            Plan.staticPlan("steps", [
                Plan.Step.of(Plan.Intent.SEARCH, "echo"),
                Plan.Step.of(Plan.Intent.ANALYZE, "graph-model"),
            ])
        } as Function)
        def effectCalls = []
        def registry = new org.cland.alice.tool.gateway.ToolRegistry()
        registerEchoTool(registry, { String msg -> effectCalls << msg; "echoed" })

        def toolCallRaw =
            '{"id":"r1","choices":[{"index":0,"message":{"role":"assistant",' +
            '"content":"calling","tool_calls":[{"index":0,"id":"c1","type":"function",' +
            '"function":{"name":"echo","arguments":{"msg":"hello"}}}]}},' +
            '"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}'
        // 注意：arguments 在 raw 中以 JSON 对象出现（OpenAI 风格为字符串，此处适配 decode 直取 Response.toolCalls）
        def toolCalls = [new org.cland.alice.model.Call.ToolCall("echo", '{"msg": "hello"}')]
        def done = org.cland.alice.model.Call.Response.textOnly("[done]", new org.cland.alice.model.Call.TokenUsage(1, 1, 2), ["raw": '{"choices":[{"message":{"content":"[done]"}}]}'])
        def supplier = Stub(org.cland.alice.model.ModelSupplier) {
            request(_) >>> [
                new org.cland.alice.model.Call.Response("I will echo.", new org.cland.alice.model.Call.TokenUsage(1, 1, 2), ["raw": toolCallRaw], toolCalls),
                done,
                done
            ]
        }
        org.cland.alice.model.ModelProvider.getInstance()
            .registerSupplier(supplier)
            .registerModel(org.cland.alice.model.Model.builder()
                .modelId("graph-model")
                .supplierName(supplier.name())
                .capability(org.cland.alice.model.Model.Capability.FUNCTION_CALL)
                .pricing(new org.cland.alice.model.Model.Pricing(0, 0))
                .build())
        def inferencer = new org.cland.alice.core.agent.pipeline.TextLlmPipeline()
        def brain = new LlmActorBrain(inferencer, "graph-model", null,
            [new org.cland.alice.core.agent.kernel.InferRequest.ToolSpec("echo", "Echo", schemaOf(["msg"] as String[]))])
        def gateway = new ToolRegistryEffectGateway(registry)

        def skeleton = new StandardSkeleton(
                "do the task", new PlannerGoalBrain(planner), brain,
                { g, l, o -> StandardSkeleton.Arbitration.pass() } as StandardSkeleton.ArbitrationBrain,
                null, gateway, 2, 10)

        when:
        def outcome = skeleton.run()

        then:
        outcome.status() == SessionOutcome.Status.FINISHED
        effectCalls == ["hello"]              // TAO 的 Action 经真实 ExecutionEngine 执行
        def trace = outcome.trace()
        trace.count { it.contains("tao(COMPOSITE)") } == 2   // 两个 goal 各自 TAO
        trace.count { it.contains("arbitrate(DECISION)") } == 2
        trace.any { it.contains("effect:echo ok=true") }
        trace.last().contains("end-all(TERMINAL)")

        cleanup:
        org.cland.alice.model.ModelProvider.reset()
    }

    def "actor brain queues multiple tool calls from one response"() {
        given:
        def raw = '{"choices":[{"message":{"content":"multi"}}],"finish_reason":"tool_calls"}'
        def toolCalls = [
            new org.cland.alice.model.Call.ToolCall("echo", '{"msg":"one"}'),
            new org.cland.alice.model.Call.ToolCall("echo", '{"msg":"two"}')
        ]
        def supplier = Stub(org.cland.alice.model.ModelSupplier) {
            request(_) >> new org.cland.alice.model.Call.Response("m", new org.cland.alice.model.Call.TokenUsage(1, 1, 2), ["raw": raw], toolCalls)
        }
        org.cland.alice.model.ModelProvider.getInstance()
            .registerSupplier(supplier)
            .registerModel(org.cland.alice.model.Model.builder()
                .modelId("graph-model-2").supplierName(supplier.name())
                .capability(org.cland.alice.model.Model.Capability.FUNCTION_CALL)
                .pricing(new org.cland.alice.model.Model.Pricing(0, 0))
                .build())
        def brain = new LlmActorBrain(new org.cland.alice.core.agent.pipeline.TextLlmPipeline(), "graph-model-2", null, [])

        when: "第一次 think 返回第一个调用"
        def first = brain.think(GoalRef.of("g0", "SEARCH:echo"), new Ledger(), "")

        then:
        first instanceof StandardSkeleton.ActorStep.Act
        ((StandardSkeleton.ActorStep.Act) first).tool().name() == "echo"
        ((StandardSkeleton.ActorStep.Act) first).tool().arguments()["msg"] == "one"

        and: "第二次 think 消费队列（不再发起模型请求）"
        def second = brain.think(GoalRef.of("g0", "SEARCH:echo"), new Ledger(), "echoed")
        second instanceof StandardSkeleton.ActorStep.Act
        ((StandardSkeleton.ActorStep.Act) second).tool().name() == "echo"
        ((StandardSkeleton.ActorStep.Act) second).tool().arguments()["msg"] == "two"

        cleanup:
        org.cland.alice.model.ModelProvider.reset()
    }
}

/** 模拟工具 Bean（与 executor 测试同套路）。 */
class EchoToolBean {
    private final Closure impl
    EchoToolBean(Closure impl) { this.impl = impl }
    String echoOp(String msg) { return impl.call(msg) }
}
