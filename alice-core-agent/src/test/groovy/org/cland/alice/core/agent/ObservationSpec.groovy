package org.cland.alice.core.agent

import org.cland.alice.core.agent.lifecycle.Observation
import spock.lang.Specification

class ObservationSpec extends Specification {

    def "should create success observation"() {
        when:
        def obs = Observation.success("Task completed")

        then:
        obs.status() == Observation.Status.SUCCESS
        obs.summary() == "Task completed"
        obs.isSuccess()
        !obs.needsRevision()
    }

    def "should create failure observation"() {
        when:
        def obs = Observation.failure("Task failed")

        then:
        obs.status() == Observation.Status.FAILURE
        obs.summary() == "Task failed"
        !obs.isSuccess()
        obs.needsRevision()
    }

    def "should create blocked observation"() {
        when:
        def obs = Observation.blocked("Policy violation")

        then:
        obs.status() == Observation.Status.BLOCKED
        obs.summary() == "Blocked: Policy violation"
        obs.needsRevision()
    }

    def "should create timeout observation"() {
        when:
        def obs = Observation.timeout("myTool")

        then:
        obs.status() == Observation.Status.TIMEOUT
        obs.summary() == "Timeout on: myTool"
        obs.needsRevision()
    }

    def "should use builder with all fields"() {
        when:
        def obs = Observation.builder()
            .status(Observation.Status.PARTIAL)
            .summary("Got partial data")
            .rawData("some raw output")
            .metadata([key: "value"])
            .timestampMs(123456789L)
            .build()

        then:
        obs.status() == Observation.Status.PARTIAL
        obs.summary() == "Got partial data"
        obs.rawData() == "some raw output"
        obs.metadata()["key"] == "value"
        obs.timestampMs() == 123456789L
    }
}
