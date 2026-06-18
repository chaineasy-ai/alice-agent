/*
 * CommandDispatchLoopSpec — 验证 bootstrap → facade → cmd 完整分发链路
 *
 * 测试目标：确认从 FacadeSelector.launch() 到 AliceCliLauncher.dispatchCommand()
 * 的完整下行链路通畅。
 *
 * 不涉及真实的 LLM 调用、TUI 屏幕或文件系统 IO。
 * 仅验证 dispatch switch 表达式能接收所有 AgentCommand 子类型并正确路由。
 *
 * 注意：alice-bootstrap 使用 SPI 发现 facade 模块。以下测试依赖于
 *       alice-facade-cmd 在测试 classpath 上（通过 testImplementation 引入）。
 */
package org.cland.alice.agent

import org.cland.alice.agent.command.*
import spock.lang.Specification
import spock.lang.Title

@Title("bootstrap → facade → cmd 完整分发链路")
class CommandDispatchLoopSpec extends Specification {

    static final String SESSION = "test-session"
    static final String TRACE   = "test-trace"

    // ================================================================
    // 1. FacadeSelector.launch()  SPI 路由验证
    // ================================================================

    def "FacadeSelector.launch 无参数时通过 CLI facade 返回 EXIT_PARAM_ERROR"() {
        expect:
        FacadeSelector.launch([] as String[]) == AliceApp.EXIT_PARAM_ERROR
    }

    def "FacadeSelector.launch 使用 run 子命令传入 CLI 层"() {
        expect:
        FacadeSelector.launch(["run", "测试任务"] as String[]) == AliceApp.EXIT_SUCCESS
    }

    def "FacadeSelector.launch 使用 --tui 触发 TUI facade 发现"() {
        expect:
        FacadeSelector.launch(["--tui"] as String[]) == AliceApp.EXIT_SUCCESS
    }

    def "FacadeSelector.launch 使用 --cli 触发 CLI facade"() {
        expect:
        FacadeSelector.launch(["--cli"] as String[]) == AliceApp.EXIT_PARAM_ERROR
    }

    // ================================================================
    // 3. AliceCliLauncher.dispatchCommand() — CLI 侧所有指令分发
    // ================================================================

