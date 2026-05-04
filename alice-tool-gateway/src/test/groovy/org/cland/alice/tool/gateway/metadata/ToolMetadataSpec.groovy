package org.cland.alice.tool.gateway.metadata

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.cland.alice.tool.gateway.annotation.RiskLevel
import spock.lang.Specification
import java.lang.invoke.MethodHandles

class ToolMetadataSpec extends Specification {

    def mapper = new ObjectMapper()

    def "should build metadata with all fields"() {
        given:
        def schema = mapper.createObjectNode() as ObjectNode
        schema.put("type", "object")
        def handle = MethodHandles.lookup().unreflect(
            SampleBean.getMethod("echo", String)
        )

        when:
        def meta = ToolMetadata.builder()
            .name("echo")
            .description("Echoes input")
            .inputSchema(schema)
            .targetMethod(handle)
            .targetBean(new SampleBean())
            .riskLevel(RiskLevel.MEDIUM)
            .returnType(String)
            .paramNames(["msg"] as String[])
            .build()

        then:
        meta.name() == "echo"
        meta.description() == "Echoes input"
        meta.inputSchema().path("type").asText() == "object"
        meta.targetMethod() != null
        meta.targetBean() != null
        meta.riskLevel() == RiskLevel.MEDIUM
        meta.returnType() == String
        meta.paramNames() == ["msg"] as String[]
    }

    def "should build metadata with defaults"() {
        given:
        def schema = mapper.createObjectNode() as ObjectNode
        def handle = MethodHandles.lookup().unreflect(
            SampleBean.getMethod("noop")
        )

        when:
        def meta = ToolMetadata.builder()
            .name("noop")
            .inputSchema(schema)
            .targetMethod(handle)
            .build()

        then:
        meta.name() == "noop"
        meta.description() == ""
        meta.riskLevel() == RiskLevel.LOW
        meta.targetBean() == null
        meta.returnType() == null
        meta.paramNames().length == 0
    }

    def "should reject null required fields"() {
        when:
        ToolMetadata.builder().build()

        then:
        thrown(NullPointerException)
    }

    def "should invoke method with params via target bean"() {
        given:
        def bean = new SampleBean()
        def handle = MethodHandles.lookup().unreflect(
            SampleBean.getMethod("add", int, int)
        )
        def meta = ToolMetadata.builder()
            .name("add")
            .inputSchema(mapper.createObjectNode() as ObjectNode)
            .targetMethod(handle)
            .targetBean(bean)
            .paramNames(["a", "b"] as String[])
            .build()

        when:
        def result = meta.invoke([a: 3, b: 4])

        then:
        result == 7
    }

    def "should invoke no-arg method"() {
        given:
        def bean = new SampleBean()
        def handle = MethodHandles.lookup().unreflect(
            SampleBean.getMethod("noop")
        )
        def meta = ToolMetadata.builder()
            .name("noop")
            .inputSchema(mapper.createObjectNode() as ObjectNode)
            .targetMethod(handle)
            .targetBean(bean)
            .build()

        when:
        def result = meta.invoke([:])

        then:
        result == "done"
    }

    def "should invoke static method without bean"() {
        given:
        def handle = MethodHandles.lookup().unreflect(
            SampleBean.getMethod("staticHello")
        )
        def meta = ToolMetadata.builder()
            .name("staticHello")
            .inputSchema(mapper.createObjectNode() as ObjectNode)
            .targetMethod(handle)
            .build()

        when:
        def result = meta.invoke([:])

        then:
        result == "hello from static"
    }

    def "toString should include name and risk"() {
        given:
        def schema = mapper.createObjectNode() as ObjectNode
        def handle = MethodHandles.lookup().unreflect(
            SampleBean.getMethod("noop")
        )
        def meta = ToolMetadata.builder()
            .name("myTool")
            .inputSchema(schema)
            .targetMethod(handle)
            .riskLevel(RiskLevel.HIGH)
            .build()

        expect:
        meta.toString().contains("myTool")
        meta.toString().contains("HIGH")
    }

    // ========== Helper bean ==========

    static class SampleBean {
        String echo(String msg) {
            return msg
        }

        String noop() {
            return "done"
        }

        int add(int a, int b) {
            return a + b
        }

        static String staticHello() {
            return "hello from static"
        }
    }
}
