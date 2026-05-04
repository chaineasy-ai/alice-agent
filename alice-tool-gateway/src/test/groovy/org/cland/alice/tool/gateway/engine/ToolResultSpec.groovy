package org.cland.alice.tool.gateway.engine

import spock.lang.Specification

class ToolResultSpec extends Specification {

    def "should create success result"() {
        when:
        def result = ToolResult.success("Task completed")

        then:
        result.status() == ToolResult.Status.SUCCESS
        result.summary() == "Task completed"
        result.isSuccess()
        !result.isFailure()
        !result.isTimeout()
    }

    def "should create success result with raw data"() {
        when:
        def result = ToolResult.success("Task completed", '{"key": "value"}')

        then:
        result.status() == ToolResult.Status.SUCCESS
        result.summary() == "Task completed"
        result.rawData() == '{"key": "value"}'
    }

    def "should create failure result"() {
        when:
        def result = ToolResult.failure("Something went wrong")

        then:
        result.status() == ToolResult.Status.FAILURE
        result.summary() == "Something went wrong"
        result.isFailure()
        !result.isSuccess()
        !result.isTimeout()
    }

    def "should create timeout result"() {
        when:
        def result = ToolResult.timeout("file_reader")

        then:
        result.status() == ToolResult.Status.TIMEOUT
        result.summary() == "Timeout on: file_reader"
        result.isTimeout()
        !result.isSuccess()
        !result.isFailure()
    }

    def "should use builder with all fields"() {
        when:
        def result = ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .summary("Got data")
            .rawData("raw output")
            .metadata([key: "value"])
            .timestampMs(123456789L)
            .build()

        then:
        result.status() == ToolResult.Status.SUCCESS
        result.summary() == "Got data"
        result.rawData() == "raw output"
        result.metadata()["key"] == "value"
        result.timestampMs() == 123456789L
    }

    def "should have default timestamp on creation"() {
        when:
        def result = ToolResult.success("test")

        then:
        result.timestampMs() > 0
        result.timestampMs() <= System.currentTimeMillis()
    }

    def "should create unmodifiable metadata"() {
        given:
        def result = ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .summary("test")
            .metadata([originalKey: "original"])
            .build()

        when:
        result.metadata().put("newKey", "newValue")

        then:
        thrown(UnsupportedOperationException)
    }

    def "toString should include status and summary"() {
        expect:
        ToolResult.success("All good").toString().contains("SUCCESS")
        ToolResult.success("All good").toString().contains("All good")
        ToolResult.failure("Boom").toString().contains("FAILURE")
        ToolResult.failure("Boom").toString().contains("Boom")
    }

    def "Status enum should have expected values"() {
        expect:
        ToolResult.Status.values() as Set == [
            ToolResult.Status.SUCCESS,
            ToolResult.Status.FAILURE,
            ToolResult.Status.TIMEOUT
        ] as Set
    }

    def "should handle null raw data"() {
        when:
        def result = ToolResult.success("no data")

        then:
        result.rawData() == null
    }
}
