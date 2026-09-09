package org.cland.alice.core.agent.kernel.graph

import spock.lang.Specification
import spock.lang.Title

/**
 * R0 解释器语义测试 — 验证 kernel-architecture.md §5.2.3 R0–R4：
 * 类型分派 / 展开帧与 exit port（越层不变式）/ 决策路由 / 注解预算（R3）/ 两个写点（R4）/
 * finish-goal 不终结会话（P1）/ goal 图游标（P2）/ 步数单一来源（P4）。
 */
@Title("R0Interpreter — 图元模型解释器")
class R0InterpreterSpec extends Specification {

    // ========================================================================
    // 基础：类型遍历 + 会话级 terminal
    // ========================================================================

    def "linear decision-answer graph finishes at session terminal"() {
        given:
        def graph = SessionGraph.builder("g")
            .node(GraphNode.of("d1", NodeKind.DECISION))
            .node(GraphNode.of("end", NodeKind.TERMINAL))
            .start("d1")
            .edge("d1", "end", "done")
            .terminalExit("end", SessionGraph.SESSION_END)
            .build()
        def interpreter = new R0Interpreter()
            .decision("d1", { GraphNode n, Ledger l, String obs -> Decision.answer("done", "hello") } as DecisionSource)

        when:
        def outcome = interpreter.run(graph)

        then:
        outcome.status() == SessionOutcome.Status.FINISHED
        outcome.steps() == 2
        outcome.trace().size() == 2
        outcome.trace()[0].contains("d1(DECISION)")
        outcome.trace()[1].contains("end(TERMINAL)")
    }

    def "decision routes along the edge matching d.route"() {
        given:
        def graph = SessionGraph.builder("g")
            .node(GraphNode.of("d", NodeKind.DECISION))
            .node(GraphNode.of("a", NodeKind.TERMINAL))
            .node(GraphNode.of("b", NodeKind.TERMINAL))
            .start("d")
            .edge("d", "a", "go-a")
            .edge("d", "b", "go-b")
            .terminalExit("a", SessionGraph.SESSION_END)
            .terminalExit("b", SessionGraph.SESSION_END)
            .build()

        when: "route selects the b edge"
        def outcome = new R0Interpreter()
            .decision("d", { n, l, o -> Decision.answer("go-b", "x") } as DecisionSource)
            .run(graph)

        then:
        outcome.status() == SessionOutcome.Status.FINISHED
        outcome.trace().any { it.contains("b(TERMINAL)") }
        !outcome.trace().any { it.contains("a(TERMINAL)") }
    }

    def "missing DecisionSource fails structurally"() {
        given:
        def graph = SessionGraph.builder("g")
            .node(GraphNode.of("d", NodeKind.DECISION))
            .node(GraphNode.of("end", NodeKind.TERMINAL))
            .start("d")
            .edge("d", "end", "done")
            .terminalExit("end", SessionGraph.SESSION_END)
            .build()

        when:
        def outcome = new R0Interpreter().run(graph)

        then:
        outcome.status() == SessionOutcome.Status.FAILED
        outcome.message().contains("no DecisionSource")
    }

    // ========================================================================
    // R3 预算注解：effect 次数耗尽 → guard 边
    // ========================================================================

    def "effect budget exhaustion routes to the guard edge"() {
        given:
        def graph = SessionGraph.builder("g")
            .node(GraphNode.of("d", NodeKind.DECISION))
            .node(GraphNode.of("fx", NodeKind.EFFECT, [budgetEffects: 1]))
            .node(GraphNode.of("guard", NodeKind.TERMINAL))
            .node(GraphNode.of("end", NodeKind.TERMINAL))
            .start("d")
            .edge("d", "fx", "act")
            .edge("fx", "d", null)          // 效果主出边：回决策（再次 act）
            .edge("fx", "guard", "guard")   // 预算耗尽 guard 边
            .terminalExit("guard", SessionGraph.SESSION_END)
            .terminalExit("end", SessionGraph.SESSION_END)
            .build()
        def calls = []
        def interpreter = new R0Interpreter()
            .decision("d", { n, l, o -> Decision.act("act", ToolCallReq.of("ping")) } as DecisionSource)
            .effect("fx", { ToolCallReq c -> calls << c.name(); EffectOutcome.ok("pong") } as EffectGateway)

        when:
        def outcome = interpreter.run(graph)

        then: "effect executed exactly once, then budget guard taken"
        outcome.status() == SessionOutcome.Status.FINISHED
        calls == ["ping"]
        outcome.trace().any { it.contains("guard(TERMINAL)") }
    }

    // ========================================================================
    // 展开帧 + exit port（P1：finish-goal 只是 goal 级判据，不终结会话）
    // ========================================================================

    def "goal-level finish inside composite does NOT end the session (P1)"() {
        given:
        def inner = SessionGraph.builder("tao-inner")
            .node(GraphNode.of("thought", NodeKind.DECISION))
            .node(GraphNode.of("goal-done", NodeKind.TERMINAL))
            .start("thought")
            .edge("thought", "goal-done", "finish")
            .build()
        def graph = SessionGraph.builder("session")
            .node(GraphNode.composite("tao", new CompositeSpec(inner, ["goal-done": "arbitrate"])))
            .node(GraphNode.of("arbitrate", NodeKind.DECISION))
            .node(GraphNode.of("session-end", NodeKind.TERMINAL))
            .start("tao")
            .edge("arbitrate", "session-end", "pass")
            .terminalExit("session-end", SessionGraph.SESSION_END)
            .build()
        def interpreter = new R0Interpreter()
            .decision("thought", { n, l, o -> Decision.finishGoal("finish") } as DecisionSource)
            .decision("arbitrate", { n, l, o -> Decision.answer("pass", "all goals done") } as DecisionSource)

        when:
        def outcome = interpreter.run(graph)

        then: "finish-goal exits the composite; session only ends at the outer session terminal"
        outcome.status() == SessionOutcome.Status.FINISHED
        outcome.trace().any { it.contains("tao(COMPOSITE)") }
        outcome.trace().any { it.contains("goal-done(TERMINAL)") }
        outcome.trace().any { it.contains("arbitrate(DECISION)") }
        outcome.steps() == 5
    }

