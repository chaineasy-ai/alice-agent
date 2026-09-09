package org.cland.alice.core.agent.kernel.graph
import java.util.concurrent.atomic.AtomicInteger

import spock.lang.Specification
import spock.lang.Title

/**
 * 标准会话骨架（§5.3 规划 → TAO → 反思）端到端语义测试：
 * goal 图游标多目标推进（P2）、finish-goal 不终结会话（P1）、verifyPost(g) gate（P7）、
 * FAIL 修订回路（同 goal 重跑）、修订超限/路线偏差回规划（D9 反思重入）、abort、效果熔断注解（R3）。
 */
@Title("StandardSkeleton — 规划-TAO-反思 三回路")
class StandardSkeletonSpec extends Specification {

    /** 便捷装配：默认 verifyPost 放行、效果网关记账。 */
    private StandardSkeleton skeleton(
            StandardSkeleton.StrategizeBrain strategize,
            StandardSkeleton.ActorBrain actor,
            StandardSkeleton.ArbitrationBrain arbitrate,
            Map cfg = [:], List effectCalls = []) {
        return new StandardSkeleton(
                "test task",
                strategize,
                actor,
                arbitrate,
                cfg.verifyPost,
                { ToolCallReq c -> effectCalls << c.name(); EffectOutcome.ok("result-of-" + c.name()) } as EffectGateway,
                cfg.revisionBudget != null ? cfg.revisionBudget : 2,
                cfg.effectBudget != null ? cfg.effectBudget : 10)
    }

    private static StandardSkeleton.StrategizeBrain bindGoals(String... ids) {
        { String task, Ledger l ->
            StandardSkeleton.StrategyPlan.of(ids.collect { String id -> GoalRef.of(id, "goal " + id) })
        } as StandardSkeleton.StrategizeBrain
    }

    private static StandardSkeleton.ActorBrain actOnceThenDone(String tool = "read_file") {
        { GoalRef g, Ledger l, String obs ->
            obs.isEmpty()
                ? new StandardSkeleton.ActorStep.Act(ToolCallReq.of(tool))
                : new StandardSkeleton.ActorStep.Done("")
        } as StandardSkeleton.ActorBrain
    }

    private static StandardSkeleton.ArbitrationBrain alwaysPass() {
        { GoalRef g, Ledger l, String obs -> StandardSkeleton.Arbitration.pass() } as StandardSkeleton.ArbitrationBrain
    }

    def "happy path: multi-goal plan drives per-goal TAO then session end"() {
        given:
        def effectCalls = []
        def actorCalls = []
        def skeleton = skeleton(
                bindGoals("g1", "g2"),
                { GoalRef g, Ledger l, String obs ->
                    actorCalls << g.id()
                    if (g.id() == "g1" && obs.isEmpty()) {
                        return new StandardSkeleton.ActorStep.Act(ToolCallReq.of("read_file"))
                    }
                    return new StandardSkeleton.ActorStep.Done("")
                } as StandardSkeleton.ActorBrain,
                alwaysPass(),
                [:], effectCalls)

        when:
        def outcome = skeleton.run()

        then:
        outcome.status() == SessionOutcome.Status.FINISHED
        // 两个 goal 各跑了一次 TAO（Thought 至少各一次）
        actorCalls.findAll { it == "g1" }.size() == 2
        actorCalls.findAll { it == "g2" }.size() == 1
        // 一个效果经 Action 执行
        effectCalls == ["read_file"]
        def trace = outcome.trace()
        trace.count { it.contains("tao(COMPOSITE)") } == 2
        trace.any { it.contains("ledger: advance -> g2") }
        trace.last().contains("end-all(TERMINAL)")
        // finish-goal 只是 goal 级判据提交：会话在全部 goal 通过后才结束（P1+P2）
        trace.count { it.contains("arbitrate(DECISION)") } == 2
    }

    def "verifyPost gate rejection routes back to the same goal TAO (revision loop)"() {
        given:
        def vpCount = new AtomicInteger(0)
        def skeleton = skeleton(
                bindGoals("g1"),
                actOnceThenDone(),
                alwaysPass(),
                [verifyPost: { GraphNode n, Ledger l, String obs ->
                    if (vpCount.getAndIncrement() == 0) {
                        return GateResult.guard("reject")
                    }
                    return GateResult.passed()
                } as GatePolicy],
                [])

        when:
        def outcome = skeleton.run()

        then:
        outcome.status() == SessionOutcome.Status.FINISHED
        def trace = outcome.trace()
        trace.any { it.contains("vp=BLOCK(reject)") }
        trace.count { it.contains("tao(COMPOSITE)") } == 2   // 同 goal 重跑
        trace.any { it.contains("ledger: revision[g1]=1") }
    }

