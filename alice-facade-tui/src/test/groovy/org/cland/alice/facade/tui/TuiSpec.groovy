/*
 * Tests for alice-facade-tui module.
 */
package org.cland.alice.facade.tui

import spock.lang.Specification

import org.cland.alice.facade.tui.state.TuiState
import org.cland.alice.facade.tui.command.SlashCommand
import org.cland.alice.facade.tui.command.CommandHandler
import org.cland.alice.facade.tui.bridge.TuiEvent
import org.cland.alice.facade.tui.bridge.EventBridge
import org.cland.alice.agent.proto.*

class TuiSpec extends Specification {

    // ========== TuiState 测试 ==========

    def "TuiState starts at IDLE"() {
        given: "a new TuiState"
        def state = new TuiState()

        expect: "initial state is IDLE"
        state.current() == TuiState.State.IDLE
    }

    def "TuiState transitions IDLE -> INPUTING"() {
        given: "a TuiState at IDLE"
        def state = new TuiState()

        when: "transitioning to INPUTING"
        state.transitionTo(TuiState.State.INPUTING)

        then: "state is INPUTING"
        state.current() == TuiState.State.INPUTING
    }

    def "TuiState transitions IDLE -> RUNNING"() {
        given: "a TuiState at IDLE"
        def state = new TuiState()

        when: "transitioning to RUNNING"
        state.transitionTo(TuiState.State.RUNNING)

        then: "state is RUNNING"
        state.current() == TuiState.State.RUNNING
    }

    def "TuiState isInputable returns true for IDLE"() {
        given: "a TuiState at IDLE"
        def state = new TuiState()

        expect: "IDLE is inputable"
        state.isInputable()
    }

    def "TuiState isInputable returns false for RUNNING"() {
        given: "a TuiState at RUNNING"
        def state = new TuiState(TuiState.State.RUNNING)

        expect: "RUNNING is not inputable"
        !state.isInputable()
    }

    // ========== SlashCommand 测试 ==========

    def "SlashCommand parses /help"() {
        when: "parsing /help"
        def cmd = SlashCommand.parse("/help")

        then: "command is recognized"
        cmd != null
        cmd.command() == "/help"
        cmd.type() == SlashCommand.Type.INTERNAL
    }

    def "SlashCommand parses /exec with args"() {
        when: "parsing /exec ls -la"
        def cmd = SlashCommand.parse("/exec ls -la")

        then: "command is recognized with args"
        cmd != null
        cmd.command() == "/exec"
        cmd.args() == "ls -la"
        cmd.type() == SlashCommand.Type.SYSTEM
        cmd.hasArgs()
    }

    def "SlashCommand returns null for non-slash input"() {
        expect: "regular text returns null"
        SlashCommand.parse("hello world") == null
    }

    def "SlashCommand help text contains all commands"() {
        when: "getting help text"
        def help = SlashCommand.helpText()

        then: "all commands are listed"
        help.contains("/new")
        help.contains("/clear")
        help.contains("/exit")
        help.contains("/help")
        help.contains("/prompt")
        help.contains("/exec")
        help.contains("/model")
        help.contains("/tools")
        help.contains("/routine")
    }

    def "SlashCommand parses /routine with args"() {
        when: "parsing /routine 0 */2 * * * ?"
        def cmd = SlashCommand.parse("/routine 0 */2 * * * ?")

        then: "command is recognized with CONFIG type"
        cmd != null
        cmd.command() == "/routine"
        cmd.args() == "0 */2 * * * ?"
        cmd.type() == SlashCommand.Type.CONFIG
        cmd.hasArgs()
    }

    def "SlashCommand parses /routine without args"() {
        when: "parsing /routine with no args"
        def cmd = SlashCommand.parse("/routine")

        then: "command is recognized with empty args"
        cmd != null
        cmd.command() == "/routine"
        cmd.args() == ""
        cmd.type() == SlashCommand.Type.CONFIG
        !cmd.hasArgs()
    }

    def "SlashCommand toAgentCommand for /routine returns RegisterRoutineCmd"() {
        given: "a parsed /routine slash command"
        def cmd = SlashCommand.parse("/routine 0 */2 * * * ?")

        when: "converting to AgentCommand"
        def ac = cmd.toAgentCommand("sess-01", "trace-abc")

        then: "it returns a RegisterRoutineCmd"
        ac != null
        ac instanceof RoutineTimeCmd.RegisterRoutineCmd
        (ac as RoutineTimeCmd.RegisterRoutineCmd).cronExpression() == "0 */2 * * * ?"
    }

