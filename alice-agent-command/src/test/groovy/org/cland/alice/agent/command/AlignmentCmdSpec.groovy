package org.cland.alice.agent.command

import spock.lang.Specification
import spock.lang.Title

/**
 * 测试 {@link AlignmentCmd} 及其子类型 {@link AlignmentCmd.SwitchModelCmd}.
 */
@Title("AlignmentCmd 密封接口")
class AlignmentCmdSpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE  = "trace-xyz"

    // ========================================================================
    // SwitchModelCmd (/model)
    // ========================================================================

    def "SwitchModelCmd 应记录 modelId, sessionId, traceId"() {
        given:
        def cmd = new AlignmentCmd.SwitchModelCmd("claude-3.5-sonnet", SESSION, TRACE)

        expect:
        cmd.modelId()   == "claude-3.5-sonnet"
        cmd.value()     == "claude-3.5-sonnet"
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
        cmd.timestamp() != null
    }

    def "SwitchModelCmd 可指定自定义时间戳"() {
        given:
        def ts = java.time.Instant.parse("2026-05-25T02:00:00Z")
        def cmd = new AlignmentCmd.SwitchModelCmd("gpt-4o", SESSION, TRACE, ts)

        expect:
        cmd.timestamp() == ts
    }

    def "SwitchModelCmd 拒绝 null modelId"() {
        when:
        new AlignmentCmd.SwitchModelCmd(null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "SwitchModelCmd 拒绝 null sessionId"() {
        when:
        new AlignmentCmd.SwitchModelCmd("model", null, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "SwitchModelCmd 拒绝 null traceId"() {
        when:
        new AlignmentCmd.SwitchModelCmd("model", SESSION, null)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // Record 相等性
    // ========================================================================

    def "相同字段的两个 SwitchModelCmd 应相等"() {
        given:
        def ts = java.time.Instant.now()
        def a = new AlignmentCmd.SwitchModelCmd("gpt-4", SESSION, TRACE, ts)
        def b = new AlignmentCmd.SwitchModelCmd("gpt-4", SESSION, TRACE, ts)

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    def "不同 modelId 的 SwitchModelCmd 不应相等"() {
        expect:
        new AlignmentCmd.SwitchModelCmd("gpt-4", SESSION, TRACE) !=
        new AlignmentCmd.SwitchModelCmd("claude", SESSION, TRACE)
    }
}