    def "CLI dispatchCommand /run 分发到 AcquireGoalCmd"() {
        when:
        def cmd = AgentCommand.parse("/run 写测试", SESSION, TRACE)

        then:
        cmd instanceof ExecutionCmd.AcquireGoalCmd
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/run 写测试") == org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand /exec 分发到 ExecuteRawCmd"() {
        when:
        def cmd = AgentCommand.parse("/exec ls", SESSION, TRACE)

        then:
        cmd instanceof ExecutionCmd.ExecuteRawCmd
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/exec ls") == org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand /skill 分发到 RegisterSkillCmd"() {
        when:
        def cmd = AgentCommand.parse("/skill my-tool", SESSION, TRACE)

        then:
        cmd instanceof CapabilityCmd.RegisterSkillCmd
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/skill my-tool") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand /rules 分发到 UpdateRulesCmd"() {
        when:
        def cmd = AgentCommand.parse("/rules my.prompt", SESSION, TRACE)

        then:
        cmd instanceof CapabilityCmd.UpdateRulesCmd
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/rules my.prompt") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand /reload 分发到 ReloadKernelCmd"() {
        when:
        def cmd = AgentCommand.parse("/reload", SESSION, TRACE)

        then:
        cmd instanceof CapabilityCmd.ReloadKernelCmd
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/reload") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand /model 分发到 SwitchModelCmd"() {
        when:
        def cmd = AgentCommand.parse("/model gpt-4o", SESSION, TRACE)

        then:
        cmd instanceof AlignmentCmd.SwitchModelCmd
        (cmd as AlignmentCmd.SwitchModelCmd).modelId() == "gpt-4o"
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/model gpt-4o") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand /new 分发到 ResetSessionCmd"() {
        when:
        def cmd = AgentCommand.parse("/new", SESSION, TRACE)

        then:
        cmd instanceof ControlCmd.ResetSessionCmd
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/new") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand /feedback 分发到 FeedbackCmd"() {
        when:
        def cmd = AgentCommand.parse("/feedback 请简化输出", SESSION, TRACE)

        then:
        cmd instanceof ControlCmd.FeedbackCmd
        (cmd as ControlCmd.FeedbackCmd).message() == "请简化输出"
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/feedback 请简化输出") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand /exit 分发到 InterruptCmd"() {
        when:
        def cmd = AgentCommand.parse("/exit", SESSION, TRACE)

        then:
        cmd instanceof ControlCmd.InterruptCmd
        (cmd as ControlCmd.InterruptCmd).cause() == "user-exit"
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/exit") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand /clear 分发到 ClearContextCmd"() {
        when:
        def cmd = AgentCommand.parse("/clear", SESSION, TRACE)

        then:
        cmd instanceof ControlCmd.ClearContextCmd
        (cmd as ControlCmd.ClearContextCmd).reason() == "clear-context"
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/clear") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand /context 分发到 ViewContextCmd"() {
        when:
        def cmd = AgentCommand.parse("/context", SESSION, TRACE)

        then:
        cmd instanceof ControlCmd.ViewContextCmd
        (cmd as ControlCmd.ViewContextCmd).reason() == "view-context"
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/context") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand /compact 分发到 CompactContextCmd"() {
        when:
        def cmd = AgentCommand.parse("/compact", SESSION, TRACE)

        then:
        cmd instanceof ControlCmd.CompactContextCmd
        (cmd as ControlCmd.CompactContextCmd).reason() == "compact-context"
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/compact") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand 自然语言默认当作 AcquireGoalCmd"() {
        when:
        def cmd = AgentCommand.parse("帮我查天气", SESSION, TRACE)

        then:
        cmd instanceof ExecutionCmd.AcquireGoalCmd
        (cmd as ExecutionCmd.AcquireGoalCmd).goal() == "帮我查天气"
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("帮我查天气") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand 未知命令返回 EXIT_PARAM_ERROR"() {
        expect:
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/unknown") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_PARAM_ERROR
    }

    // ================================================================
    // 4. 完整链路：自然语言 → AgentCommand → dispatch → exit code
    // ================================================================

    def "CLI dispatchCommand /feedback with empty message 返回 EXIT_SUCCESS"() {
        expect:
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/feedback") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    // ================================================================
    // 5. RoutineTimeCmd CLI 分发验证
    // ================================================================

    def "CLI dispatchCommand /routine 分发到 RegisterRoutineCmd"() {
        when:
        def cmd = AgentCommand.parse("/routine 0 */5 * * * ?", SESSION, TRACE)

        then:
        cmd instanceof RoutineTimeCmd.RegisterRoutineCmd
        (cmd as RoutineTimeCmd.RegisterRoutineCmd).cronExpression() == "0 */5 * * * ?"
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/routine 0 */5 * * * ?") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "CLI dispatchCommand /routine without args 分发到 RegisterRoutineCmd"() {
        when:
        def cmd = AgentCommand.parse("/routine", SESSION, TRACE)

        then:
        cmd instanceof RoutineTimeCmd.RegisterRoutineCmd
        (cmd as RoutineTimeCmd.RegisterRoutineCmd).cronExpression() == ""
        org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand("/routine") ==
            org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
    }

    def "完整链路测试 — 全指令集逐一验证 dispatch 不抛出异常"() {
        given: "所有 AgentCommand 输入样本"
        def inputs = [
            "/run 帮我查天气",
            "/exec ls -la",
            "/skill my-tool",
            "/rules custom-rule",
            "/reload",
            "/model gpt-4o",
            "/new",
            "/feedback 很好",
            "/exit",
            "/clear",
            "/context",
            "/compact",
            "/routine 0 */5 * * * ?",
        ]

        expect: "每条指令都能被 dispatch 且不抛出异常"
        for (def input : inputs) {
            def cmd = AgentCommand.parse(input, SESSION, TRACE)
            cmd != null
            def exitCode = org.cland.alice.facade.cmd.AliceCliLauncher.dispatchCommand(input)
            assert exitCode == org.cland.alice.facade.cmd.AliceCliLauncher.EXIT_SUCCESS
        }
    }
}
