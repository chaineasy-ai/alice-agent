package org.cland.alice.tool.gateway.engine

import com.fasterxml.jackson.databind.ObjectMapper
import org.cland.alice.tool.gateway.ToolRegistry
import org.cland.alice.tool.gateway.annotation.RiskLevel
import spock.lang.Specification

/**
 * 测试用 Bean 定义在 {@link ExecutionEngineTestBeans} 中（Java 类）。
 */
class ExecutionEngineSpec extends Specification {

    def registry
    def engine
    def mapper = new ObjectMapper()
    def toolBean = new ExecutionEngineTestBeans.TestTools()

    def setup() {
        registry = new ToolRegistry()
        def discovery = new ToolDiscovery(registry)
        discovery.scanAndRegister([toolBean])
        engine = ExecutionEngine.builder()
            .registry(registry)
            .defaultTimeoutMs(5000)
            .build()
    }

    def cleanup() {
        engine?.shutdown()
    }

    def "should invoke simple tool and return success"() {
        when:
        def result = engine.invoke("greet", [name: "Alice"])

        then:
        result.isSuccess()
        result.summary().contains("greet")
        result.rawData().contains("Hello, Alice!")
    }

    def "should invoke tool without parameters"() {
        when:
        def result = engine.invoke("ping", [:])

        then:
        result.isSuccess()
        result.rawData() == "pong"
    }

    def "should invoke tool that returns structured data"() {
        when:
        def result = engine.invoke("getPerson", [id: 1])

        then:
        result.isSuccess()
        result.rawData().contains("Alice")
    }

    def "should return failure for unregistered tool"() {
        when:
        def result = engine.invoke("nonexistent_tool", [:])

        then:
        result.isFailure()
        result.summary().contains("nonexistent_tool")
        result.summary().contains("not found")
    }

    def "should return failure for tool that throws exception"() {
        when:
        def result = engine.invoke("fail", [:])

        then:
        result.isFailure()
        // HIGH 风险等级未配置沙箱提供者，返回配置错误
        // 若配置了上层的沙箱，则会捕获异常并返回更具体的错误
        result.summary().contains("No sandbox provider configured")
    }

    def "should invoke tool with multiple params"() {
        when:
        def result = engine.invoke("add", [a: 10, b: 20])

        then:
        result.isSuccess()
        result.rawData() == "30"
    }

    def "should handle null params gracefully"() {
        when:
        def result = engine.invoke("ping", null)

        then:
        result.isSuccess()
        result.rawData() == "pong"
    }

    def "should handle empty tool name"() {
        when:
        def result = engine.invoke("", [:])

        then:
        result.isFailure()
        result.summary().contains("empty")

        when:
        result = engine.invoke(null, [:])

        then:
        result.isFailure()
        result.summary().contains("null")
    }

    def "engine shutdown should not throw"() {
        given:
        def localEngine = ExecutionEngine.builder()
            .registry(registry)
            .build()

        when:
        localEngine.shutdown()

        then:
        noExceptionThrown()
    }

    def "should match risk level in metadata"() {
        given:
        def greetMeta = registry.lookup("greet")

        expect:
        greetMeta.riskLevel() == RiskLevel.LOW

        and:
        registry.lookup("fail").riskLevel() == RiskLevel.HIGH
    }
}