    // ========== SlashCommand 补全命令解析测试 (§3.1) ==========

    def "SlashCommand parses /context"() {
        when: "parsing /context"
        def cmd = SlashCommand.parse("/context")

        then: "command is recognized with INTERNAL type"
        cmd != null
        cmd.command() == "/context"
        cmd.type() == SlashCommand.Type.INTERNAL
    }

    def "SlashCommand parses /compact"() {
        when: "parsing /compact"
        def cmd = SlashCommand.parse("/compact")

        then: "command is recognized with INTERNAL type"
        cmd != null
        cmd.command() == "/compact"
        cmd.type() == SlashCommand.Type.INTERNAL
    }

    def "SlashCommand toAgentCommand for /context returns ViewContextCmd"() {
        given: "a parsed /context slash command"
        def cmd = SlashCommand.parse("/context")

        when: "converting to AgentCommand"
        def ac = cmd.toAgentCommand("sess-01", "trace-abc")

        then: "it returns a ViewContextCmd"
        ac != null
        ac instanceof ControlCmd.ViewContextCmd
        (ac as ControlCmd.ViewContextCmd).sessionId() == "sess-01"
    }

    def "SlashCommand toAgentCommand for /compact returns CompactContextCmd"() {
        given: "a parsed /compact slash command"
        def cmd = SlashCommand.parse("/compact")

        when: "converting to AgentCommand"
        def ac = cmd.toAgentCommand("sess-01", "trace-abc")

        then: "it returns a CompactContextCmd"
        ac != null
        ac instanceof ControlCmd.CompactContextCmd
        (ac as ControlCmd.CompactContextCmd).sessionId() == "sess-01"
    }

    def "SlashCommand toAgentCommand for /feedback returns FeedbackCmd"() {
        given: "a parsed /feedback slash command with message"
        def cmd = SlashCommand.parse("/feedback 做得不错")

        when: "converting to AgentCommand"
        def ac = cmd.toAgentCommand("sess-01", "trace-abc")

        then: "it returns a FeedbackCmd with the message"
        ac != null
        ac instanceof ControlCmd.FeedbackCmd
        (ac as ControlCmd.FeedbackCmd).message() == "做得不错"
    }

    // ========== #666 D7：dispatch 分支必须断言"路由到了哪个处理器" ==========

    /**
     * 捕获 launcher 内部分发结果：`EventBridge` 是 handler 的唯一可观测出口。
     *
     * <p>#666 D7 前这 7 条只写 `noExceptionThrown()` —— 删掉 switch 里任意一个 case 它们仍然全绿
     * （等于没有断言）。这里改成"拿事件当证据"：走对分支 ⇒ 该分支特有的 ChatMessage 必须出现；
     * 不该走分支 ⇒ 必须一条都不出现。
     */
    private static List<String> captureChat(AliceTuiLauncher launcher) {
        def f = AliceTuiLauncher.getDeclaredField("eventBridge")
        f.setAccessible(true)
        def bridge = f.get(launcher) as EventBridge
        def captured = []
        bridge.addListener { ev ->
            if (ev instanceof TuiEvent.ChatMessage) {
                captured << (((TuiEvent.ChatMessage) ev).sender() + "|" + ((TuiEvent.ChatMessage) ev).content())
            }
        }
        return captured
    }

    /** 读 launcher 内部 screenManager 的 footer 模型名（WhiteBox，仅测试用）。 */
    private static String footerModelOf(AliceTuiLauncher launcher) {
        def f = AliceTuiLauncher.getDeclaredField("screenManager")
        f.setAccessible(true)
        def sm = f.get(launcher)
        return sm.layout().footer().modelInfo()
    }

    // ========== CommandHandler 命令分支测试 (§3.1) ==========


    def "CommandHandler dispatches /context to onAgentCommand"() {
        given: "a CommandHandler with a captured AgentCommand list"
        def bridge = new EventBridge()
        def handler = new CommandHandler(bridge).sessionId("sess-01")
        def dispatched = []
        handler.onAgentCommand({ cmd -> dispatched.add(cmd) })

        and: "a parsed /context SlashCommand"
        def cmd = SlashCommand.parse("/context")

        when: "executing the command"
        def handled = handler.execute(cmd)

        then: "command is handled and ViewContextCmd is dispatched"
        handled
        dispatched.size() == 1
        dispatched[0] instanceof ControlCmd.ViewContextCmd
    }

