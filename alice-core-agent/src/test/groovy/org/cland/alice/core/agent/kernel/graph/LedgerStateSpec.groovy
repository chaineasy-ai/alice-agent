package org.cland.alice.core.agent.kernel.graph

import spock.lang.Specification
import spock.lang.Title

/**
 * LedgerState — 账本快照/恢复的往返等值（#178 M1-S3）。
 */
@Title("LedgerState — 账本快照往返")
class LedgerStateSpec extends Specification {

    def "snapshot then restore reproduces all ledger slots"() {
        given:
        def ledger = new Ledger()
        ledger.bindGoals([GoalRef.of("g1", "one"), GoalRef.of("g2", "two")])
        ledger.recordArbitration([route: "next", budget: 3])
        ledger.recordArtifact("answer", "A")
        ledger.bumpRevision("g1")
        ledger.markAborted("g2", "boom")
        ledger.appendTrace("t1")

        when:
        def state = ledger.snapshot()
        def restored = new Ledger()
        restored.restore(state)

        then:
        restored.goals() == [GoalRef.of("g1", "one"), GoalRef.of("g2", "two")]
        restored.cursor() == 0
        restored.route() == [route: "next", budget: 3]
        restored.artifacts() == [answer: "A"]
        restored.revisionsOf("g1") == 1
        restored.isAborted("g2")
        restored.trace() == ledger.trace()
        state.trace() == ["ledger: bindGoals(2)", "ledger: arbitration", "ledger: artifact[answer]",
                          "ledger: revision[g1]=1", "ledger: abort[g2]", "t1"]
    }

    def "restore(null) yields empty ledger state"() {
        given:
        def ledger = new Ledger()
        ledger.bindGoals([GoalRef.of("g1", "one")])

        when:
        ledger.restore(null)

        then:
        ledger.goals().isEmpty()
        ledger.cursor() == -1
        ledger.artifacts().isEmpty()
        ledger.trace().isEmpty()
    }
}
