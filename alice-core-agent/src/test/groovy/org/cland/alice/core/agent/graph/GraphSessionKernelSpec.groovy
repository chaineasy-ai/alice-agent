package org.cland.alice.core.agent.graph

import io.vertx.core.Future
import io.vertx.core.Vertx
import org.cland.alice.core.agent.kernel.EventStream
import org.cland.alice.core.agent.kernel.KernelState
import org.cland.alice.core.agent.kernel.Loop
import org.cland.alice.core.agent.kernel.SessionRequest
import org.cland.alice.core.agent.kernel.SessionResult
import org.cland.alice.core.agent.kernel.SessionStatus
import org.cland.alice.core.agent.pipeline.TextLlmPipeline
import org.cland.alice.core.planner.Plan
import org.cland.alice.core.planner.PlannerService
import org.cland.alice.core.planner.strategy.StrategySelector
import org.cland.alice.model.Call
import org.cland.alice.model.Model
import org.cland.alice.model.ModelProvider
import org.cland.alice.model.ModelSupplier
import spock.lang.Specification
import spock.lang.Title

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * GraphSessionKernel（换轨目标态 Loop 实现）契约测试：
 * SessionRequest → SessionResult 收敛（answer 产物/状态/步数）、取消安全点、KernelState、事件面。
 */
@Title("GraphSessionKernel — 图会话内核契约")
class GraphSessionKernelSpec extends Specification {

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

    private void registerModelSupplier(Closure respond, String modelId = "graph-model") {
        def supplier = Stub(ModelSupplier) {
            request(_ as Call) >> { Call c -> respond(c) as Call.Response }
        }
        ModelProvider.getInstance()
            .registerSupplier(supplier)
            .registerModel(Model.builder()
                .modelId(modelId)
                .supplierName(supplier.name())
                .capability(Model.Capability.FUNCTION_CALL)
                .pricing(new Model.Pricing(0, 0))
                .build())
    }

    private GraphSessionKernel kernel(PlannerService planner, String modelId = "graph-model") {
        new GraphSessionKernel(
                Vertx.vertx(), planner, new org.cland.alice.tool.gateway.ToolRegistry(),
                new TextLlmPipeline(), modelId, null,
                { g, l, o -> org.cland.alice.core.agent.kernel.graph.StandardSkeleton.Arbitration.pass() }
                        as org.cland.alice.core.agent.kernel.graph.StandardSkeleton.ArbitrationBrain,
                null, 2, 10)
    }

    private SessionResult await(Future resultFuture) {
        def latch = new CountDownLatch(1)
        def ref = new AtomicReference<SessionResult>()
        def err = new AtomicReference<Throwable>()
        resultFuture
            .onSuccess { r -> ref.set(r); latch.countDown() }
            .onFailure { e -> err.set(e); latch.countDown() }
        assert latch.await(10, TimeUnit.SECONDS), "kernel execute timed out"
        if (err.get() != null) {
            throw new AssertionError("kernel execute failed", err.get())
        }
        return ref.get()
    }

    def "execute runs a real skeleton session and returns the model answer"() {
        given: "单 ANALYZE goal + hermetic 模型直接回答"
        def planner = staticPlanner([Plan.Step.of(Plan.Intent.ANALYZE, "graph-model")])
        registerModelSupplier { Call c ->
            Call.Response.textOnly("最终回答",
                    new Call.TokenUsage(1, 1, 2),
                    ["raw": '{"choices":[{"message":{"content":"最终回答"}}]}'])
        }
        def kernel = kernel(planner)

        when:
        def result = await(kernel.execute(SessionRequest.of("请回答")))

        then:
        result.status() == SessionStatus.FINISHED
        result.answer() == "最终回答"
        result.sessionId() != null
        result.iteration() > 0
        result.metadata().containsKey("steps")

        and: "state reflects the finished session"
        def state = kernel.state()
        state.phase() == "FINISH"
        state.iteration() == result.iteration()

        and: "contract surface"
        kernel instanceof Loop
        kernel.events() instanceof EventStream
    }

    def "execute honors explicit sessionId and model override"() {
        given:
        def planner = staticPlanner([Plan.Step.of(Plan.Intent.ANALYZE, "graph-model-2")])
        registerModelSupplier({ Call c ->
            Call.Response.textOnly("ok",
                    new Call.TokenUsage(1, 1, 2),
                    ["raw": '{"choices":[{"message":{"content":"ok"}}]}'])
        }, "graph-model-2")
        def kernel = kernel(planner, "ignored-default")

        when:
        def result = await(kernel.execute(
                new SessionRequest("client-s1", "task", "graph-model-2", [:])))

        then:
        result.status() == SessionStatus.FINISHED
        result.sessionId() == "client-s1"
        result.answer() == "ok"
        kernel.state().sessionId() == "client-s1"
    }

    def "cancel before execute returns CANCELLED"() {
        given:
        def kernel = kernel(staticPlanner([Plan.Step.of(Plan.Intent.ANALYZE, "graph-model")]))

        when:
        kernel.cancel()
        def result = await(kernel.execute(SessionRequest.of("x")))

        then:
        result.status() == SessionStatus.CANCELLED
    }

    def "brain failures converge to FAILED SessionResult instead of future errors"() {
        given: "模型传输抛异常（actor brain 上抛）"
        def planner = staticPlanner([Plan.Step.of(Plan.Intent.ANALYZE, "graph-model")])
        registerModelSupplier { Call c -> throw new RuntimeException("network down") }
        def kernel = kernel(planner)

        when:
        def result = await(kernel.execute(SessionRequest.of("x")))

        then:
        result.status() == SessionStatus.FAILED
        result.answer() == ""
        (result.metadata()["error"] as String).contains("network down")
        kernel.state().phase() == "FAILED"
    }

    def "step budget option is honored"() {
        given:
        def planner = staticPlanner([Plan.Step.of(Plan.Intent.ANALYZE, "graph-model")])
        // 模型永远要工具 → TAO 效果熔断走 guard 仍会推进；改用 budget 极小验证 FAILED 兜底路径
        registerModelSupplier { Call c ->
            new Call.Response("call", new Call.TokenUsage(1, 1, 2),
                    ["raw": '{"choices":[{"message":{"content":"call"}}]}', "finish_reason": "tool_calls"],
                    [new Call.ToolCall("echo", '{}')])
        }
        def kernel = kernel(planner)

        when:
        def result = await(kernel.execute(new SessionRequest("s", "x", "graph-model", [maxSteps: 5])))

        then:
        result.status() == SessionStatus.FAILED
        (result.metadata()["error"] as String).contains("step budget exhausted")
    }

    def "events() subscription surface is callable"() {
        given:
        def kernel = kernel(staticPlanner([Plan.Step.of(Plan.Intent.ANALYZE, "graph-model")]))
        def listener = new EventStream.Listener() {}

        when:
        kernel.events().subscribe(listener)
        kernel.events().unsubscribe(listener)

        then:
        noExceptionThrown()
        kernel.state().phase() == "IDLE"
    }
}