    def "CommandHandler dispatches /compact to onAgentCommand"() {
        given: "a CommandHandler with a captured AgentCommand list"
        def bridge = new EventBridge()
        def handler = new CommandHandler(bridge).sessionId("sess-01")
        def dispatched = []
        handler.onAgentCommand({ cmd -> dispatched.add(cmd) })

        and: "a parsed /compact SlashCommand"
        def cmd = SlashCommand.parse("/compact")

        when: "executing the command"
        def handled = handler.execute(cmd)

        then: "command is handled and CompactContextCmd is dispatched"
        handled
        dispatched.size() == 1
        dispatched[0] instanceof ControlCmd.CompactContextCmd
    }

    def "CommandHandler dispatches /clear internally (no AgentCommand)"() {
        given: "a CommandHandler with callbacks"
        def bridge = new EventBridge()
        def handler = new CommandHandler(bridge).sessionId("sess-01")
        def clearCalled = false
        handler.onClear({ clearCalled = true })

        and: "a parsed /clear SlashCommand"
        def cmd = SlashCommand.parse("/clear")

        when: "executing the command"
        def handled = handler.execute(cmd)

        then: "command is handled and onClear is called, no AgentCommand dispatch"
        handled
        clearCalled
    }

    def "CommandHandler dispatches /model to onAgentCommand"() {
        given: "a CommandHandler with callbacks"
        def bridge = new EventBridge()
        def handler = new CommandHandler(bridge).sessionId("sess-01")
        def dispatched = []
        def modelSwitchCalled = null
        handler.onAgentCommand({ cmd -> dispatched.add(cmd) })
        handler.onModelSwitch({ model -> modelSwitchCalled = model })

        and: "a parsed /model gpt-4o SlashCommand"
        def cmd = SlashCommand.parse("/model gpt-4o")

        when: "executing the command"
        def handled = handler.execute(cmd)

        then: "command is handled and SwitchModelCmd is dispatched"
        handled
        dispatched.size() == 1
        dispatched[0] instanceof AlignmentCmd.SwitchModelCmd
        (dispatched[0] as AlignmentCmd.SwitchModelCmd).modelId() == "gpt-4o"
        modelSwitchCalled == "gpt-4o"
    }

    def "CommandHandler dispatches /feedback to onAgentCommand"() {
        given: "a CommandHandler with callbacks"
        def bridge = new EventBridge()
        def handler = new CommandHandler(bridge).sessionId("sess-01")
        def dispatched = []
        handler.onAgentCommand({ cmd -> dispatched.add(cmd) })

        and: "a parsed /feedback please retry SlashCommand"
        def cmd = SlashCommand.parse("/feedback please retry")

        when: "executing the command"
        def handled = handler.execute(cmd)

        then: "command is handled and FeedbackCmd is dispatched"
        handled
        dispatched.size() == 1
        dispatched[0] instanceof ControlCmd.FeedbackCmd
        (dispatched[0] as ControlCmd.FeedbackCmd).message() == "please retry"
    }

    // ========== AliceTuiLauncher.dispatchAgentCommand() 测试 (§3.1) ==========