    def "revision budget exhaustion re-enters STRATEGIZE (D9)"() {
        given:
        def planCalls = new AtomicInteger(0)
        def skeleton = skeleton(
                { String task, Ledger l ->
                    planCalls.incrementAndGet()
                    if (planCalls.get() == 1) {
                        return StandardSkeleton.StrategyPlan.of([GoalRef.of("g1", "hard")])
                    }
                    return StandardSkeleton.StrategyPlan.of([GoalRef.of("g9", "easier")])
                } as StandardSkeleton.StrategizeBrain,
                actOnceThenDone("probe"),
                { GoalRef g, Ledger l, String obs ->
                    if (g.id() == "g1") {
                        return StandardSkeleton.Arbitration.fail("still not good")
                    }
                    return StandardSkeleton.Arbitration.pass()
                } as StandardSkeleton.ArbitrationBrain,
                [revisionBudget: 1],
                [])

        when:
        def outcome = skeleton.run()

        then:
        outcome.status() == SessionOutcome.Status.FINISHED
        def trace = outcome.trace()
        // 修订 1 次后超限 → 回规划重新审慎 → 新 goal 图
        planCalls.get() == 2
        trace.count { it.contains("S(COMPOSITE)") } == 2
        trace.any { it.contains("rev-budget=BLOCK(exceed)") }
        trace.any { it.contains("ledger: bindGoals(1)") }
        trace.any { it.contains("ledger: revision[g1]=1") }
        // 回规划后绑定 g9 走新 goal（无 g1→g9 的游标推进）
        !trace.any { it.contains("ledger: advance -> g9") }
    }

    def "route deviation returns to STRATEGIZE for re-deliberation"() {
        given:
        def arbitrations = new AtomicInteger(0)
        def skeleton = skeleton(
                bindGoals("g1"),
                actOnceThenDone(),
                { GoalRef g, Ledger l, String obs ->
                    arbitrations.incrementAndGet()
                    if (arbitrations.get() == 1) {
                        return StandardSkeleton.Arbitration.route("wrong route taken")
                    }
                    return StandardSkeleton.Arbitration.pass()
                } as StandardSkeleton.ArbitrationBrain,
                [:], [])

        when:
        def outcome = skeleton.run()

        then:
        outcome.status() == SessionOutcome.Status.FINISHED
        def trace = outcome.trace()
        trace.count { it.contains("S(COMPOSITE)") } == 2
        trace.any { it.contains("ledger: arbitration") }   // routeDeviation 落账（写点 ①）
    }

    def "abort verdict skips remaining goals and ends the session"() {
        given:
        def actorCalls = []
        def skeleton = skeleton(
                bindGoals("g1", "g2"),
                { GoalRef g, Ledger l, String obs ->
                    actorCalls << g.id()
                    return new StandardSkeleton.ActorStep.Done("")
                } as StandardSkeleton.ActorBrain,
                { GoalRef g, Ledger l, String obs -> StandardSkeleton.Arbitration.abort("user cancelled") } as StandardSkeleton.ArbitrationBrain,
                [:], [])

        when:
        def outcome = skeleton.run()

        then:
        outcome.status() == SessionOutcome.Status.FINISHED
        def trace = outcome.trace()
        trace.any { it.contains("arbitrate(DECISION)") }
        trace.last().contains("end-all(TERMINAL)")
        // 只处理了第一个 goal 就 abort 结束
        actorCalls == ["g1"]
    }

    def "empty plan ends the session immediately"() {
        given:
        def actorCalls = []
        def skeleton = skeleton(
                bindGoals(),
                { GoalRef g, Ledger l, String obs ->
                    actorCalls << g.id()
                    return new StandardSkeleton.ActorStep.Done("")
                } as StandardSkeleton.ActorBrain,
                alwaysPass(),
                [:], [])

        when:
        def outcome = skeleton.run()

        then:
        outcome.status() == SessionOutcome.Status.FINISHED
        actorCalls.isEmpty()
        outcome.trace().any { it.contains("goal-avail=BLOCK(none)") }
    }

    def "TAO effect budget acts as circuit breaker and exits the goal"() {
        given:
        def effectCalls = []
        def skeleton = skeleton(
                bindGoals("g1"),
                { GoalRef g, Ledger l, String obs ->
                    new StandardSkeleton.ActorStep.Act(ToolCallReq.of("spin"))
                } as StandardSkeleton.ActorBrain,
                alwaysPass(),
                [effectBudget: 1],
                effectCalls)

        when:
        def outcome = skeleton.run()

        then:
        outcome.status() == SessionOutcome.Status.FINISHED
        effectCalls.size() == 1  // 熔断：只执行一次效果
        outcome.trace().any { it.contains("effect:spin ok=true") }
        outcome.trace().last().contains("end-all(TERMINAL)")
    }

    def "session end only reachable from the outermost session terminal"() {
        given:
        def skeleton = skeleton(bindGoals("g1"), actOnceThenDone(), alwaysPass(), [:], [])

        when:
        def outcome = skeleton.run()

        then: "FINISHED 只能来自 end-all(TERMINAL) 的 SESSION_END 出口"
        outcome.status() == SessionOutcome.Status.FINISHED
        outcome.trace().last().contains("end-all(TERMINAL)")
        outcome.message() == "session ended"
    }
}
