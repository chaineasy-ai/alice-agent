package org.cland.alice.agent.command

import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title

/**
 * 测试 {@link AgentCommand#parse(String, String, String)} 工厂方法，
 * 验证自然语言与斜杠命令到各密封指令类型的映射。
 */
@Title("AgentCommand.parse() 指令解析")
class AgentCommandParseSpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE  = "trace-abc"

    // ========================================================================
    // ExecutionCmd（任务驱动）
    // ========================================================================

    def "自然语言输入应转换为 AcquireGoalCmd"() {
        when:
        def cmd = AgentCommand.parse("hello world", SESSION, TRACE)

        then:
        cmd instanceof ExecutionCmd.AcquireGoalCmd
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
        (cmd as ExecutionCmd.AcquireGoalCmd).goal() == "hello world"
        (cmd as ExecutionCmd.AcquireGoalCmd).task() == "hello world"
    }

    def "空输入应返回 null"() {
        expect:
        AgentCommand.parse(null, SESSION, TRACE) == null
        AgentCommand.parse("", SESSION, TRACE) == null
        AgentCommand.parse("   ", SESSION, TRACE) == null
    }

    def "/run 应转换为 AcquireGoalCmd"() {
        when:
        def cmd = AgentCommand.parse("/run 写一首诗", SESSION, TRACE)

        then:
        cmd instanceof ExecutionCmd.AcquireGoalCmd
        (cmd as ExecutionCmd.AcquireGoalCmd).goal() == "写一首诗"
    }

    def "/run 无参数时 goal 应为空字符串占位"() {
        when:
        def cmd = AgentCommand.parse("/run", SESSION, TRACE)

        then:
        cmd instanceof ExecutionCmd.AcquireGoalCmd
        (cmd as ExecutionCmd.AcquireGoalCmd).goal() == "(empty /run)"
    }

    def "/exec 应转换为 ExecuteRawCmd"() {
        when:
        def cmd = AgentCommand.parse("/exec nvidia-smi", SESSION, TRACE)

        then:
        cmd instanceof ExecutionCmd.ExecuteRawCmd
        (cmd as ExecutionCmd.ExecuteRawCmd).command() == "nvidia-smi"
        (cmd as ExecutionCmd.ExecuteRawCmd).task() == "nvidia-smi"
    }

    def "/exec 无参数应有默认回退命令"() {
        when:
        def cmd = AgentCommand.parse("/exec", SESSION, TRACE)

        then:
        cmd instanceof ExecutionCmd.ExecuteRawCmd
        (cmd as ExecutionCmd.ExecuteRawCmd).command() != ""
        (cmd as ExecutionCmd.ExecuteRawCmd).command().contains("echo")
    }

    // ========================================================================
    // CapabilityCmd（能力装载）
    // ========================================================================

    def "/skill 应转换为 RegisterSkillCmd"() {
        when:
        def cmd = AgentCommand.parse("/skill my-tool", SESSION, TRACE)

        then:
        cmd instanceof CapabilityCmd.RegisterSkillCmd
        (cmd as CapabilityCmd.RegisterSkillCmd).skillRef() == "my-tool"
        (cmd as CapabilityCmd.RegisterSkillCmd).resource() == "my-tool"
    }

    def "/rules 应转换为 UpdateRulesCmd"() {
        when:
        def cmd = AgentCommand.parse("/rules rules/my.prompt", SESSION, TRACE)

        then:
        cmd instanceof CapabilityCmd.UpdateRulesCmd
        (cmd as CapabilityCmd.UpdateRulesCmd).rulesRef() == "rules/my.prompt"
        (cmd as CapabilityCmd.UpdateRulesCmd).resource() == "rules/my.prompt"
    }

    def "/reload 应转换为 ReloadKernelCmd"() {
        when:
        def cmd = AgentCommand.parse("/reload", SESSION, TRACE)

        then:
        cmd instanceof CapabilityCmd.ReloadKernelCmd
        (cmd as CapabilityCmd.ReloadKernelCmd).resource() == "*"
    }

    def "/reload 忽略额外参数"() {
        when:
        def cmd = AgentCommand.parse("/reload all", SESSION, TRACE)

        then:
        cmd instanceof CapabilityCmd.ReloadKernelCmd
        (cmd as CapabilityCmd.ReloadKernelCmd).resource() == "*"
    }

    // ========================================================================
    // AlignmentCmd（运行配置）
    // ========================================================================

    def "/model 应转换为 SwitchModelCmd"() {
        when:
        def cmd = AgentCommand.parse("/model claude-3.5", SESSION, TRACE)

        then:
        cmd instanceof AlignmentCmd.SwitchModelCmd
        (cmd as AlignmentCmd.SwitchModelCmd).modelId() == "claude-3.5"
        (cmd as AlignmentCmd.SwitchModelCmd).value() == "claude-3.5"
    }

    def "/model 无参数时应使用默认模型"() {
        when:
        def cmd = AgentCommand.parse("/model", SESSION, TRACE)

        then:
        cmd instanceof AlignmentCmd.SwitchModelCmd
        (cmd as AlignmentCmd.SwitchModelCmd).modelId() == "gpt-4o"
    }

    // ========================================================================
    // ControlCmd（控制与反馈）
    // ========================================================================

    def "/new 应转换为 ResetSessionCmd"() {
        when:
        def cmd = AgentCommand.parse("/new", SESSION, TRACE)

        then:
        cmd instanceof ControlCmd.ResetSessionCmd
        (cmd as ControlCmd.ResetSessionCmd).reason() == "reset-session"
        (cmd as ControlCmd.ResetSessionCmd).sessionId() == SESSION
    }

    def "/feedback 应转换为 FeedbackCmd"() {
        when:
        def cmd = AgentCommand.parse("/feedback 做得不错但太长了", SESSION, TRACE)

        then:
        cmd instanceof ControlCmd.FeedbackCmd
        (cmd as ControlCmd.FeedbackCmd).message() == "做得不错但太长了"
        (cmd as ControlCmd.FeedbackCmd).reason().contains("human-feedback")
    }

    def "/exit 应转换为 InterruptCmd"() {
        when:
        def cmd = AgentCommand.parse("/exit", SESSION, TRACE)

        then:
        cmd instanceof ControlCmd.InterruptCmd
        (cmd as ControlCmd.InterruptCmd).cause() == "user-exit"
        (cmd as ControlCmd.InterruptCmd).reason().contains("user-exit")
    }

    // ========================================================================
    // 未知命令
    // ========================================================================

    def "未知斜杠命令应返回 null"() {
        expect:
        AgentCommand.parse("/unknown", SESSION, TRACE) == null
        AgentCommand.parse("/foo bar", SESSION, TRACE) == null
        AgentCommand.parse("/", SESSION, TRACE) == null
    }

    // ========================================================================
    // 时间戳
    // ========================================================================

    def "所有指令应携带非空时间戳"() {
        when:
        def cmds = [
            AgentCommand.parse("自然语言", SESSION, TRACE),
            AgentCommand.parse("/run task", SESSION, TRACE),
            AgentCommand.parse("/exec ls", SESSION, TRACE),
            AgentCommand.parse("/skill x", SESSION, TRACE),
            AgentCommand.parse("/rules x", SESSION, TRACE),
            AgentCommand.parse("/reload", SESSION, TRACE),
            AgentCommand.parse("/model x", SESSION, TRACE),
            AgentCommand.parse("/new", SESSION, TRACE),
            AgentCommand.parse("/exit", SESSION, TRACE),
        ]

        then:
        cmds.every { it.timestamp() != null }
    }

    // ========================================================================
    // 输出/显示
    // ========================================================================

    def "AcquireGoalCmd toString 应包含 goal"() {
        when:
        def cmd = new ExecutionCmd.AcquireGoalCmd("test", SESSION, TRACE)

        then:
        cmd.toString().contains("test")
        cmd.toString().contains("AcquireGoalCmd")
    }

    def "SwitchModelCmd toString 应包含 modelId"() {
        when:
        def cmd = new AlignmentCmd.SwitchModelCmd("gpt-4", SESSION, TRACE)

        then:
        cmd.toString().contains("gpt-4")
        cmd.toString().contains("SwitchModelCmd")
    }
}