    def "AliceTuiLauncher dispatchAgentCommand routes ClearContextCmd to 清上下文分支"() {
        given:
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))
        def chat = captureChat(launcher)

        when:
        launcher.dispatchAgentCommand(new ControlCmd.ClearContextCmd("sess-01", "trace-abc"))

        then: "走的是 handleClearContext（该分支独有的一条 ChatMessage）"
        chat.any { it.contains("上下文已清除") }
    }

    def "AliceTuiLauncher dispatchAgentCommand routes ViewContextCmd to 看上下文分支"() {
        given:
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))
        def chat = captureChat(launcher)

        when:
        launcher.dispatchAgentCommand(new ControlCmd.ViewContextCmd("sess-01", "trace-abc"))

        then: "该分支必有且仅有一条 System 消息（内容随 agent 实现而异，但不能是空的）"
        chat.size() == 1
        chat[0].startsWith("System|")
        chat[0].startsWith("System|") && chat[0].length() > "System|".length() + 5
    }

    def "AliceTuiLauncher dispatchAgentCommand routes CompactContextCmd to 压缩分支"() {
        given:
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))
        def chat = captureChat(launcher)

        when:
        launcher.dispatchAgentCommand(new ControlCmd.CompactContextCmd("sess-01", "trace-abc"))

        then:
        chat.any { it.contains("压缩") }
    }

    def "AliceTuiLauncher dispatchAgentCommand routes FeedbackCmd to 反馈分支并带上原始内容"() {
        given:
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))
        def chat = captureChat(launcher)

        when:
        launcher.dispatchAgentCommand(new ControlCmd.FeedbackCmd("测试反馈", "sess-01", "trace-abc"))

        then: "消息里必须原样带出反馈内容（否则等于没把用户输入传下去）"
        chat.any { it.contains("测试反馈") && (it.contains("反馈已注入") || it.contains("反馈已记录")) }
    }

    def "AliceTuiLauncher dispatchAgentCommand routes SwitchModelCmd 且把模型名带出去"() {
        given:
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))
        def chat = captureChat(launcher)

        when:
        launcher.dispatchAgentCommand(new AlignmentCmd.SwitchModelCmd("gpt-4o", "sess-01", "trace-abc"))

        then: "该分支必须有一条带请求模型名的回执（内容是生效还是未生效由 agent 决定，见下一条）"
        chat.size() == 1
        chat[0].contains("gpt-4o")
    }

    /**
     * #666 D5：`switchModel()` 当前是空实现（只打日志），但 UI 原来无条件发「模型切换至: xxx」
     * 并改 footer ⇒ **用户看到"已切换"，推理仍用旧模型**。
     *
     * <p>本用例钉住"界面不得替系统撒谎"：只要切换没真的生效，
     * ① 不许出现「模型切换至:」；② footer 的模型名必须保持原值不变。
     */
    def "switchModel 未生效时 UI 不得宣称已切换（P-VACUOUS：不许拿没炸当结论）"() {
        given:
        def agent = new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults())
        def launcher = new AliceTuiLauncher(agent)
        def chat = captureChat(launcher)
        def footerBefore = footerModelOf(launcher)
        def effective = agent.currentModelId()

        when: "请求切到一个和当前不同的模型"
        def target = effective + "-not-implemented"
        launcher.dispatchAgentCommand(new AlignmentCmd.SwitchModelCmd(target, "sess-01", "trace-abc"))

        then: "没有任何一条消息宣称切换成功"
        chat.every { !it.contains("模型切换至") }

        and: "回执必须说清未生效，并带上仍在使用的模型"
        chat.size() == 1
        chat[0].contains("未生效")
        chat[0].contains(effective)
        chat[0].contains(target)

        and: "footer 不得被改成未生效的目标模型"
        footerModelOf(launcher) == footerBefore
        footerModelOf(launcher) != target
    }

    def "AliceTuiLauncher dispatchAgentCommand 对未接线的 RegisterRoutineCmd 不得伪装成功"() {
        given:
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))
        def chat = captureChat(launcher)

        when: "该类型当前没有对应 case，会落到 default 分支（只 warn，不回消息）"
        launcher.dispatchAgentCommand(new RoutineTimeCmd.RegisterRoutineCmd("0 */5 * * * ?", "sess-01", "trace-abc"))

        then: "没有假装成功的用户可见回执 —— 未接线就是未接线（接线后这条会红，需同步改断言）"
        chat.isEmpty()
    }

    def "AliceTuiLauncher dispatchAgentCommand handles null gracefully"() {
        given:
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))
        def chat = captureChat(launcher)

        when: "dispatching null"
        launcher.dispatchAgentCommand(null)

        then: "既不抛异常，也不产生任何分发副作用"
        noExceptionThrown()
        chat.isEmpty()
    }

    // ========== #748 TUI 批：/new 与 /model 不得在分发前预声明成功 ==========

    /** 取 launcher 里的 CommandHandler（用户敲 `/` 命令的真实入口）。 */
    private static Object handlerOf(AliceTuiLauncher launcher) {
        def smField = AliceTuiLauncher.getDeclaredField("screenManager")
        smField.setAccessible(true)
        def sm = smField.get(launcher)
        def chField = sm.class.getDeclaredField("commandHandler")
        chField.setAccessible(true)
        return chField.get(sm)
    }

    def "全链路 /new：不再分发前宣称已重置；结论由执行方按真实结果给出"() {
        given:
        def agent = new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults())
        def launcher = new AliceTuiLauncher(agent)
        def chat = captureChat(launcher)

        when:
        def handler = handlerOf(launcher)
        handler.execute(SlashCommand.parse("/new"))

        then: "重置类消息只能有一条（原来会打两条：CommandHandler 预声明 + handleReset），且不得自相矛盾"
        def resetMsgs = chat.findAll { it.contains("会话已重置") || it.contains("会话重置未生效") }
        resetMsgs.size() == 1
    }

    def "全链路 /new：真的清空 Agent 上下文（断副作用，不断消息条数）"() {
        given: "先往会话里塞一条短期记忆（有东西可清，才能验证真的清了）"
        def sid = "sess-748-reset"
        def agent = new org.cland.alice.core.agent.Agent("tui-748", sid,
            org.cland.alice.core.agent.AgentConfig.defaults())
            .withMemory(new org.cland.alice.core.agent.memory.AgentSession())
        agent.memory().persist(sid, "旧上下文-748")
        assert agent.memory().getShortTerm(sid).contains("旧上下文-748")
        def launcher = new AliceTuiLauncher(agent)
        def chat = captureChat(launcher)

        when: "用户敲 /new（经 CommandHandler，真实入口）"
        handlerOf(launcher).execute(SlashCommand.parse("/new"))

        then: "断言**副作用**：短期记忆真空 —— 只断消息条数会让「删掉 clearMemory()」照样绿"
        agent.memory().getShortTerm(sid).isEmpty()

        and: "并且回显说的是真的那一句"
        chat.any { it.contains("上下文已清空") }
    }

    def "全链路 /new：没有短期记忆实现时不得宣称上下文已清空（no-op 也要如实说）"() {
        given: "生产 TUI 装配（Agent.createDefault）本来就不挂 AgentSession ⇒ memory() == null"
        def sid = "sess-748-no-memory"
        def agent = new org.cland.alice.core.agent.Agent("tui-748b", sid,
            org.cland.alice.core.agent.AgentConfig.defaults())
        assert agent.memory() == null
        def launcher = new AliceTuiLauncher(agent)
        def chat = captureChat(launcher)

        when:
        handlerOf(launcher).execute(SlashCommand.parse("/new"))

        then: "不许说「上下文已清空」（那是 no-op 撑起来的假话）；要如实说未启用短期记忆"
        chat.every { !it.contains("上下文已清空") }
        chat.any { it.contains("未启用短期记忆") }
    }

    // ========== #752：全链路只有一个会话 id（resume 指引必须指向真实历史）==========

    def "#752 launcher / agent / 命令层用同一个会话 id（三个 id 必须相等，否则 resume 指引指到不存在的历史）"() {
        given:
        def sid = "sess-752-single"
        def agent = new org.cland.alice.core.agent.Agent("tui-752", sid,
            org.cland.alice.core.agent.AgentConfig.defaults())
        def launcher = new AliceTuiLauncher(agent)

        expect: "装配后三条链路共用一个 id（launcher 不再自造、命令层不再自造）"
        launcherSessionIdOf(launcher) == sid
        handlerSessionIdOf(launcher) == sid
        launcherAgentId(launcher) == sid
    }

    def "#752 /context 显示的会话 id 就是 Agent 的 id（用户照抄它能 resume 到真历史）"() {
        given:
        def sid = "sess-752-context"
        def agent = new org.cland.alice.core.agent.Agent("tui-752c", sid,
            org.cland.alice.core.agent.AgentConfig.defaults())
        def launcher = new AliceTuiLauncher(agent)
        def chat = captureChat(launcher)

        when:
        handlerOf(launcher).execute(SlashCommand.parse("/context"))

        then: "命令层用的是 Agent 的 id（否则显示的是另一个会话的上下文）"
        chat.any { it.contains("sess-752-context") }
        chat.every { !(it.contains("会话 ID |") && !it.contains("sess-752-context")) }
    }

    def "#752 配置构造（无外部 Agent）：launcher 与命令层都采用 Agent 生成的 id"() {
        when:
        def launcher = new AliceTuiLauncher(org.cland.alice.core.agent.AgentConfig.defaults())
        def agentId = launcherAgentId(launcher)

        then:
        agentId != null && !agentId.isEmpty()
        launcherSessionIdOf(launcher) == agentId
        handlerSessionIdOf(launcher) == agentId
    }

    /** 取 launcher 内部 Agent 的会话 id。 */
    private static String launcherAgentId(AliceTuiLauncher launcher) {
        def f = AliceTuiLauncher.getDeclaredField("agent")
        f.setAccessible(true)
        return ((org.cland.alice.core.agent.Agent) f.get(launcher)).sessionId()
    }

    /** 取 launcher 自己持有的会话 id（装配核查用）。 */
    private static String launcherSessionIdOf(AliceTuiLauncher launcher) {
        def f = AliceTuiLauncher.getDeclaredField("sessionId")
        f.setAccessible(true)
        return (String) f.get(launcher)
    }

    /** 取命令层（CommandHandler）实际使用的会话 id。 */
    private static String handlerSessionIdOf(AliceTuiLauncher launcher) {
        def h = handlerOf(launcher)
        return (String) h.sessionId()
    }

    // ========== #751：短期记忆端口的现状契约（决议前护栏）==========

    def "#751 默认装配不含短期记忆（事实契约）：createDefault() 不挂 AgentSession"() {
        expect: "默认装配 memory() == null —— 若有人无意识接上，这条会红，强迫同步改文案/文档并给出决议"
        org.cland.alice.core.agent.Agent.createDefault(
            org.cland.alice.core.agent.AgentConfig.defaults()).memory() == null
    }

    def "#751 无记忆时 /new 回显必须指明真源在磁盘、可恢复（不许只说已重置）"() {
        given:
        def sid = "sess-751-resume"
        def agent = new org.cland.alice.core.agent.Agent("tui-751", sid,
            org.cland.alice.core.agent.AgentConfig.defaults())
        def launcher = new AliceTuiLauncher(agent)
        def chat = captureChat(launcher)

        when:
        handlerOf(launcher).execute(SlashCommand.parse("/new"))

        then: "必须告诉用户历史还在、怎么恢复（否则「已重置」读起来像把历史删了）"
        chat.any { it.contains("alice resume --session-id") && it.contains(sid) }
        chat.any { it.contains("无内存上下文可清") }
    }

    def "全链路 /model 未生效：不得在分发前宣称切换（两句打脸）"() {
        given:
        def agent = new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults())
        def launcher = new AliceTuiLauncher(agent)
        def chat = captureChat(launcher)
        def target = agent.currentModelId() + "-not-registered"

        when:
        def handler = handlerOf(launcher)
        handler.execute(SlashCommand.parse("/model " + target))

        then: "不许出现预声明；必须出现未生效"
        chat.every { !it.contains("切换模型至") }
        chat.any { it.contains("未生效") && it.contains(target) }
    }

    // ========== TuiEvent 测试 ==========

    def "TuiEvent StartThinking has correct prompt"() {
        given: "a StartThinking event"
        def event = new TuiEvent.StartThinking("What is AI?")

        expect: "event has correct prompt"
        event.prompt() == "What is AI?"
    }

    def "TuiEvent TaskComplete has correct result"() {
        given: "a TaskComplete event"
        def event = new TuiEvent.TaskComplete("42", "Calculation done")

        expect: "event has correct result and summary"
        event.result() == "42"
        event.summary() == "Calculation done"
    }

    def "TuiEvent ChatMessage has correct sender and content"() {
        given: "a ChatMessage event"
        def event = new TuiEvent.ChatMessage("User", "Hello!")

        expect: "event has correct fields"
        event.sender() == "User"
        event.content() == "Hello!"
    }

    // ========== EventBridge 测试 ==========

    def "EventBridge delivers events to listeners"() {
        given: "an EventBridge with a listener"
        def bridge = new EventBridge()
        def received = []
        bridge.addListener({ event -> received.add(event) })

        when: "emitting an event"
        bridge.emit(new TuiEvent.ChatMessage("System", "test"))

        then: "listener receives the event (async, wait briefly)"
        Thread.sleep(100)
        received.size() == 1
        received[0] instanceof TuiEvent.ChatMessage
        received[0].sender() == "System"
    }

    def "EventBridge supports multiple listeners"() {
        given: "an EventBridge with two listeners"
        def bridge = new EventBridge()
        def received1 = []
        def received2 = []
        bridge.addListener({ event -> received1.add(event) })
        bridge.addListener({ event -> received2.add(event) })

        when: "emitting an event"
        bridge.emit(new TuiEvent.TaskComplete("done", "ok"))

        then: "both listeners receive the event"
        Thread.sleep(100)
        received1.size() == 1
        received2.size() == 1
    }

    def "EventBridge stops delivering after close"() {
        given: "a closed EventBridge with a listener"
        def bridge = new EventBridge()
        def received = []
        bridge.addListener({ event -> received.add(event) })
        bridge.close()

        when: "emitting after close"
        bridge.emit(new TuiEvent.ChatMessage("System", "after close"))

        then: "no events are delivered"
        Thread.sleep(100)
        received.isEmpty()
    }
}
