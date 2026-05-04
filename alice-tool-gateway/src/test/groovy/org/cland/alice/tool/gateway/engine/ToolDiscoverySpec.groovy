package org.cland.alice.tool.gateway.engine

import com.fasterxml.jackson.databind.ObjectMapper
import org.cland.alice.tool.gateway.ToolRegistry
import org.cland.alice.tool.gateway.annotation.RiskLevel
import spock.lang.Specification

/**
 * 注意：测试用 Bean 定义在 {@link ToolDiscoveryTestBeans} 中（Java 类），
 * 以确保 @AgentTool 和 @ToolParam 注解在运行时对反射可见。
 * Groovy 内部静态类上的注解可能不会正确保留到运行时。
 */
class ToolDiscoverySpec extends Specification {

    def registry
    def discovery

    def setup() {
        registry = new ToolRegistry()
        discovery = new ToolDiscovery(registry)
    }

    def "should discover and register annotated methods"() {
        when:
        int count = discovery.scanAndRegister([new ToolDiscoveryTestBeans.DiscoveredBean()])

        then:
        count == 3
        registry.hasTool("hello")
        registry.hasTool("square")
        registry.hasTool("concat")
    }

    def "should generate correct metadata from annotations"() {
        given:
        discovery.scanAndRegister([new ToolDiscoveryTestBeans.DiscoveredBean()])

        when:
        def hello = registry.lookup("hello")

        then:
        hello.name() == "hello"
        hello.description() == "Says hello"
        hello.riskLevel() == RiskLevel.LOW
        hello.returnType() == String

        when:
        def square = registry.lookup("square")

        then:
        square.name() == "square"
        square.riskLevel() == RiskLevel.MEDIUM
        square.returnType() == int
    }

    def "should generate JSON Schema for discovered tools"() {
        given:
        discovery.scanAndRegister([new ToolDiscoveryTestBeans.DiscoveredBean()])

        when:
        def schema = registry.lookup("hello").inputSchema()

        then:
        schema.path("type").asText() == "object"
        schema.path("properties").path("name").path("type").asText() == "string"

        when:
        schema = registry.lookup("concat").inputSchema()

        then:
        schema.path("type").asText() == "object"
        schema.path("properties").path("a").path("type").asText() == "string"
        schema.path("properties").path("b").path("type").asText() == "string"
    }

    def "should handle empty bean list"() {
        when:
        int count = discovery.scanAndRegister([])

        then:
        count == 0

        when:
        count = discovery.scanAndRegister(null)

        then:
        count == 0
    }

    def "should throw on duplicate tools across beans"() {
        given:
        discovery.scanAndRegister([new ToolDiscoveryTestBeans.DiscoveredBean()])

        when:
        discovery.scanAndRegister([new ToolDiscoveryTestBeans.DuplicateBean()])

        then:
        def e = thrown(RuntimeException)
        // 异常消息包含注册失败的概括信息
        e.message.contains("error(s)")
        // 被抑制的异常或其 cause 包含具体的冲突信息
        e.suppressed.any { sup ->
            sup.message.contains("already registered")
                || (sup.cause?.message ?: "").contains("already registered")
        }
    }

    def "should register tools from multiple beans"() {
        given:
        discovery.scanAndRegister([new ToolDiscoveryTestBeans.DiscoveredBean()])
        discovery.scanAndRegister([new ToolDiscoveryTestBeans.ExtraBean()])

        expect:
        registry.size() == 5
        registry.hasTool("hello")
        registry.hasTool("extraTool")
    }

    def "should invoke discovered tool via metadata"() {
        given:
        def bean = new ToolDiscoveryTestBeans.DiscoveredBean()
        discovery.scanAndRegister([bean])

        when:
        def result = registry.lookup("hello").invoke([name: "World"])

        then:
        result == "Hello, World!"
    }
}
