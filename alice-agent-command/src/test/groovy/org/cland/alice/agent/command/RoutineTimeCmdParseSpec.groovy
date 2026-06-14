package org.cland.alice.agent.command

import spock.lang.Specification
import spock.lang.Title

/**
 * 验证 {@link AgentCommand#parse(String, String, String)} 对 /routine 指令的解析。
 *
 * <p>对应 {@code RoutineTimeCmd-contract.md} 中 Integration Contract: AgentCommand.parse() 的契约。
 */
@Title("AgentCommand.parse() /routine 指令解析")
class RoutineTimeCmdParseSpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE  = "trace-abc"

    // ========================================================================
    // /routine 解析为 RegisterRoutineCmd
    // ========================================================================

    def "/routine 带 cron 参数应转换为 RegisterRoutineCmd"() {
        when:
        def cmd = AgentCommand.parse("/routine 0 */2 * * * ?", SESSION, TRACE)

        then:
        cmd instanceof RoutineTimeCmd.RegisterRoutineCmd
        (cmd as RoutineTimeCmd.RegisterRoutineCmd).cronExpression() == "0 */2 * * * ?"
    }

    def "/routine 无参数应转换为 RegisterRoutineCmd 且 cron 为空字符串"() {
        when:
        def cmd = AgentCommand.parse("/routine", SESSION, TRACE)

        then:
        cmd instanceof RoutineTimeCmd.RegisterRoutineCmd
        (cmd as RoutineTimeCmd.RegisterRoutineCmd).cronExpression() == ""
    }

    def "/routine 带空参数应转换为 RegisterRoutineCmd 且 cron 为空字符串"() {
        when:
        def cmd = AgentCommand.parse("/routine   ", SESSION, TRACE)

        then:
        cmd instanceof RoutineTimeCmd.RegisterRoutineCmd
        (cmd as RoutineTimeCmd.RegisterRoutineCmd).cronExpression() == ""
    }

    // ========================================================================
    // 非斜杠输入仍应返回 AcquireGoalCmd（无回归）
    // ========================================================================

    def "自然语言输入仍应转换为 AcquireGoalCmd（无回归）"() {
        when:
        def cmd = AgentCommand.parse("hello world", SESSION, TRACE)

        then:
        cmd instanceof ExecutionCmd.AcquireGoalCmd
    }

    // ========================================================================
    // 字段继承验证
    // ========================================================================

    def "/routine 解析结果应继承 sessionId 和 traceId"() {
        when:
        def cmd = AgentCommand.parse("/routine 0 */2 * * * ?", SESSION, TRACE)

        then:
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
        cmd.timestamp() != null
    }

    def "/routine 无参数结果应继承 sessionId 和 traceId"() {
        when:
        def cmd = AgentCommand.parse("/routine", SESSION, TRACE)

        then:
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
        cmd.timestamp() != null
    }

    // ========================================================================
    // 其他斜杠命令无回归
    // ========================================================================

    def "/run 仍应转换为 AcquireGoalCmd（无回归）"() {
        expect:
        AgentCommand.parse("/run task", SESSION, TRACE) instanceof ExecutionCmd.AcquireGoalCmd
        AgentCommand.parse("/exec ls", SESSION, TRACE) instanceof ExecutionCmd.ExecuteRawCmd
    }

    def "/model 仍应转换为 SwitchModelCmd（无回归）"() {
        expect:
        AgentCommand.parse("/model gpt-4", SESSION, TRACE) instanceof AlignmentCmd.SwitchModelCmd
    }

    def "/new 仍应转换为 ResetSessionCmd（无回归）"() {
        expect:
        AgentCommand.parse("/new", SESSION, TRACE) instanceof ControlCmd.ResetSessionCmd
    }
}