    def "composite exit binding to unknown node is rejected at prepare"() {
        given:
        def inner = SessionGraph.builder("inner")
            .node(GraphNode.of("t", NodeKind.TERMINAL))
            .start("t")
            .build()
        def graph = SessionGraph.builder("session")
            .node(GraphNode.composite("c", new CompositeSpec(inner, [t: "ghost"])))
            .start("c")
            .build()

        when:
        new R0Interpreter().run(graph)

        then:
        thrown(IllegalArgumentException)
    }

    def "terminal without exit port binding fails instead of leaking"() {
        given: "outer terminal lacks a declared exit"
        def graph = SessionGraph.builder("g")
            .node(GraphNode.of("d", NodeKind.DECISION))
            .node(GraphNode.of("end", NodeKind.TERMINAL))
            .start("d")
            .edge("d", "end", "done")
            .build()

        when:
        def outcome = new R0Interpreter()
            .decision("d", { n, l, o -> Decision.answer("done", "x") } as DecisionSource)
            .run(graph)

        then:
        outcome.status() == SessionOutcome.Status.FAILED
        outcome.message().contains("no exit port")
    }

    // ========================================================================
    // R4 写点：goal 绑定经 decision 落账；游标驱动多 goal 遍历（P2）
    // ========================================================================

    def "goal bindings land in ledger and cursor drives goal iteration (P2)"() {
        given:
        def graph = SessionGraph.builder("g")
            .node(GraphNode.of("strategize", NodeKind.DECISION))
            .node(GraphNode.of("vp", NodeKind.GATE))
            .node(GraphNode.of("work", NodeKind.DECISION))
            .node(GraphNode.of("end", NodeKind.TERMINAL))
            .start("strategize")
            .edge("strategize", "vp", "bind")
            .edge("vp", "work", "next")   // guard 边：还有下一个 goal
            .edge("vp", "end", "pass")    // 放行边：全部 goal 完成
            .edge("work", "vp", "done")   // 每个 goal 完成后回到仲裁门
            .terminalExit("end", SessionGraph.SESSION_END)
            .build()
        def visits = []
        def interpreter = new R0Interpreter()
            .decision("strategize", { n, l, o ->
                new Decision(DecisionKind.FINISH_GOAL, "bind", null, [], [GoalRef.of("g1", "one"), GoalRef.of("g2", "two")], [:], [:])
            } as DecisionSource)
            .gate("vp", { n, l, o ->
                visits << (l.currentGoal() != null ? l.currentGoal().id() : "none")
                if (l.hasNextGoal()) {
                    l.advanceGoal()
                    return GateResult.guard("next")
                }
                return GateResult.passed()
            } as GatePolicy)
            .decision("work", { n, l, o -> Decision.finishGoal("done") } as DecisionSource)

        when:
        def outcome = interpreter.run(graph)

        then: "gate advanced through both goals by ledger cursor fact"
        outcome.status() == SessionOutcome.Status.FINISHED
        visits == ["g1", "g2"]   // 进入 gate 时游标依次指向两个 goal
        outcome.trace().any { it.contains("bindGoals(2)") }
        outcome.trace().any { it.contains("advance -> g2") }
    }

    // ========================================================================
    // P4：步数单一来源（遍历步数，maxSteps 注解）
    // ========================================================================

    def "maxSteps budget fails a non-progressing cycle"() {
        given:
        def graph = SessionGraph.builder("g")
            .node(GraphNode.of("d", NodeKind.DECISION))
            .node(GraphNode.of("fx", NodeKind.EFFECT))
            .start("d")
            .edge("d", "fx", "act")
            .edge("fx", "d", null)
            .build()

        when:
        def outcome = new R0Interpreter()
            .decision("d", { n, l, o -> Decision.act("act", ToolCallReq.of("spin")) } as DecisionSource)
            .effect("fx", { ToolCallReq c -> EffectOutcome.ok("again") } as EffectGateway)
            .run(graph, [maxSteps: 20])

        then:
        outcome.status() == SessionOutcome.Status.FAILED
        outcome.message().contains("step budget exhausted")
        outcome.steps() == 21
    }

    // ========================================================================
    // Ledger 槽位语义（R4 写点纪律）
    // ========================================================================

    def "ledger goal cursor and revision slots behave per R4"() {
        given:
        def ledger = new Ledger()

        when: "no goals bound"
        then:
        !ledger.hasGoals()
        ledger.currentGoal() == null
        !ledger.hasNextGoal()

        when: "write point: bind two goals"
        ledger.bindGoals([GoalRef.of("a", "A"), GoalRef.of("b", "B")])
        then:
        ledger.hasGoals()
        ledger.currentGoal().id() == "a"
        ledger.hasNextGoal()

        when: "advance and bump revision"
        ledger.advanceGoal()
        ledger.bumpRevision("b")
        ledger.bumpRevision("b")
        then:
        ledger.currentGoal().id() == "b"
        !ledger.hasNextGoal()
        ledger.revisionsOf("b") == 2
        ledger.revisionsOf("a") == 0

        when: "abort recorded"
        ledger.markAborted("a", "user said stop")
        then:
        ledger.isAborted("a")

        and: "trace is append-only"
        ledger.trace().size() >= 5
    }
}
