package org.cland.alice.agent.command

import spock.lang.Specification
import spock.lang.Title

/**
 * 测试 {@link ControlCmd} 及其子类型：
 * {@link ControlCmd.ResetSessionCmd},
 * {@link ControlCmd.FeedbackCmd},
 * {@link ControlCmd.InterruptCmd}.
 */
@Title("ControlCmd 密封接口")
class ControlCmdSpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE  = "trace-xyz"

    // ========================================================================
    // ResetSessionCmd (/new)
    // ========================================================================

    def "ResetSessionCmd 应记录 sessionId, traceId, reason"() {
        given:
        def cmd = new ControlCmd.ResetSessionCmd(SESSION, TRACE)

        expect:
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
        cmd.reason()    == "reset-session"
        cmd.timestamp() != null
    }

    def "ResetSessionCmd 拒绝 null sessionId"() {
        when:
        new ControlCmd.ResetSessionCmd(null, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "ResetSessionCmd 拒绝 null traceId"() {
        when:
        new ControlCmd.ResetSessionCmd(SESSION, null)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // FeedbackCmd (/feedback)
    // ========================================================================

    def "FeedbackCmd 应记录 message, sessionId, traceId"() {
        given:
        def cmd = new ControlCmd.FeedbackCmd("请缩短回答", SESSION, TRACE)

        expect:
        cmd.message()   == "请缩短回答"
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
        cmd.reason().contains("human-feedback")
        cmd.reason().contains("请缩短回答")
    }

    def "FeedbackCmd 拒绝 null message"() {
        when:
        new ControlCmd.FeedbackCmd(null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "FeedbackCmd 拒绝 null sessionId"() {
        when:
        new ControlCmd.FeedbackCmd("msg", null, TRACE)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // InterruptCmd (Ctrl+C / /exit)
    // ========================================================================

    def "InterruptCmd 应记录 cause, sessionId, traceId"() {
        given:
        def cmd = new ControlCmd.InterruptCmd("user-exit", SESSION, TRACE)

        expect:
        cmd.cause()     == "user-exit"
        cmd.reason()    == "interrupt: user-exit"
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
        cmd.timestamp() != null
    }

    def "InterruptCmd 拒绝 null cause"() {
        when:
        new ControlCmd.InterruptCmd(null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "InterruptCmd 接受空字符串 cause"() {
        when:
        def cmd = new ControlCmd.InterruptCmd("", SESSION, TRACE)

        then:
        cmd.cause() == ""
    }

    // ========================================================================
    // Record 相等性
    // ========================================================================

    def "相同字段的两个 ResetSessionCmd 应相等"() {
        given:
        def ts = java.time.Instant.now()
        def a = new ControlCmd.ResetSessionCmd(SESSION, TRACE, ts)
        def b = new ControlCmd.ResetSessionCmd(SESSION, TRACE, ts)

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    def "不同 cause 的 InterruptCmd 不应相等"() {
        expect:
        new ControlCmd.InterruptCmd("exit", SESSION, TRACE) !=
        new ControlCmd.InterruptCmd("cancel", SESSION, TRACE)
    }
}
