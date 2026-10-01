package org.cland.alice.core.agent.graph

import io.vertx.core.Future
import io.vertx.core.Vertx
import org.cland.alice.core.agent.kernel.SessionRequest
import org.cland.alice.core.agent.kernel.SessionResult
import org.cland.alice.core.agent.kernel.SessionStatus
import org.cland.alice.core.agent.kernel.graph.StandardSkeleton
import org.cland.alice.core.agent.pipeline.TextLlmPipeline
import org.cland.alice.core.agent.wal.InMemoryWalStore
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
 * GraphSessionKernel — WAL/Checkpoint 接入：中断 → 恢复 → 完成（#178 M1-S3）。
 */
@Title("GraphSessionKernel — 中断恢复续跑")
class GraphSessionKernelResumeSpec extends Specification {

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

    private GraphSessionKernel kernel(PlannerService planner,
            StandardSkeleton.ArbitrationBrain arbitration,
            InMemoryWalStore store) {
        def k = new GraphSessionKernel(
                Vertx.vertx(), planner, new ToolRegistry(),
                new TextLlmPipeline(), "graph-model", null, arbitration, null, 2, 10)
        if (store != null) {
            k.walStore(store)
        }
        return k
    }

    private static StandardSkeleton.ArbitrationBrain passArbitration() {
        { g, l, o -> StandardSkeleton.Arbitration.pass() } as StandardSkeleton.ArbitrationBrain
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

    def "interrupt after goal-boundary checkpoint then resume completes with the same answer"() {
        given: "确定性模型回答"
        def planner = staticPlanner([Plan.Step.of(Plan.Intent.ANALYZE, "graph-model")])
        registerModelSupplier { Call c ->
            Call.Response.textOnly("最终回答：完成", new Call.TokenUsage(1, 1, 2),
                    ["raw": '{"choices":[{"message":{"content":"最终回答：完成"}}]}'])
        }

        and: "参考跑（无 store）"
        def reference = await(kernel(planner, passArbitration(), null)
                .execute(SessionRequest.of("ref-s1")))

        and: "崩溃跑：vp 安全点落盘后，arbitrate 抛异常（模拟进程中断）"
        def store = new InMemoryWalStore()
        def crashKernel = kernel(planner, { g, l, o -> throw new RuntimeException("simulated crash") }
                as StandardSkeleton.ArbitrationBrain, store)
        def crashed = await(crashKernel.execute(
                new SessionRequest("crash-s1", "do it", "graph-model", [:])))

        expect: "崩溃收敛为 FAILED，但 vp 安全点已落盘"
        crashed.status() == SessionStatus.FAILED
        store.getLatestCheckpoint("crash-s1").present

        when: "恢复续跑"
        def resumed = await(kernel(planner, passArbitration(), store).resume("crash-s1"))

        then: "完成且回答与不中断一次跑一致"
        reference.status() == SessionStatus.FINISHED
        resumed.status() == SessionStatus.FINISHED
        resumed.answer() == reference.answer()
    }

    def "without walStore the kernel neither persists nor resumes (behaviour unchanged)"() {
        given:
        def planner = staticPlanner([Plan.Step.of(Plan.Intent.ANALYZE, "graph-model")])
        registerModelSupplier { Call c ->
            Call.Response.textOnly("ok", new Call.TokenUsage(1, 1, 2),
                    ["raw": '{"choices":[{"message":{"content":"ok"}}]}'])
        }
        def store = new InMemoryWalStore()

        when: "kernel 未注入 store"
        def result = await(kernel(planner, passArbitration(), null)
                .execute(new SessionRequest("plain-s1", "x", "graph-model", [:])))

        then:
        result.status() == SessionStatus.FINISHED
        store.getLatestCheckpoint("plain-s1").isEmpty()
    }
}
