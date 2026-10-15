package org.cland.alice.agent.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Title

/**
 * 测试 {@link JsonLogEncoder}：JSON Lines 字段 + 转义 + 单行。
 */
@Title("JsonLogEncoder — JSON Lines 结构化日志")
class JsonLogEncoderSpec extends Specification {

    JsonLogEncoder encoder = new JsonLogEncoder()
    LoggerContext ctx = new LoggerContext()

    def setup() {
        ctx.setMDCAdapter(new LogbackMDCAdapter())
    }

    private LoggingEvent event(Level level, String msg, Throwable t = null) {
        return new LoggingEvent("fqcn", ctx.getLogger("test.Logger"), level, msg, t, null)
    }

    def "输出合法单行 JSON 且含规范字段"() {
        when:
        String line = new String(encoder.encode(event(Level.INFO, "hello agent")), "UTF-8")

        then:
        line.endsWith("\n")
        line.count("\n") == 1

        and:
        def json = new JsonSlurper().parseText(line.trim())
        json.level == "INFO"
        json.app == "alice-agent"
        json.trace_id == "-"
        json.logger == "test.Logger"
        json.message == "hello agent"
        json.timestamp instanceof String
        json.timestamp.length() > 0
    }

    def "消息中的引号/换行/制表符被转义，仍是合法 JSON"() {
        given:
        String msg = "line1 \"quoted\"\nline2\tend\\x"

        when:
        String line = new String(encoder.encode(event(Level.WARN, msg)), "UTF-8")

        then: "不含裸换行（除结尾）"
        line.count("\n") == 1

        and:
        def json = new JsonSlurper().parseText(line.trim())
        json.message == msg
    }

    def "ERROR 带异常时输出 exception 字段"() {
        given:
        def ex = new IllegalStateException("boom")

        when:
        String line = new String(encoder.encode(event(Level.ERROR, "failed", ex)), "UTF-8")

        then:
        def json = new JsonSlurper().parseText(line.trim())
        json.level == "ERROR"
        json.exception.toString().contains("IllegalStateException")
        json.exception.toString().contains("boom")
    }

    def "MDC 中的 trace_id 被透传"() {
        given:
        def e = event(Level.INFO, "with trace")
        e.setMDCPropertyMap(["trace_id": "abc123"])

        when:
        def json = new JsonSlurper().parseText(new String(encoder.encode(e), "UTF-8").trim())

        then:
        json.trace_id == "abc123"
    }

    def "escape 处理控制字符"() {
        expect:
        JsonLogEncoder.escape(null) == ""
        JsonLogEncoder.escape("a\u0001b").contains("\\u0001")
    }
}
