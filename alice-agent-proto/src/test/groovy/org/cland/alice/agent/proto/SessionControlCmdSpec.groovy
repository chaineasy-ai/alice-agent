package org.cland.alice.agent.proto

import spock.lang.Specification
import spock.lang.Title

/**
 * 测试会话控制命令（2026-09-19 新增）：
 * {@link ControlCmd.SteerCmd}（人工插话·忙时插队）与
 * {@link ControlCmd.AbortCmd}（中止当前轮·会话保留）。
 *
 * 语义边界：AbortCmd ≠ InterruptCmd（后者 = Ctrl+C / 退出进程）。
 */
@Title("会话控制命令：steer / abort")
class SessionControlCmdSpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE   = "trace-xyz"

    // ========================================================================
    // SteerCmd (/steer)
    // ========================================================================

    def "SteerCmd 记录 message/sessionId/traceId 与 reason"() {
        given:
        def cmd = new ControlCmd.SteerCmd("这单别追了", SESSION, TRACE)

        expect:
        cmd.message()   == "这单别追了"
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
        cmd.reason()    == "steer: 这单别追了"
        cmd.timestamp() != null
    }

    def "SteerCmd 拒绝 null message"() {
        when:
        new ControlCmd.SteerCmd(null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // AbortCmd (/abort)
    // ========================================================================

    def "AbortCmd reason 与 InterruptCmd 区分（会话保留 vs 退出）"() {
        given:
        def abort = new ControlCmd.AbortCmd(SESSION, TRACE)
        def interrupt = new ControlCmd.InterruptCmd("user-exit", SESSION, TRACE)

        expect:
        abort.reason()     == "abort-round"
        interrupt.reason() == "interrupt: user-exit"
        abort.reason() != interrupt.reason()
    }

    // ========================================================================
    // 解析入口（AgentCommand.parse）
    // ========================================================================

    def "parse(/steer …) 命中 SteerCmd"() {
        when:
        def cmd = AgentCommand.parse("/steer 先回这条", SESSION, TRACE)

        then:
        cmd instanceof ControlCmd.SteerCmd
        cmd.message() == "先回这条"
        cmd.sessionId() == SESSION
    }

    def "parse(/abort) 命中 AbortCmd"() {
        when:
        def cmd = AgentCommand.parse("/abort", SESSION, TRACE)

        then:
        cmd instanceof ControlCmd.AbortCmd
    }

    def "steer/abort 均属于 ControlCmd 密封体系"() {
        expect:
        AgentCommand.parse("/steer hi", SESSION, TRACE).with { it instanceof ControlCmd }
        AgentCommand.parse("/abort", SESSION, TRACE).with { it instanceof ControlCmd }
    }
}
