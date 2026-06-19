package org.cland.alice.tool.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.cland.alice.tool.gateway.annotation.RiskLevel
import org.cland.alice.tool.gateway.metadata.ToolMetadata
import spock.lang.Specification
import java.lang.invoke.MethodHandles

class ToolRegistrySpec extends Specification {

    def registry = new ToolRegistry()
    def mapper = new ObjectMapper()

    def setup() {
        registry = new ToolRegistry()
    }

    def "should register and lookup a tool"() {
        given:
        def schema = mapper.createObjectNode()
        def handle = MethodHandles.lookup().unreflect(
            getClass().getMethod("sampleMethod", String)
        )
        def metadata = ToolMetadata.builder()
            .name("greeter")
            .description("Greets someone")
            .inputSchema(schema)
            .targetMethod(handle)
            .targetBean(this)
            .riskLevel(RiskLevel.LOW)
            .returnType(String)
            .build()

        when:
        registry.register(metadata)
        def found = registry.lookup("greeter")

        then:
        found.name() == "greeter"
        found.description() == "Greets someone"
        found.riskLevel() == RiskLevel.LOW
        registry.hasTool("greeter")
        registry.toolNames() == ["greeter"] as Set
        registry.size() == 1
    }

    def "should silently skip duplicate registration"() {
        given:
        def schema = mapper.createObjectNode() as ObjectNode
        def handle = MethodHandles.lookup().unreflect(
            getClass().getMethod("sampleMethod", String)
        )
        def meta1 = ToolMetadata.builder()
            .name("dup")
            .inputSchema(schema)
            .targetMethod(handle)
            .build()
        def meta2 = ToolMetadata.builder()
            .name("dup")
            .inputSchema(schema)
            .targetMethod(handle)
            .build()
        registry.register(meta1)

        when:
        registry.register(meta2)

        then:
        noExceptionThrown()
        registry.lookup("dup") == meta1 // 保持第一个注册
    }

    def "should throw on lookup of unknown tool"() {
        when:
        registry.lookup("nonexistent")

        then:
        thrown(IllegalArgumentException)
    }

    def "should unregister a tool"() {
        given:
        def schema = mapper.createObjectNode() as ObjectNode
        def handle = MethodHandles.lookup().unreflect(
            getClass().getMethod("sampleMethod", String)
        )
        def meta = ToolMetadata.builder()
            .name("temp")
            .inputSchema(schema)
            .targetMethod(handle)
            .build()
        registry.register(meta)
        assert registry.size() == 1

        when:
        registry.unregister("temp")

        then:
        registry.size() == 0
        !registry.hasTool("temp")
    }

    def "should return all tools as unmodifiable collection"() {
        given:
        def schema = mapper.createObjectNode() as ObjectNode
        def handle = MethodHandles.lookup().unreflect(
            getClass().getMethod("sampleMethod", String)
        )
        registry.register(ToolMetadata.builder()
            .name("a").inputSchema(schema).targetMethod(handle).build())
        registry.register(ToolMetadata.builder()
            .name("b").inputSchema(schema).targetMethod(handle).build())

        when:
        def all = registry.allTools()

        then:
        all.size() == 2
    }

    def "should generate function calling schema"() {
        given:
        def schema = mapper.createObjectNode() as ObjectNode
        schema.put("type", "object")
        schema.putObject("properties")
        schema.putArray("required")
        def handle = MethodHandles.lookup().unreflect(
            getClass().getMethod("sampleMethod", String)
        )
        registry.register(ToolMetadata.builder()
            .name("test_tool")
            .description("A test tool")
            .inputSchema(schema)
            .targetMethod(handle)
            .riskLevel(RiskLevel.LOW)
            .returnType(String)
            .build())

        when:
        def fcSchema = registry.toFunctionCallingSchema()

        then:
        fcSchema.size() == 1
        fcSchema[0].type == "function"
        fcSchema[0].function.name == "test_tool"
        fcSchema[0].function.description == "A test tool"
        fcSchema[0].function.parameters != null
    }

    def "legacy execute should return true on success"() {
        given:
        def schema = mapper.createObjectNode() as ObjectNode
        def handle = MethodHandles.lookup().unreflect(
            getClass().getMethod("sampleMethod", String)
        )
        def meta = ToolMetadata.builder()
            .name("legacy")
            .inputSchema(schema)
            .targetMethod(handle)
            .targetBean(this)
            .paramNames(["arg"] as String[])
            .build()
        registry.register(meta)

        when:
        boolean result = registry.execute("legacy", [arg: "test"])

        then:
        result == true
    }

    def "legacy execute should return false on failure"() {
        given:
        def schema = mapper.createObjectNode() as ObjectNode
        def handle = MethodHandles.lookup().unreflect(
            getClass().getMethod("failingMethod")
        )
        def meta = ToolMetadata.builder()
            .name("failing")
            .inputSchema(schema)
            .targetMethod(handle)
            .targetBean(this)
            .build()
        registry.register(meta)

        when:
        boolean result = registry.execute("failing", [:])

        then:
        result == false
    }

    // ========== Sample methods used as tool targets ==========

    /** Sample method used by tests via MethodHandle */
    public String sampleMethod(String arg) {
        return "Hello, $arg!"
    }

    /** Failing method used by tests */
    public String failingMethod() {
        throw new RuntimeException("Intentional failure")
    }
}
