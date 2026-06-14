package org.cland.alice.agent.command

import spock.lang.Specification
import spock.lang.Title

/**
 * 验证 {@link SubAgentCmd} 密封层级结构完整性。
 *
 * <p>确保：
 * <ul>
 *   <li>instanceof 检查能覆盖所有 7 个 SubAgentCmd 子类型</li>
 *   <li>SubAgentCmd 被正确识别为 AgentCommand 的分支</li>
 *   <li>跨分支 instanceof 不会误匹配</li>
 * </ul>
 */
@Title("SubAgentCmd 密封层级完整性")
class SubAgentCmdSealedHierarchySpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE  = "trace-xyz"

    // ========================================================================
    // instanceof 分类 —— 确保所有 7 个 SubAgentCmd 子类型可被正确识别
    // ========================================================================

    def "instanceof 应正确分类所有 7 个 SubAgentCmd 子类型"() {
        given:
        def cmds = [
            new SpawnSubAgentCmd("goal", SESSION, TRACE),
            new ConnectSubAgentCmd("name", URI.create("http://localhost:9000/acp"), SESSION, TRACE),
            new ListSubAgentsCmd(SESSION, TRACE),
            new CancelSubAgentCmd("id1", SESSION, TRACE),
            new GetSubAgentResultsCmd("id1", SESSION, TRACE),
            new SendToSubAgentCmd("id1", "hello", SESSION, TRACE),
            new PromptSubAgentCmd("id1", "analyze", SESSION, TRACE),
        ]

        expect:
        cmds[0] instanceof SpawnSubAgentCmd
        cmds[1] instanceof ConnectSubAgentCmd
        cmds[2] instanceof ListSubAgentsCmd
        cmds[3] instanceof CancelSubAgentCmd
        cmds[4] instanceof GetSubAgentResultsCmd
        cmds[5] instanceof SendToSubAgentCmd
        cmds[6] instanceof PromptSubAgentCmd

        // 父接口 instanceof 也成立
        cmds.every { it instanceof SubAgentCmd }

        // 顶层接口 instanceof 成立
        cmds.every { it instanceof AgentCommand }
    }

    def "SubAgentCmd 的 instanceof 分类（含 AgentCommand 层级）"() {
        expect:
        new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof SubAgentCmd
        new ConnectSubAgentCmd("n", URI.create("http://x"), SESSION, TRACE) instanceof SubAgentCmd
        new ListSubAgentsCmd(SESSION, TRACE) instanceof SubAgentCmd
        new CancelSubAgentCmd("i", SESSION, TRACE) instanceof SubAgentCmd
        new GetSubAgentResultsCmd("i", SESSION, TRACE) instanceof SubAgentCmd
        new SendToSubAgentCmd("i", "m", SESSION, TRACE) instanceof SubAgentCmd
        new PromptSubAgentCmd("i", "p", SESSION, TRACE) instanceof SubAgentCmd

        // 所有都是 AgentCommand
        new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof AgentCommand
        new ConnectSubAgentCmd("n", URI.create("http://x"), SESSION, TRACE) instanceof AgentCommand
        new ListSubAgentsCmd(SESSION, TRACE) instanceof AgentCommand
        new CancelSubAgentCmd("i", SESSION, TRACE) instanceof AgentCommand
        new GetSubAgentResultsCmd("i", SESSION, TRACE) instanceof AgentCommand
        new SendToSubAgentCmd("i", "m", SESSION, TRACE) instanceof AgentCommand
        new PromptSubAgentCmd("i", "p", SESSION, TRACE) instanceof AgentCommand
    }

    // ========================================================================
    // 跨分支排他性
    // ========================================================================

    def "SubAgentCmd 子类型之间不应跨 instanceof 匹配"() {
        expect:
        !(new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof ConnectSubAgentCmd)
        !(new ConnectSubAgentCmd("n", URI.create("http://x"), SESSION, TRACE) instanceof SpawnSubAgentCmd)
        !(new CancelSubAgentCmd("i", SESSION, TRACE) instanceof ListSubAgentsCmd)
        !(new GetSubAgentResultsCmd("i", SESSION, TRACE) instanceof SendToSubAgentCmd)
        !(new SendToSubAgentCmd("i", "m", SESSION, TRACE) instanceof PromptSubAgentCmd)
        !(new PromptSubAgentCmd("i", "p", SESSION, TRACE) instanceof CancelSubAgentCmd)
        !(new ListSubAgentsCmd(SESSION, TRACE) instanceof SpawnSubAgentCmd)
    }

    def "SubAgentCmd 子类型不应跨分支匹配其他 AgentCommand 分支"() {
        expect:
        // SubAgentCmd 不是其他分支
        !(new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof ExecutionCmd)
        !(new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof CapabilityCmd)
        !(new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof AlignmentCmd)
        !(new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof ControlCmd)
        !(new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof RoutineTimeCmd)

        // 其他分支不是 SubAgentCmd
        !(new ExecutionCmd.AcquireGoalCmd("g", SESSION, TRACE) instanceof SubAgentCmd)
        !(new CapabilityCmd.ReloadKernelCmd(SESSION, TRACE) instanceof SubAgentCmd)
        !(new AlignmentCmd.SwitchModelCmd("m", SESSION, TRACE) instanceof SubAgentCmd)
        !(new ControlCmd.InterruptCmd("i", SESSION, TRACE) instanceof SubAgentCmd)
        !(new RoutineTimeCmd.RegisterRoutineCmd("cron", SESSION, TRACE) instanceof SubAgentCmd)
    }

    // ========================================================================
    // 密封约束
    // ========================================================================

    def "所有 SubAgentCmd 子类型都实现了 AgentCommand"() {
        expect:
        new SpawnSubAgentCmd("g", SESSION, TRACE) instanceof AgentCommand
        new ConnectSubAgentCmd("n", URI.create("http://x"), SESSION, TRACE) instanceof AgentCommand
        new ListSubAgentsCmd(SESSION, TRACE) instanceof AgentCommand
        new CancelSubAgentCmd("i", SESSION, TRACE) instanceof AgentCommand
        new GetSubAgentResultsCmd("i", SESSION, TRACE) instanceof AgentCommand
        new SendToSubAgentCmd("i", "m", SESSION, TRACE) instanceof AgentCommand
        new PromptSubAgentCmd("i", "p", SESSION, TRACE) instanceof AgentCommand
    }
}
