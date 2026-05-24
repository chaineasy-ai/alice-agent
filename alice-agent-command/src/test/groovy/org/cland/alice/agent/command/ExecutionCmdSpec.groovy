package org.cland.alice.agent.command

import spock.lang.Specification
import spock.lang.Title

/**
 * 测试 {@link ExecutionCmd} 及其子类型 {@link ExecutionCmd.AcquireGoalCmd}、
 * {@link ExecutionCmd.ExecuteRawCmd} 的构造与密封约束。
 */
@Title("ExecutionCmd 密封接口")
class ExecutionCmdSpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE  = "trace-xyz"

    // ========================================================================
    // AcquireGoalCmd
    // ========================================================================

    def "AcquireGoalCmd 应记录 goal, sessionId, traceId"() {
        given:
        def cmd = new ExecutionCmd.AcquireGoalCmd("帮我查天气", SESSION, TRACE)

        expect:
        cmd.goal()      == "帮我查天气"
        cmd.task()      == "帮我查天气"
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
        cmd.timestamp() != null
    }

    def "AcquireGoalCmd 可指定自定义时间戳"() {
        given:
        def ts = java.time.Instant.parse("2026-05-25T00:00:00Z")
        def cmd = new ExecutionCmd.AcquireGoalCmd("task", SESSION, TRACE, ts)

        expect:
        cmd.timestamp() == ts
    }

    def "AcquireGoalCmd 拒绝 null goal"() {
        when:
        new ExecutionCmd.AcquireGoalCmd(null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "AcquireGoalCmd 拒绝 null sessionId"() {
        when:
        new ExecutionCmd.AcquireGoalCmd("task", null, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "AcquireGoalCmd 拒绝 null traceId"() {
        when:
        new ExecutionCmd.AcquireGoalCmd("task", SESSION, null)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // ExecuteRawCmd
    // ========================================================================

    def "ExecuteRawCmd 应记录 command, sessionId, traceId"() {
        given:
        def cmd = new ExecutionCmd.ExecuteRawCmd("ls -la", SESSION, TRACE)

        expect:
        cmd.command()   == "ls -la"
        cmd.task()      == "ls -la"
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
        cmd.timestamp() != null
    }

    def "ExecuteRawCmd 可指定自定义时间戳"() {
        given:
        def ts = java.time.Instant.parse("2026-05-25T01:00:00Z")
        def cmd = new ExecutionCmd.ExecuteRawCmd("date", SESSION, TRACE, ts)

        expect:
        cmd.timestamp() == ts
    }

    def "ExecuteRawCmd 拒绝 null command"() {
        when:
        new ExecutionCmd.ExecuteRawCmd(null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // Record 方法
    // ========================================================================

    def "相同字段的两个 AcquireGoalCmd 应相等"() {
        given:
        def ts = java.time.Instant.now()
        def a = new ExecutionCmd.AcquireGoalCmd("task", SESSION, TRACE, ts)
        def b = new ExecutionCmd.AcquireGoalCmd("task", SESSION, TRACE, ts)

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    def "不同 goal 的 AcquireGoalCmd 不应相等"() {
        given:
        def a = new ExecutionCmd.AcquireGoalCmd("task-a", SESSION, TRACE)
        def b = new ExecutionCmd.AcquireGoalCmd("task-b", SESSION, TRACE)

        expect:
        a != b
    }
}
