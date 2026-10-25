/*
 * #748 — CLI「打印动作语气、实际没做」的 9 处必须改成诚实回显。
 *
 * 判据（票内 4 条）：
 *   ① 入口要么真做、要么把话改成"未接线/不支持"，**不许动作语气**
 *   ② 每条配"捕获副作用"用例
 *   ③ 未实现的：断言**零副作用** + 回显含 not wired / unsupported
 *   ④ 变异：改回"只打印" ⇒ 对应用例必红
 *
 * 对照组（关键）：`SwitchModelCmd` / `SpawnSubAgentCmd` 是**真做**的两条（#747 交付），
 * 它们必须有副作用 —— 否则"改话"这条路会退化成"全都标未接线"的假绿。
 */
package org.cland.alice.facade.cli

import org.cland.alice.agent.proto.AlignmentCmd
import org.cland.alice.agent.proto.AgentCommand
import org.cland.alice.agent.proto.CapabilityCmd
import org.cland.alice.agent.proto.ControlCmd
import org.cland.alice.agent.proto.ExecutionCmd
import org.cland.alice.agent.proto.RoutineTimeCmd
import org.cland.alice.agent.proto.SpawnSubAgentCmd
import org.cland.alice.core.agent.Agent
import org.cland.alice.core.agent.AgentConfig
import org.cland.alice.model.Model
import org.cland.alice.model.ModelProvider
import spock.lang.Specification
import spock.lang.Title
import spock.lang.Unroll

@Title("CLI 未接线命令必须诚实回显（#748）")
class AliceCliNotWiredSpec extends Specification {

    static final String SESSION = "sess-748"
    static final String TRACE = "trace-748"

    /** 动作语气词：出现即说明"界面在宣称做了事"。 */
    static final List<String> ACTION_WORDS = [
        "Executing", "Registering", "Updating", "Reloading", "Resetting", "Resuming",
        "Clearing context", "上下文已清除",
    ]

    private static String captureOut(Closure<?> body) {
        def original = System.out
        def buffer = new ByteArrayOutputStream()
        System.setOut(new PrintStream(buffer, true, "UTF-8"))
        try {
            body.call()
        } finally {
            System.setOut(original)
        }
        return buffer.toString("UTF-8")
    }

    @Unroll
    def "未接线命令 #cmdName 回显必须含 not wired + 正确入口，且不含动作语气"() {
        given: "真 Agent（本项目无 MockAgent 依赖）+ 一个记录 spawn 的 runner，用来证零副作用"
        def parent = new Agent()
        def before = parent.currentModelId()
        def spawned = []
        AliceCliLauncher.testSubAgentRunner = { String id, String goal, AgentConfig cfg ->
            spawned << goal
            return "done"
        }

        when:
        def out = captureOut {
            AliceCliLauncher.dispatchCommand(cmd as AgentCommand, parent)
        }

        then: "③ 含关键词；① 不含动作语气；并给出**去哪用**"
        out.contains("[not wired]")
        out.contains("未产生任何副作用")
        out.contains("请改用")
        ACTION_WORDS.every { !out.contains(it) }

        and: "③ 零副作用：有效模型未变、没有 spawn 任何子 Agent（不是没抛异常）"
        parent.currentModelId() == before
        spawned.isEmpty()

        cleanup:
        AliceCliLauncher.testSubAgentRunner = null

        where:
        cmdName                | cmd
        "AcquireGoalCmd"       | new ExecutionCmd.AcquireGoalCmd("写一个冒泡排序", SESSION, TRACE)
        "ExecuteRawCmd"        | new ExecutionCmd.ExecuteRawCmd("ls -la", SESSION, TRACE)
        "RegisterSkillCmd"     | new CapabilityCmd.RegisterSkillCmd("skill://demo", SESSION, TRACE)
        "UpdateRulesCmd"       | new CapabilityCmd.UpdateRulesCmd("rules://demo", SESSION, TRACE)
        "ReloadKernelCmd"      | new CapabilityCmd.ReloadKernelCmd(SESSION, TRACE)
        "ResetSessionCmd"      | new ControlCmd.ResetSessionCmd(SESSION, TRACE)
        "ClearContextCmd"      | new ControlCmd.ClearContextCmd(SESSION, TRACE)
        "RegisterRoutineCmd"   | new RoutineTimeCmd.RegisterRoutineCmd("0 */5 * * * ?", SESSION, TRACE)
        "ResumeSessionCmd"     | new ControlCmd.ResumeSessionCmd(SESSION, TRACE, null)
    }

