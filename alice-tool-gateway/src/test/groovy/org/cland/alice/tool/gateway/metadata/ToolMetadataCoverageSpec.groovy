package org.cland.alice.tool.gateway.metadata

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.cland.alice.tool.gateway.annotation.RiskLevel
import spock.lang.Specification
import java.lang.invoke.MethodHandles

/**
 * ToolMetadata 补充测试 — 覆盖 invoke 分支、toString、null params
 */
class ToolMetadataCoverageSpec extends Specification {

    def mapper = new ObjectMapper()

    // ========== SampleBean for ToolMetadata MethodHandle ==========

    static class HelperBean {
        String echo(String msg) { return msg != null ? msg : "null-input" }
        String noop() { return "noop" }
    }

    def schema() {
        def s = mapper.createObjectNode() as ObjectNode
        s.put("type", "object")
        return s
    }

    def "ToolMetadata toString returns readable string"() {
        given:
        def handle = MethodHandles.lookup().unreflect(HelperBean.getMethod("echo", String))
        def meta = ToolMetadata.builder()
            .name("echo")
            .inputSchema(schema())
            .targetMethod(handle)
            .targetBean(new HelperBean())
            .build()

        expect:
        meta.toString().contains("echo")
    }

    def "ToolMetadata invoke with 0-param method"() {
        given:
        def meta = ToolMetadata.builder()
            .name("constant")
            .inputSchema(schema())
            .targetMethod(MethodHandles.constant(String, "fixed"))
            .build()

        when:
        def result = meta.invoke([:])

        then:
        result == "fixed"
    }

    def "ToolMetadata invoke with null params"() {
        given:
        def handle = MethodHandles.lookup().unreflect(HelperBean.getMethod("echo", String))
        def meta = ToolMetadata.builder()
            .name("echo")
            .inputSchema(schema())
            .targetMethod(handle)
            .targetBean(new HelperBean())
            .build()

        when:
        def result = meta.invoke(null)

        then:
        result == "null-input"
    }

    def "ToolMetadata builder default risk is LOW"() {
        given:
        def handle = MethodHandles.lookup().unreflect(HelperBean.getMethod("echo", String))
        def meta = ToolMetadata.builder()
            .name("test")
            .inputSchema(schema())
            .targetMethod(handle)
            .targetBean(new HelperBean())
            .build()

        expect:
        meta.riskLevel() == RiskLevel.LOW
    }

    def "ToolMetadata builder null description defaults to empty"() {
        given:
        def handle = MethodHandles.lookup().unreflect(HelperBean.getMethod("echo", String))
        def meta = ToolMetadata.builder()
            .name("test")
            .inputSchema(schema())
            .targetMethod(handle)
            .targetBean(new HelperBean())
            .build()

        expect:
        meta.description() == ""
    }

    def "ToolMetadata invoke with 0-param on bean"() {
        given:
        def handle = MethodHandles.lookup().unreflect(HelperBean.getMethod("noop"))
        def meta = ToolMetadata.builder()
            .name("noop")
            .inputSchema(schema())
            .targetMethod(handle)
            .targetBean(new HelperBean())
            .build()

        when:
        def result = meta.invoke([:])

        then:
        result == "noop"
    }
}
