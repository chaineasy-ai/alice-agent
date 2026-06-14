package org.cland.alice.agent.command

import spock.lang.Specification
import spock.lang.Title

import java.time.Instant

/**
 * 验证 {@link RoutineTimeCmd} 密封接口及其两个记录类型的正确性。
 *
 * <p>对应 {@code RoutineTimeCmd-contract.md} 中的 Identity、Null safety、Blank allowed 契约。
 */
@Title("RoutineTimeCmd 密封接口")
class RoutineTimeCmdSpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE  = "trace-abc"
    static final Instant NOW   = Instant.now()

    // ========================================================================
    // RegisterRoutineCmd — 用户面向 /routine 指令
    // ========================================================================

    def "RegisterRoutineCmd 应存储 cron 表达式"() {
        given:
        def cmd = new RoutineTimeCmd.RegisterRoutineCmd("0 */2 * * * ?", SESSION, TRACE, NOW)

        expect:
        cmd.cronExpression() == "0 */2 * * * ?"
    }

    def "RegisterRoutineCmd task() 应返回 cron 表达式"() {
        given:
        def cmd = new RoutineTimeCmd.RegisterRoutineCmd("0 */2 * * * ?", SESSION, TRACE, NOW)

        expect:
        cmd.task() == "0 */2 * * * ?"
    }

    def "RegisterRoutineCmd 应继承 sessionId 和 traceId"() {
        given:
        def cmd = new RoutineTimeCmd.RegisterRoutineCmd("0 */2 * * * ?", SESSION, TRACE, NOW)

        expect:
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
    }

    def "RegisterRoutineCmd 应携带时间戳"() {
        given:
        def cmd = new RoutineTimeCmd.RegisterRoutineCmd("0 */2 * * * ?", SESSION, TRACE, NOW)

        expect:
        cmd.timestamp() == NOW
    }

    def "RegisterRoutineCmd cronExpression 为空字符串时可接受"() {
        given:
        def cmd = new RoutineTimeCmd.RegisterRoutineCmd("", SESSION, TRACE, NOW)

        expect:
        cmd.cronExpression() == ""
    }

    def "RegisterRoutineCmd null 字段应抛出 NullPointerException"() {
        when:
        new RoutineTimeCmd.RegisterRoutineCmd(null, SESSION, TRACE, NOW)

        then:
        thrown(NullPointerException)

        when:
        new RoutineTimeCmd.RegisterRoutineCmd("cron", null, TRACE, NOW)

        then:
        thrown(NullPointerException)

        when:
        new RoutineTimeCmd.RegisterRoutineCmd("cron", SESSION, null, NOW)

        then:
        thrown(NullPointerException)

        when:
        new RoutineTimeCmd.RegisterRoutineCmd("cron", SESSION, TRACE, null)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // TimeTriggeredCmd — 内核触发 /routine 执行
    // ========================================================================

    def "TimeTriggeredCmd 应存储 routineGoal"() {
        given:
        def cmd = new RoutineTimeCmd.TimeTriggeredCmd("health-check", SESSION, TRACE, NOW)

        expect:
        cmd.routineGoal() == "health-check"
    }

    def "TimeTriggeredCmd task() 应返回 routineGoal"() {
        given:
        def cmd = new RoutineTimeCmd.TimeTriggeredCmd("health-check", SESSION, TRACE, NOW)

        expect:
        cmd.task() == "health-check"
    }

    def "TimeTriggeredCmd 应继承 sessionId 和 traceId"() {
        given:
        def cmd = new RoutineTimeCmd.TimeTriggeredCmd("health-check", SESSION, TRACE, NOW)

        expect:
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
    }

    def "TimeTriggeredCmd 应携带时间戳"() {
        given:
        def cmd = new RoutineTimeCmd.TimeTriggeredCmd("health-check", SESSION, TRACE, NOW)

        expect:
        cmd.timestamp() == NOW
    }

    def "TimeTriggeredCmd null 字段应抛出 NullPointerException"() {
        when:
        new RoutineTimeCmd.TimeTriggeredCmd(null, SESSION, TRACE, NOW)

        then:
        thrown(NullPointerException)

        when:
        new RoutineTimeCmd.TimeTriggeredCmd("g", null, TRACE, NOW)

        then:
        thrown(NullPointerException)

        when:
        new RoutineTimeCmd.TimeTriggeredCmd("g", SESSION, null, NOW)

        then:
        thrown(NullPointerException)

        when:
        new RoutineTimeCmd.TimeTriggeredCmd("g", SESSION, TRACE, null)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // 密封层级验证
    // ========================================================================

    def "RegisterRoutineCmd 应 instanceof RoutineTimeCmd"() {
        expect:
        new RoutineTimeCmd.RegisterRoutineCmd("cron", SESSION, TRACE, NOW) instanceof RoutineTimeCmd
    }

    def "TimeTriggeredCmd 应 instanceof RoutineTimeCmd"() {
        expect:
        new RoutineTimeCmd.TimeTriggeredCmd("goal", SESSION, TRACE, NOW) instanceof RoutineTimeCmd
    }

    def "RoutineTimeCmd 的两个子类型应互相排他"() {
        expect:
        !(new RoutineTimeCmd.RegisterRoutineCmd("cron", SESSION, TRACE, NOW) instanceof RoutineTimeCmd.TimeTriggeredCmd)
        !(new RoutineTimeCmd.TimeTriggeredCmd("goal", SESSION, TRACE, NOW) instanceof RoutineTimeCmd.RegisterRoutineCmd)
    }

    def "RoutineTimeCmd 应 instanceof AgentCommand"() {
        expect:
        new RoutineTimeCmd.RegisterRoutineCmd("cron", SESSION, TRACE, NOW) instanceof AgentCommand
        new RoutineTimeCmd.TimeTriggeredCmd("goal", SESSION, TRACE, NOW) instanceof AgentCommand
    }
}
