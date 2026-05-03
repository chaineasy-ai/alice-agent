package org.cland.alice.model

import spock.lang.Specification

class CallStatusSpec extends Specification {

    def "CREATED can transition to PENDING and ABORTED"() {
        expect:
        CallStatus.CREATED.canTransitionTo(CallStatus.PENDING)
        CallStatus.CREATED.canTransitionTo(CallStatus.ABORTED)
    }

    def "CREATED cannot transition to invalid states"() {
        expect:
        !CallStatus.CREATED.canTransitionTo(CallStatus.RUNNING)
        !CallStatus.CREATED.canTransitionTo(CallStatus.FINISHED)
        !CallStatus.CREATED.canTransitionTo(CallStatus.FAILED)
        !CallStatus.CREATED.canTransitionTo(CallStatus.RETRY)
    }

    def "PENDING can transition to RUNNING, RETRY, ABORTED"() {
        expect:
        CallStatus.PENDING.canTransitionTo(CallStatus.RUNNING)
        CallStatus.PENDING.canTransitionTo(CallStatus.RETRY)
        CallStatus.PENDING.canTransitionTo(CallStatus.ABORTED)
    }

    def "RUNNING can transition to FINISHED, FAILED, RETRY"() {
        expect:
        CallStatus.RUNNING.canTransitionTo(CallStatus.FINISHED)
        CallStatus.RUNNING.canTransitionTo(CallStatus.FAILED)
        CallStatus.RUNNING.canTransitionTo(CallStatus.RETRY)
    }

    def "RUNNING cannot transition back to CREATED or PENDING"() {
        expect:
        !CallStatus.RUNNING.canTransitionTo(CallStatus.CREATED)
        !CallStatus.RUNNING.canTransitionTo(CallStatus.PENDING)
    }

    def "RETRY can transition to PENDING or FAILED"() {
        expect:
        CallStatus.RETRY.canTransitionTo(CallStatus.PENDING)
        CallStatus.RETRY.canTransitionTo(CallStatus.FAILED)
    }

    def "RETRY cannot go back to RUNNING directly"() {
        expect:
        !CallStatus.RETRY.canTransitionTo(CallStatus.RUNNING)
        !CallStatus.RETRY.canTransitionTo(CallStatus.FINISHED)
    }

    def "terminal states (ABORTED, FAILED, FINISHED) reject all transitions"() {
        expect:
        !CallStatus.ABORTED.canTransitionTo(CallStatus.CREATED)
        !CallStatus.ABORTED.canTransitionTo(CallStatus.FINISHED)
        !CallStatus.FAILED.canTransitionTo(CallStatus.CREATED)
        !CallStatus.FAILED.canTransitionTo(CallStatus.FINISHED)
        !CallStatus.FINISHED.canTransitionTo(CallStatus.CREATED)
        !CallStatus.FINISHED.canTransitionTo(CallStatus.PENDING)
        !CallStatus.FINISHED.canTransitionTo(CallStatus.RUNNING)
    }
}
