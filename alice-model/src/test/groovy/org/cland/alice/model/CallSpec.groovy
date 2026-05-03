package org.cland.alice.model

import spock.lang.Specification

class CallSpec extends Specification {

    def "should create Call with default traceId"() {
        given:
        def payload = new Call.Payload("gpt-4o", "hello", [:])

        when:
        def call = Call.builder().payload(payload).build()

        then:
        call.traceId() != null
        call.status() == CallStatus.CREATED
        call.payload() == payload
        call.result() == null
        call.metrics() != null
        call.attributes().isEmpty()
    }

    def "should create Call with custom traceId"() {
        given:
        def payload = new Call.Payload("gpt-4o", "hello", [:])

        when:
        def call = Call.builder()
            .traceId("my-trace-123")
            .payload(payload)
            .build()

        then:
        call.traceId() == "my-trace-123"
    }

    def "should create Call with attributes"() {
        given:
        def payload = new Call.Payload("gpt-4o", "hello", [:])

        when:
        def call = Call.builder()
            .payload(payload)
            .attribute("key1", "val1")
            .attribute("key2", 42)
            .build()

        then:
        call.attributes()["key1"] == "val1"
        call.attributes()["key2"] == 42
    }

    def "transitionTo should work for valid transitions"() {
        given:
        def payload = new Call.Payload("gpt-4o", "hello", [:])
        def call = Call.builder().payload(payload).build()
        assert call.status() == CallStatus.CREATED

        when:
        call.transitionTo(CallStatus.PENDING)
        then: call.status() == CallStatus.PENDING

        when:
        call.transitionTo(CallStatus.RUNNING)
        then: call.status() == CallStatus.RUNNING

        when:
        call.transitionTo(CallStatus.FINISHED)
        then: call.status() == CallStatus.FINISHED
    }

    def "transitionTo should throw for invalid transitions"() {
        given:
        def payload = new Call.Payload("gpt-4o", "hello", [:])
        def call = Call.builder().payload(payload).build()

        when:
        call.transitionTo(CallStatus.FINISHED)

        then:
        thrown(IllegalStateException)
        call.status() == CallStatus.CREATED  // status unchanged
    }

    def "updateResult should set result and record token usage in metrics"() {
        given:
        def payload = new Call.Payload("gpt-4o", "hello", [:])
        def call = Call.builder().payload(payload).build()
        def usage = new Call.TokenUsage(10, 20, 30)
        def response = new Call.Response("Hello world!", usage, [:])

        when:
        call.updateResult(response)

        then:
        call.result() == response
        call.metrics().tokenUsage() == usage
    }

    def "updateResult should throw on null response"() {
        given:
        def payload = new Call.Payload("gpt-4o", "hello", [:])
        def call = Call.builder().payload(payload).build()

        when:
        call.updateResult(null)

        then:
        thrown(NullPointerException)
    }

    def "Payload should reject null modelId"() {
        when:
        new Call.Payload(null, "hello", [:])

        then:
        thrown(NullPointerException)
    }

    def "Payload should reject null prompt"() {
        when:
        new Call.Payload("gpt-4o", null, [:])

        then:
        thrown(NullPointerException)
    }

    def "Payload null parameters should default to empty map"() {
        when:
        def payload = new Call.Payload("gpt-4o", "hello", null)

        then:
        payload.parameters().isEmpty()
    }

    def "Metrics should calculate latency correctly"() {
        given:
        def metrics = new Call.Metrics()

        when: "start and stop"
        metrics.start()
        Thread.sleep(10)
        metrics.stop()

        then:
        metrics.startTime() != null
        metrics.endTime() != null
        metrics.latencyMs() >= 10
    }

    def "Metrics latencyMs should return -1 if not started"() {
        given:
        def metrics = new Call.Metrics()

        expect:
        metrics.latencyMs() == -1
    }

    def "TokenUsage totalTokens should be sum of prompt and completion"() {
        given:
        def usage = new Call.TokenUsage(10, 20, 30)

        expect:
        usage.totalTokens() == 30
    }

    def "TokenUsage totalTokens should be computed as prompt + completion"() {
        given:
        def usage = new Call.TokenUsage(10, 20, 0)

        expect:
        usage.promptTokens() == 10
        usage.completionTokens() == 20
        usage.totalTokens() == 30  // computed as prompt + completion
    }
}