    def "① 回显必须指向真实存在的入口（不许指一个不存在的子命令）"() {
        given: "本仓真实存在的子命令（picocli CommandParser 里声明的）"
        def realSubcommands = ["run", "chat", "tools", "resume", "config", "sub-agent"]

        when: "把 8 个未接线命令的回显都收集起来"
        def outs = [
            new ExecutionCmd.AcquireGoalCmd("t", SESSION, TRACE),
            new ExecutionCmd.ExecuteRawCmd("c", SESSION, TRACE),
            new CapabilityCmd.RegisterSkillCmd("s", SESSION, TRACE),
            new CapabilityCmd.UpdateRulesCmd("r", SESSION, TRACE),
            new CapabilityCmd.ReloadKernelCmd(SESSION, TRACE),
            new ControlCmd.ResetSessionCmd(SESSION, TRACE),
            new ControlCmd.ClearContextCmd(SESSION, TRACE),
            new RoutineTimeCmd.RegisterRoutineCmd("0 */5 * * * ?", SESSION, TRACE),
            new ControlCmd.ResumeSessionCmd(SESSION, TRACE, null),
        ].collect { c -> captureOut { AliceCliLauncher.dispatchCommand(c as AgentCommand, null) } }

        then: "凡提到 `alice <sub>` 的，sub 必须真实存在（否则是换一种方式骗人）"
        outs.every { out ->
            def mentioned = (out =~ /alice ([a-z-]+)/).collect { it[1] }
            mentioned.every { realSubcommands.contains(it) }
        }
    }

    def "未接线的 ExecutionCmd 不得先制造正在做事的印象（#748 行为形态扫描补漏）"() {
        given: "交互式会话的渲染入口（此前会先打「Agent 思考中…」再交给分发器）"
        def session = new org.cland.alice.facade.cli.chat.JLineChatSession()
        def dispatch = session.class.getDeclaredMethod("dispatchAndRender", AgentCommand)
        dispatch.setAccessible(true)

        when:
        def out = captureOut {
            dispatch.invoke(session, new ExecutionCmd.AcquireGoalCmd("写一个冒泡排序", SESSION, TRACE))
        }

        then: "只有一条诚实回显：不许出现思考中这类进度假信号，也不许出现动作语气"
        out.contains("[not wired]")
        !out.contains("思考中")
        ACTION_WORDS.every { !out.contains(it) }

        cleanup:
        session.close()
    }

    def "对照组：SwitchModelCmd 是真做，必须有副作用（否则改话会退化成全都标未接线）"() {
        given:
        def provider = ModelProvider.getInstance()
        provider.registerBuiltinModels()
        if (provider.getModel("switch-target-748") == null) {
            provider.registerModel(
                Model.builder()
                    .modelId("switch-target-748")
                    .supplierName("fake748")
                    .capability(Model.Capability.ALL)
                    .pricing(new Model.Pricing(0.0d, 0.0d))
                    .build())
        }
        def parent = new Agent()

        when:
        def out = captureOut {
            AliceCliLauncher.dispatchCommand(
                new AlignmentCmd.SwitchModelCmd("switch-target-748", SESSION, TRACE), parent)
        }

        then: "副作用真的发生了（不是打印）"
        parent.currentModelId() == "switch-target-748"
        out.contains("模型切换至: switch-target-748")
        !out.contains("[not wired]")
    }

    def "对照组：SpawnSubAgentCmd 是真做，必须真的 spawn（不是打印）"() {
        given:
        def captured = []
        AliceCliLauncher.testSubAgentRunner = { String id, String goal, AgentConfig cfg ->
            captured << goal
            return "done"
        }
        def parent = new Agent()

        when:
        def out = captureOut {
            AliceCliLauncher.dispatchCommand(new SpawnSubAgentCmd("child goal", null, SESSION, TRACE), parent)
        }
        long deadline = System.currentTimeMillis() + 5_000
        while (captured.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        then:
        captured == ["child goal"]
        !out.contains("[not wired]")

        cleanup:
        AliceCliLauncher.testSubAgentRunner = null
    }
}
