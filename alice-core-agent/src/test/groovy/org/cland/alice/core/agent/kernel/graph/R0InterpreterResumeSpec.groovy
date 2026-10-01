package org.cland.alice.core.agent.kernel.graph

import spock.lang.Specification
import spock.lang.Title

/**
 * R0Interpreter — 安全点快照 + 恢复续跑等价性（#178 M1-S3）。
 *
 * 验收核心：resume 续跑后 trace/artifacts == 不中断一次跑。
 */
@Title("R0Interpreter — 恢复续跑等价")
class R0InterpreterResumeSpec extends Specification {

    private static SessionGraph linearGraph() {
        SessionGraph.builder("g")
                .node(GraphNode.of("d0", NodeKind.DECISION))
                .node(GraphNode.of("e0", NodeKind.EFFECT, [tool: "echo"]))
                .node(GraphNode.of("g0", NodeKind.GATE))
                .node(GraphNode.of("end", NodeKind.TERMINAL))
                .start("d0")
                .edge("d0", "e0")
                .edge("e0", "g0")
                .edge("g0", "end", R0Interpreter.PORT_PASS)
                .terminalExit("end", SessionGraph.SESSION_END)
                .build()
    }

    private static R0Interpreter wiredLinear() {
        new R0Interpreter()
                .decision("d0", { n, l, o -> Decision.finishGoal("next") } as DecisionSource)
                .effect("e0", { call -> EffectOutcome.ok("obs-" + call.name()) } as EffectGateway)
                .gate("g0", { n, l, o -> GateResult.passed() } as GatePolicy)
    }

    def "resume from a captured safe point reproduces the uninterrupted run"() {
        given: "一次完整跑，捕获 g0 安全点"
        def graph = linearGraph()
        def captured = []
        def reference = wiredLinear().run(graph, [
                (R0Interpreter.OPTION_CHECKPOINT_SINK): { SafePoint sp -> captured << sp } as CheckpointSink])

        def g0SafePoint = captured.find { it.pos() == "g0" }
        assert g0SafePoint != null : "g0 safe point not captured: ${captured*.pos()}"

        when: "从 g0 安全点恢复续跑（新解释器）"
        def resumed = wiredLinear().run(graph, [(R0Interpreter.OPTION_RESUME): g0SafePoint])

        then: "状态/产物/trace 与不中断一次跑逐项一致"
        reference.status() == SessionOutcome.Status.FINISHED
        resumed.status() == SessionOutcome.Status.FINISHED
        resumed.artifacts() == reference.artifacts()
        resumed.trace() == reference.trace()
        resumed.steps() == reference.steps()
    }

    def "resume rebuilds entry frames for nested composite graphs"() {
        given: "外层 → 复合子图(inner)；在 inner 节点捕获安全点后恢复"
        def inner = SessionGraph.builder("inner")
                .node(GraphNode.of("in-d", NodeKind.DECISION))
                .node(GraphNode.of("in-end", NodeKind.TERMINAL))
                .start("in-d")
                .edge("in-d", "in-end")
                .terminalExit("in-end", "after")
                .build()
        def outer = SessionGraph.builder("outer")
                .node(GraphNode.of("start", NodeKind.DECISION))
                .node(GraphNode.composite("comp", new CompositeSpec(inner, [("in-end"): "after"])))
                .node(GraphNode.of("after", NodeKind.TERMINAL))
                .start("start")
                .edge("start", "comp")
                .terminalExit("after", SessionGraph.SESSION_END)
                .build()

        def captured = []
        def reference = wiredNested().run(outer, [
                (R0Interpreter.OPTION_CHECKPOINT_SINK): { SafePoint sp -> captured << sp } as CheckpointSink])
        def innerSafePoint = captured.find { it.pos() == "in-d" }
        assert innerSafePoint != null : "inner safe point not captured: ${captured*.pos()}"

        when: "从子图内安全点恢复"
        def resumed = wiredNested().run(outer, [(R0Interpreter.OPTION_RESUME): innerSafePoint])

        then: "展开帧重建正确，trace/artifacts 与一次跑一致"
        reference.status() == SessionOutcome.Status.FINISHED
        resumed.status() == SessionOutcome.Status.FINISHED
        resumed.trace() == reference.trace()
        resumed.artifacts() == reference.artifacts()
    }

    private static R0Interpreter wiredNested() {
        new R0Interpreter()
                .decision("start", { n, l, o -> Decision.finishGoal("go") } as DecisionSource)
                .decision("in-d", { n, l, o -> Decision.finishGoal("go") } as DecisionSource)
    }
}
