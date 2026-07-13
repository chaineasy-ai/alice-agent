package org.cland.alice.core.agent

import io.vertx.core.Vertx
import org.cland.alice.core.agent.executor.AgentExecutor
import org.cland.alice.core.agent.lifecycle.Action
import org.cland.alice.core.agent.result.StepResult
import org.cland.alice.core.planner.Plan
import org.cland.alice.core.planner.PlannerService
import org.cland.alice.core.planner.strategy.DecisionStrategy
import org.cland.alice.core.planner.strategy.StrategySelector
import spock.lang.Specification
import spock.lang.Timeout

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test Agent PPAO loop flow.
 *
 * Uses mock StrategySelector (via Spock Stub) to control PlannerService output
 * and verify the Perceive → Plan → Verify(Pre) → Act → Observe → Verify(Post) → Reflect
 * flow without real LLM calls.
 *
 * Tests only use FINISH / REVISION / OBSERVE actions since LLM_INFERENCE requires
 * a real ModelProvider with registered suppliers.
 */
@Timeout(10)
class AgentPpaoLoopSpec extends Specification {

    // ========================================================================
    // Helper: create sequenced StrategySelector
    // ========================================================================

    private StrategySelector sequencedStrategy(List<Plan> plans) {
        def callIndex = new AtomicInteger(0)
        def fastPath = Stub(DecisionStrategy) {
            decide(_ as Map) >> { Map ctx ->
                int idx = callIndex.getAndIncrement()
                if (idx < plans.size()) return plans[idx]
                return Plan.builder().type(Plan.Type.FAST_PATH).summary("Default finish")
                        .addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH")).build()
            }
        }
        def slowPath = Stub(DecisionStrategy) {
            decide(_ as Map) >> { Plan.builder().type(Plan.Type.SLOW_PATH).summary("fallback")
                    .addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH")).build() }
        }
        return StrategySelector.builder()
                .fastPath(fastPath).slowPath(slowPath)
                .complexityFunction { _ -> false }
                .build()
    }

    private StrategySelector finishStrategy() {
        sequencedStrategy([
            Plan.builder().type(Plan.Type.FAST_PATH).summary("Immediate finish")
                    .addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH")).build()
        ])
    }

    private StrategySelector revisionThenFinishStrategy() {
        sequencedStrategy([
            Plan.builder().type(Plan.Type.FAST_PATH).summary("Revision")
                    .addStep(Plan.Step.of(Plan.Intent.REVISION, "REVISION", ["feedback": "Re-evaluate"])).build(),
            Plan.builder().type(Plan.Type.FAST_PATH).summary("Done")
                    .addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH")).build()
        ])
    }

    private StrategySelector observeThenFinishStrategy() {
        sequencedStrategy([
            Plan.builder().type(Plan.Type.FAST_PATH).summary("Answer then finish")
                    .addStep(Plan.Step.of(Plan.Intent.ANSWER, "ANSWER")).build(),
            Plan.builder().type(Plan.Type.FAST_PATH).summary("Done")
                    .addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH")).build()
        ])
    }

    private PlannerService makePlanner(StrategySelector sel) {
        PlannerService.builder().strategySelector(sel).build()
    }

    // ========================================================================
    // Tests for Agent.ask() - FINISH path (works via ctx.put("result"))
    // ========================================================================

    def "should complete PPAO loop when planner returns FINISH immediately"() {
        given:
        def agent = new Agent("test-finish-immediate")
        agent.withPlannerService(makePlanner(finishStrategy()))

        when:
        String result = agent.ask("Hello")

        then:
        result == "FINISH"
    }

    def "multiple agents with FINISH planner should be independent"() {
        given:
        def planner = makePlanner(finishStrategy())
        def a1 = new Agent("one"); def a2 = new Agent("two")
        a1.withPlannerService(planner); a2.withPlannerService(planner)

        when:
        def r1 = a1.ask("Hello"); def r2 = a2.ask("Hello")

        then:
        r1 == "FINISH"; r2 == "FINISH"
        a1.agentId() != a2.agentId()
    }

    // ========================================================================
    // Tests for Agent.run() - works without "result" in context
    // ========================================================================

    def "should handle revision via run()"() {
        given:
        def planner = makePlanner(revisionThenFinishStrategy())
        def agent = new Agent("test-revision", AgentConfig.builder().maxIterations(10).build())
        agent.withPlannerService(planner)
        def ctx = new AgentContext(10)
        ctx.put("prompt", "Hello")

        when:
        agent.run(ctx)

        then:
        ctx.currentPhase() == AgentContext.Phase.FINISH
    }

    def "should handle non-FINISH intent via run()"() {
        given:
        def planner = makePlanner(observeThenFinishStrategy())
        def agent = new Agent("test-observe", AgentConfig.builder().maxIterations(10).build())
        agent.withPlannerService(planner)
        def ctx = new AgentContext(10)
        ctx.put("prompt", "Hello")

        when:
        agent.run(ctx)

        then:
        ctx.currentPhase() == AgentContext.Phase.FINISH
    }

    def "should stop at max iterations via run()"() {
        given:
        def planner = makePlanner(observeThenFinishStrategy())
        def agent = new Agent("test-max-iter", AgentConfig.builder().maxIterations(1).build())
        agent.withPlannerService(planner)
        def ctx = new AgentContext(1)
        ctx.put("prompt", "Hello")

        when:
        agent.run(ctx)

        then:
        // Max iterations reached → loop terminates gracefully
        // Phase could be FINISH (if reflect set it) or the phase set in perceive/loopBody
        noExceptionThrown()
    }

    // ========================================================================
    // Tests for Agent.run() with default context
    // ========================================================================

    def "run() with default context should not throw"() {
        given:
        def agent = new Agent("test-run-default")
        agent.withPlannerService(makePlanner(finishStrategy()))

        when:
        agent.run()

        then:
        noExceptionThrown()
    }

    def "run() with custom context should propagate attributes"() {
        given:
        def agent = new Agent("test-run-custom")
        agent.withPlannerService(makePlanner(finishStrategy()))
        def ctx = new AgentContext(5)
        ctx.put("prompt", "Hello"); ctx.put("model", "gpt-4")

        when:
        agent.run(ctx)

        then:
        ctx.containsKey("input")
    }

    // ========================================================================
    // Tests for phase transitions
    // ========================================================================

    def "should transition through PPAO phases correctly"() {
        given:
        def agent = new Agent("test-phases")
        agent.withPlannerService(makePlanner(finishStrategy()))
        def ctx = new AgentContext(10)
        ctx.put("prompt", "Hello")

        when:
        agent.run(ctx)

        then:
        ctx.currentPhase() == AgentContext.Phase.FINISH ||
                ctx.currentPhase() == AgentContext.Phase.REFLECTING
    }

    // ========================================================================
    // Tests for verify hooks
    // ========================================================================

    def "verify hooks should return true when guardrail is null"() {
        given:
        def agent = new Agent("test-preverify")

        expect:
        agent.verifyPre(Action.finish())
        agent.verifyPost(new StepResult.Finish("done"))
    }

    def "shouldFinish should detect termination"() {
        given:
        def agent = new Agent("test-should-finish")

        expect:
        agent.shouldFinish(new AgentContext(5), new StepResult.Finish("done"))
        agent.shouldFinish(new AgentContext(5), new StepResult.Failure("error"))
        !agent.shouldFinish(new AgentContext(5), new StepResult.Continue(Action.llmInference("gpt", "hi")))
    }

    def "shouldFinish should detect FINISH phase even with null result"() {
        given:
        def agent = new Agent("test-phase-finish")
        def ctx = new AgentContext(5)
        // Chain through valid transitions to reach FINISH
        ctx.transitionTo(AgentContext.Phase.PERCEIVING)
        ctx.transitionTo(AgentContext.Phase.PLANNING)
        ctx.transitionTo(AgentContext.Phase.VERIFYING_PRE)
        ctx.transitionTo(AgentContext.Phase.ACTING)
        ctx.transitionTo(AgentContext.Phase.OBSERVING)
        ctx.transitionTo(AgentContext.Phase.VERIFYING_POST)
        ctx.transitionTo(AgentContext.Phase.REFLECTING)
        ctx.transitionTo(AgentContext.Phase.FINISH)

        expect:
        agent.shouldFinish(ctx, null)
    }

    def "shouldFinish should detect max iterations"() {
        given:
        def agent = new Agent("test-max-finish")
        def ctx = new AgentContext(2)
        ctx.incrementIteration(); ctx.incrementIteration()

        expect:
        agent.shouldFinish(ctx, null)
    }

    // ========================================================================
    // Tests for Agent identity and configuration
    // ========================================================================

    def "should set custom agent ID"() {
        when: def agent = new Agent("my-id")
        then: agent.agentId() == "my-id"
    }

    def "should generate 8-char agent ID"() {
        when: def agent = new Agent()
        then: agent.agentId() != null && agent.agentId().length() == 8
    }

    def "should access config properties"() {
        given:
        def config = AgentConfig.builder().defaultModelId("m").maxIterations(7).actionTimeoutMs(15000).build()
        def agent = new Agent("test-config", config)
        expect:
        agent.config().defaultModelId() == "m"
        agent.config().maxIterations() == 7
        agent.config().actionTimeoutMs() == 15000
    }

    def "should provide vertx instance"() {
        when: def agent = new Agent("test-vertx")
        then: agent.vertx() != null
    }

    def "should close without error"() {
        given: def agent = new Agent("test-close")
        when: agent.close()
        then: noExceptionThrown()
    }

    // ========================================================================
    // Tests for AgentContext state machine
    // ========================================================================

    def "idempotent transition should not throw"() {
        given: def ctx = new AgentContext()
        ctx.transitionTo(AgentContext.Phase.PERCEIVING)
        when: ctx.transitionTo(AgentContext.Phase.PERCEIVING)
        then: noExceptionThrown()
    }
}
