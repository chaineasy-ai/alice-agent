package org.cland.alice.core.agent.kernel

import io.vertx.core.Future
import org.cland.alice.core.agent.Agent
import org.cland.alice.core.planner.Plan
import org.cland.alice.core.planner.PlannerService
import org.cland.alice.core.planner.strategy.DecisionStrategy
import org.cland.alice.core.planner.strategy.StrategySelector
import spock.lang.Specification
import spock.lang.Timeout

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 内核执行契约（Loop）测试。
 *
 * 覆盖 D3/D7 第一步：Agent 只暴露 kernel()/events() 只读契约面；
 * SessionRequest → SessionResult 的闭环；cancel 安全点语义；KernelState 快照。
 *
 * 与 AgentPpaoLoopSpec 同套路：用 mock StrategySelector 控制 PlannerService 输出（FINISH），
 * 不触达真实 LLM。内核闭环 = execute(SessionRequest) → PPAO legacy 路径 → SessionResult。
 */
@Timeout(10)
class LoopSpec extends Specification {

    /** 构造一个每次返回 FINISH 计划的规划器（无 LLM 调用）。 */
    private PlannerService finishPlanner() {
        def fastPath = Stub(DecisionStrategy) {
            decide(_ as Map) >> {
                Plan.builder().type(Plan.Type.FAST_PATH).summary("Immediate finish")
                        .addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH")).build()
            }
        }
        def slowPath = Stub(DecisionStrategy) {
            decide(_ as Map) >> {
                Plan.builder().type(Plan.Type.SLOW_PATH).summary("fallback")
                        .addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH")).build()
            }
        }
        def selector = StrategySelector.builder()
                .fastPath(fastPath).slowPath(slowPath)
                .complexityFunction { _ -> false }
                .build()
        PlannerService.builder().strategySelector(selector).build()
    }

    private SessionResult await(Future sessionFuture) {
        def latch = new CountDownLatch(1)
        def resultRef = new AtomicReference<SessionResult>()
        def errorRef = new AtomicReference<Throwable>()
        sessionFuture
                .onSuccess { r -> resultRef.set(r); latch.countDown() }
                .onFailure { e -> errorRef.set(e); latch.countDown() }
        assert latch.await(10, TimeUnit.SECONDS), "kernel execute timed out"
        if (errorRef.get() != null) {
            throw new AssertionError("kernel execute failed", errorRef.get())
        }
        return resultRef.get()
    }

    def "Agent exposes only the kernel Loop contract, not the concrete executor"() {
        given: "a bare agent (legacy executor inside)"
        def agent = new Agent("kernel-contract")

        expect: "kernel()/events() are the read-only contract surface"
        agent.kernel() instanceof Loop
        agent.events() instanceof EventStream

        and: "state() is the idle snapshot before any session"
        agent.kernel().state() != null
        agent.kernel().state().phase() == "IDLE"
    }

    def "kernel execute(SessionRequest) closes a session with FINISHED result"() {
        given: "agent with an immediate-FINISH planner"
        def agent = new Agent("kernel-finish")
        agent.withPlannerService(finishPlanner())

        when: "running a session through the kernel contract"
        def result = await(agent.kernel().execute(SessionRequest.of("Hello")))

        then: "the session closed with FINISHED status and the FINISH payload"
        result != null
        result.status() == SessionStatus.FINISHED
        result.answer() == "FINISH"
        result.sessionId() != null
        result.metadata().get("phase") == "FINISH"

        and: "state() reflects the finished session"
        def state = agent.kernel().state()
        state.phase() == "FINISH"
        state.iteration() >= 1
        state.sessionId() == result.sessionId()
    }

    def "kernel cancel() before start stops the session with CANCELLED result"() {
        given: "agent with an immediate-FINISH planner, cancelled before execute"
        def agent = new Agent("kernel-cancel")
        agent.withPlannerService(finishPlanner())
        agent.kernel().cancel()

        when: "running a session through the kernel contract"
        def result = await(agent.kernel().execute(SessionRequest.of("Hello")))

        then: "the session was cancelled at the safe point"
        result.status() == SessionStatus.CANCELLED
        result.answer().contains("Cancelled")
    }

    def "execute honors sessionId and maxIterations from SessionRequest options"() {
        given: "agent with an immediate-FINISH planner"
        def agent = new Agent("kernel-options")
        agent.withPlannerService(finishPlanner())

        when: "running with explicit session id and budget"
        def options = [maxIterations: 3]
        def result = await(agent.kernel().execute(
                SessionRequest.of("client-session-1", "Hello", null, options)))

        then: "the session id and budget are honored"
        result.sessionId() == "client-session-1"
        agent.kernel().state().sessionId() == "client-session-1"
        agent.kernel().state().maxIterations() == 3
    }

    def "events() supports subscribe and unsubscribe"() {
        given: "an agent and a counting listener"
        def agent = new Agent("kernel-events")
        def counter = new AtomicInteger(0)
        def listener = new EventStream.Listener() {
            @Override
            void onThought(String reasoning) { counter.incrementAndGet() }
        }

        when: "subscribing and unsubscribing"
        agent.events().subscribe(listener)
        agent.events().unsubscribe(listener)

        then: "no exception and listener detached"
        noExceptionThrown()
        counter.get() == 0
    }
}
