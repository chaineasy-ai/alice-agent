package org.cland.alice.agent.command

import spock.lang.Specification
import spock.lang.Title

/**
 * 验证 {@link AgentCommand} 的密封层级结构完整性。
 *
 * <p>确保：
 * <ul>
 *   <li>instanceof 检查能覆盖所有分支</li>
 *   <li>所有 9 个具体子类型可被正确分类</li>
 * </ul>
 */
@Title("AgentCommand 密封层级完整性")
class AgentCommandSealedHierarchySpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE  = "trace-xyz"

    // ========================================================================
    // instanceof 分类 —— 确保所有 9 个具体子类型可被正确识别
    // ========================================================================

    def "instanceof 应正确分类所有 9 个子类型"() {
        given:
        def cmds = [
            new ExecutionCmd.AcquireGoalCmd("goal", SESSION, TRACE),
            new ExecutionCmd.ExecuteRawCmd("cmd", SESSION, TRACE),
            new CapabilityCmd.RegisterSkillCmd("skill", SESSION, TRACE),
            new CapabilityCmd.UpdateRulesCmd("rules", SESSION, TRACE),
            new CapabilityCmd.ReloadKernelCmd(SESSION, TRACE),
            new AlignmentCmd.SwitchModelCmd("model", SESSION, TRACE),
            new ControlCmd.ResetSessionCmd(SESSION, TRACE),
            new ControlCmd.FeedbackCmd("fb", SESSION, TRACE),
            new ControlCmd.InterruptCmd("exit", SESSION, TRACE),
        ]

        expect:
        cmds[0] instanceof ExecutionCmd.AcquireGoalCmd
        cmds[1] instanceof ExecutionCmd.ExecuteRawCmd
        cmds[2] instanceof CapabilityCmd.RegisterSkillCmd
        cmds[3] instanceof CapabilityCmd.UpdateRulesCmd
        cmds[4] instanceof CapabilityCmd.ReloadKernelCmd
        cmds[5] instanceof AlignmentCmd.SwitchModelCmd
        cmds[6] instanceof ControlCmd.ResetSessionCmd
        cmds[7] instanceof ControlCmd.FeedbackCmd
        cmds[8] instanceof ControlCmd.InterruptCmd

        // 父接口 instanceof 也成立
        cmds[0] instanceof ExecutionCmd
        cmds[2] instanceof CapabilityCmd
        cmds[5] instanceof AlignmentCmd
        cmds[6] instanceof ControlCmd

        // 顶层接口 instanceof 成立
        cmds.every { it instanceof AgentCommand }
    }

    def "AgentCommand 的 instanceof 分类"() {
        expect:
        new ExecutionCmd.AcquireGoalCmd("g", SESSION, TRACE) instanceof ExecutionCmd
        new ExecutionCmd.ExecuteRawCmd("c", SESSION, TRACE) instanceof ExecutionCmd
        new CapabilityCmd.RegisterSkillCmd("s", SESSION, TRACE) instanceof CapabilityCmd
        new CapabilityCmd.UpdateRulesCmd("r", SESSION, TRACE) instanceof CapabilityCmd
        new CapabilityCmd.ReloadKernelCmd(SESSION, TRACE) instanceof CapabilityCmd
        new AlignmentCmd.SwitchModelCmd("m", SESSION, TRACE) instanceof AlignmentCmd
        new ControlCmd.ResetSessionCmd(SESSION, TRACE) instanceof ControlCmd
        new ControlCmd.FeedbackCmd("f", SESSION, TRACE) instanceof ControlCmd
        new ControlCmd.InterruptCmd("i", SESSION, TRACE) instanceof ControlCmd
    }

    // ========================================================================
    // 密封约束验证（通过编译时约束 + 运行时 instanceof）
    // ========================================================================

    def "ExecutionCmd 的两个子类型应互相排他"() {
        expect:
        !(new ExecutionCmd.AcquireGoalCmd("g", SESSION, TRACE) instanceof ExecutionCmd.ExecuteRawCmd)
        !(new ExecutionCmd.ExecuteRawCmd("c", SESSION, TRACE) instanceof ExecutionCmd.AcquireGoalCmd)
    }

    def "不同分支的类型不应跨 instanceof 匹配"() {
        expect:
        !(new ExecutionCmd.AcquireGoalCmd("g", SESSION, TRACE) instanceof CapabilityCmd)
        !(new CapabilityCmd.ReloadKernelCmd(SESSION, TRACE) instanceof AlignmentCmd)
        !(new AlignmentCmd.SwitchModelCmd("m", SESSION, TRACE) instanceof ControlCmd)
        !(new ControlCmd.InterruptCmd("i", SESSION, TRACE) instanceof ExecutionCmd)
    }

    // ========================================================================
    // AgentCommand 层级关系
    // ========================================================================

    def "所有具体类型都实现了 AgentCommand"() {
        expect:
        new ExecutionCmd.AcquireGoalCmd("g", SESSION, TRACE) instanceof AgentCommand
        new ExecutionCmd.ExecuteRawCmd("c", SESSION, TRACE) instanceof AgentCommand
        new CapabilityCmd.RegisterSkillCmd("s", SESSION, TRACE) instanceof AgentCommand
        new CapabilityCmd.UpdateRulesCmd("r", SESSION, TRACE) instanceof AgentCommand
        new CapabilityCmd.ReloadKernelCmd(SESSION, TRACE) instanceof AgentCommand
        new AlignmentCmd.SwitchModelCmd("m", SESSION, TRACE) instanceof AgentCommand
        new ControlCmd.ResetSessionCmd(SESSION, TRACE) instanceof AgentCommand
        new ControlCmd.FeedbackCmd("f", SESSION, TRACE) instanceof AgentCommand
        new ControlCmd.InterruptCmd("i", SESSION, TRACE) instanceof AgentCommand
    }
}
