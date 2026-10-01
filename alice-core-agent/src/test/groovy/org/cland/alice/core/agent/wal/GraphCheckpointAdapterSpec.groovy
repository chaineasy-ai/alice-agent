package org.cland.alice.core.agent.wal

import org.cland.alice.core.agent.kernel.graph.GoalRef
import org.cland.alice.core.agent.kernel.graph.LedgerState
import org.cland.alice.core.agent.kernel.graph.SafePoint
import org.cland.alice.core.agent.kernel.graph.ToolCallReq
import spock.lang.Specification
import spock.lang.Title

/**
 * GraphCheckpointAdapter — SafePoint ↔ wal.Checkpoint 往返（#178 M1-S3）。
 */
@Title("GraphCheckpointAdapter — 图内核安全点 Checkpoint 适配")
class GraphCheckpointAdapterSpec extends Specification {

    private static SafePoint sampleSafePoint() {
        def ledgerState = new LedgerState(
                [GoalRef.of("g1", "one")], 0, [route: "next"], [answer: "A"],
                [g1: 1], [:], ["step1:vp(GATE)"])
        return new SafePoint("vp", 7, "obs", ToolCallReq.of("echo", [x: 1]), [act: 2], ledgerState)
    }

    def "toCheckpoint maps node/steps and carries SafePoint in variableSnapshot"() {
        when:
        def cp = GraphCheckpointAdapter.toCheckpoint("s1", sampleSafePoint())

        then:
        cp.sessionId() == "s1"
        cp.stateNode() == "vp"
        cp.lastAppliedMessageId() == 7
        cp.schemaVersion() == GraphCheckpointAdapter.GRAPH_SCHEMA_VERSION
        GraphCheckpointAdapter.isGraphCheckpoint(cp)
    }

    def "fromCheckpoint round-trips SafePoint exactly"() {
        given:
        def sp = sampleSafePoint()

        when:
        def cp = GraphCheckpointAdapter.toCheckpoint("s1", sp)
        def back = GraphCheckpointAdapter.fromCheckpoint(cp)

        then:
        back.present
        back.get() == sp
        back.get().ledgerState() == sp.ledgerState()
    }

    def "non-graph checkpoint is not detected and yields empty"() {
        given:
        def plain = new Checkpoint(0, "s2", 3, "ACTING", [k: "v"], null, 1000)

        expect:
        !GraphCheckpointAdapter.isGraphCheckpoint(plain)
        GraphCheckpointAdapter.fromCheckpoint(plain).isEmpty()
        GraphCheckpointAdapter.fromCheckpoint(null).isEmpty()
    }

    def "store persists and returns latest graph checkpoint"() {
        given:
        def store = new InMemoryWalStore()
        def sp = sampleSafePoint()

        when:
        store.saveCheckpoint(GraphCheckpointAdapter.toCheckpoint("s1", sp))
        def latest = store.getLatestCheckpoint("s1")

        then:
        latest.present
        GraphCheckpointAdapter.fromCheckpoint(latest.get()).get() == sp
    }
}
