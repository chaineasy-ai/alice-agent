package org.cland.alice.agent.command

import spock.lang.Specification
import spock.lang.Title

import java.time.Instant

/**
 * 验证 {@link AgentCommand} 的密封层级结构完整性。
 *
 * <p>确保：
 * <ul>
 *   <li>instanceof 检查能覆盖所有分支</li>
 *   <li>所有 21 个具体子类型可被正确分类</li>
 * </ul>
 */
@Title("AgentCommand 密封层级完整性（含 RoutineTimeCmd + SubAgentCmd）")
class AgentCommandSealedHierarchySpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE  = "trace-xyz"

    // ========================================================================
    // instanceof 分类 —— 确保所有 21 个具体子类型可被正确识别
    // ========================================================================

    def "instanceof 应正确分类所有 21 个子类型"() {
        given:
        def cmds = [
            // ExecutionCmd (2)
            new ExecutionCmd.AcquireGoalCmd("goal", SESSION, TRACE),
            new ExecutionCmd.ExecuteRawCmd("cmd", SESSION, TRACE),
            // CapabilityCmd (3)
            new CapabilityCmd.RegisterSkillCmd("skill", SESSION, TRACE),
            new CapabilityCmd.UpdateRulesCmd("rules", SESSION, TRACE),
            new CapabilityCmd.ReloadKernelCmd(SESSION, TRACE),
            // AlignmentCmd (1)
            new AlignmentCmd.SwitchModelCmd("model", SESSION, TRACE),
            // ControlCmd (6)
            new ControlCmd.ResetSessionCmd(SESSION, TRACE),
            new ControlCmd.FeedbackCmd("fb", SESSION, TRACE),
            new ControlCmd.InterruptCmd("exit", SESSION, TRACE),
            new ControlCmd.ClearContextCmd(SESSION, TRACE),
            new ControlCmd.ViewContextCmd(SESSION, TRACE),
            new ControlCmd.CompactContextCmd(SESSION, TRACE),
            // RoutineTimeCmd (2)
            new RoutineTimeCmd.RegisterRoutineCmd("cron", SESSION, TRACE),
            new RoutineTimeCmd.TimeTriggeredCmd("goal", SESSION, TRACE),
            // SubAgentCmd (7)
            new SpawnSubAgentCmd("goal", SESSION, TRACE),
            new ConnectSubAgentCmd("name", URI.create("http://localhost:9000/acp"), SESSION, TRACE),
            new ListSubAgentsCmd(SESSION, TRACE),
            new CancelSubAgentCmd("id1", SESSION, TRACE),
            new GetSubAgentResultsCmd("id1", SESSION, TRACE),
            new SendToSubAgentCmd("id1", "hello", SESSION, TRACE),
            new PromptSubAgentCmd("id1", "analyze", SESSION, TRACE),
        ]

        expect:
        // ExecutionCmd (2)
        cmds[0] instanceof ExecutionCmd.AcquireGoalCmd
        cmds[1] instanceof ExecutionCmd.ExecuteRawCmd
        // CapabilityCmd (3)
        cmds[2] instanceof CapabilityCmd.RegisterSkillCmd
        cmds[3] instanceof CapabilityCmd.UpdateRulesCmd
        cmds[4] instanceof CapabilityCmd.ReloadKernelCmd
        // AlignmentCmd (1)
        cmds[5] instanceof AlignmentCmd.SwitchModelCmd
        // ControlCmd (6)
        cmds[6] instanceof ControlCmd.ResetSessionCmd
        cmds[7] instanceof ControlCmd.FeedbackCmd
        cmds[8] instanceof ControlCmd.InterruptCmd
        cmds[9] instanceof ControlCmd.ClearContextCmd
        cmds[10] instanceof ControlCmd.ViewContextCmd
        cmds[11] instanceof ControlCmd.CompactContextCmd
        // RoutineTimeCmd (2)
        cmds[12] instanceof RoutineTimeCmd.RegisterRoutineCmd
        cmds[13] instanceof RoutineTimeCmd.TimeTriggeredCmd
        // SubAgentCmd (7)
        cmds[14] instanceof SpawnSubAgentCmd
        cmds[15] instanceof ConnectSubAgentCmd
        cmds[16] instanceof ListSubAgentsCmd
        cmds[17] instanceof CancelSubAgentCmd
        cmds[18] instanceof GetSubAgentResultsCmd
        cmds[19] instanceof SendToSubAgentCmd
        cmds[20] instanceof PromptSubAgentCmd

        // 父接口 instanceof 也成立
        cmds[0] instanceof ExecutionCmd
        cmds[2] instanceof CapabilityCmd
        cmds[5] instanceof AlignmentCmd
        cmds[6] instanceof ControlCmd
        cmds[12] instanceof RoutineTimeCmd
        cmds[14] instanceof SubAgentCmd
        cmds[15] instanceof SubAgentCmd
        cmds[16] instanceof SubAgentCmd
        cmds[17] instanceof SubAgentCmd
        cmds[18] instanceof SubAgentCmd
        cmds[19] instanceof SubAgentCmd
        cmds[20] instanceof SubAgentCmd

        // 顶层接口 instanceof 成立
        cmds.every { it instanceof AgentCommand }
    }

    def "AgentCommand 的 instanceof 分类（含 RoutineTimeCmd + SubAgentCmd）"() {
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
        new ControlCmd.ClearContextCmd(SESSION, TRACE) instanceof ControlCmd
        new ControlCmd.ViewContextCmd(SESSION, TRACE) instanceof ControlCmd
        new ControlCmd.CompactContextCmd(SESSION, TRACE) instanceof ControlCmd
        new RoutineTimeCmd.RegisterRoutineCmd("cron", SESSION, TRACE) instanceof RoutineTimeCmd
        new RoutineTimeCmd.TimeTriggeredCmd("goal", SESSION, TRACE) instanceof RoutineTimeCmd
        new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof SubAgentCmd
        new ConnectSubAgentCmd("n", URI.create("http://x"), SESSION, TRACE) instanceof SubAgentCmd
        new ListSubAgentsCmd(SESSION, TRACE) instanceof SubAgentCmd
        new CancelSubAgentCmd("i", SESSION, TRACE) instanceof SubAgentCmd
        new GetSubAgentResultsCmd("i", SESSION, TRACE) instanceof SubAgentCmd
        new SendToSubAgentCmd("i", "m", SESSION, TRACE) instanceof SubAgentCmd
        new PromptSubAgentCmd("i", "p", SESSION, TRACE) instanceof SubAgentCmd
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
        !(new RoutineTimeCmd.RegisterRoutineCmd("cron", SESSION, TRACE) instanceof ExecutionCmd)
        !(new RoutineTimeCmd.RegisterRoutineCmd("cron", SESSION, TRACE) instanceof ControlCmd)
        !(new ExecutionCmd.AcquireGoalCmd("g", SESSION, TRACE) instanceof RoutineTimeCmd)
        // SubAgentCmd 跨分支排他
        !(new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof ExecutionCmd)
        !(new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof CapabilityCmd)
        !(new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof AlignmentCmd)
        !(new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof ControlCmd)
        !(new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof RoutineTimeCmd)
        !(new ExecutionCmd.AcquireGoalCmd("g", SESSION, TRACE) instanceof SubAgentCmd)
        !(new CapabilityCmd.ReloadKernelCmd(SESSION, TRACE) instanceof SubAgentCmd)
        !(new AlignmentCmd.SwitchModelCmd("m", SESSION, TRACE) instanceof SubAgentCmd)
        !(new ControlCmd.InterruptCmd("i", SESSION, TRACE) instanceof SubAgentCmd)
        !(new RoutineTimeCmd.RegisterRoutineCmd("cron", SESSION, TRACE) instanceof SubAgentCmd)
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
        new ControlCmd.ClearContextCmd(SESSION, TRACE) instanceof AgentCommand
        new ControlCmd.ViewContextCmd(SESSION, TRACE) instanceof AgentCommand
        new ControlCmd.CompactContextCmd(SESSION, TRACE) instanceof AgentCommand
        new RoutineTimeCmd.RegisterRoutineCmd("cron", SESSION, TRACE) instanceof AgentCommand
        new RoutineTimeCmd.TimeTriggeredCmd("goal", SESSION, TRACE) instanceof AgentCommand
        new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof AgentCommand
        new ConnectSubAgentCmd("n", URI.create("http://x"), SESSION, TRACE) instanceof AgentCommand
        new ListSubAgentsCmd(SESSION, TRACE) instanceof AgentCommand
        new CancelSubAgentCmd("i", SESSION, TRACE) instanceof AgentCommand
        new GetSubAgentResultsCmd("i", SESSION, TRACE) instanceof AgentCommand
        new SendToSubAgentCmd("i", "m", SESSION, TRACE) instanceof AgentCommand
        new PromptSubAgentCmd("i", "p", SESSION, TRACE) instanceof AgentCommand
    }
}
