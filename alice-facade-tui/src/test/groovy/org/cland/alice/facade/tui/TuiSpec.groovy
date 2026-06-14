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
import org.cland.alice.agent.command.*

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

    def "TuiState rejects invalid IDLE -> RUNNING"() {
        given: "a TuiState at IDLE"
        def state = new TuiState()

        when: "transitioning to RUNNING"
        state.transitionTo(TuiState.State.RUNNING)

        then: "IllegalStateException is thrown"
        thrown(IllegalStateException)
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

    def "AliceTuiLauncher dispatchAgentCommand accepts ClearContextCmd"() {
        given: "an AliceTuiLauncher with mocked Agent"
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))

        and: "a ClearContextCmd"
        def cmd = new ControlCmd.ClearContextCmd("sess-01", "trace-abc")

        when: "dispatching the command"
        launcher.dispatchAgentCommand(cmd)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "AliceTuiLauncher dispatchAgentCommand accepts ViewContextCmd"() {
        given: "an AliceTuiLauncher with mocked Agent"
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))

        and: "a ViewContextCmd"
        def cmd = new ControlCmd.ViewContextCmd("sess-01", "trace-abc")

        when: "dispatching the command"
        launcher.dispatchAgentCommand(cmd)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "AliceTuiLauncher dispatchAgentCommand accepts CompactContextCmd"() {
        given: "an AliceTuiLauncher with mocked Agent"
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))

        and: "a CompactContextCmd"
        def cmd = new ControlCmd.CompactContextCmd("sess-01", "trace-abc")

        when: "dispatching the command"
        launcher.dispatchAgentCommand(cmd)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "AliceTuiLauncher dispatchAgentCommand accepts FeedbackCmd"() {
        given: "an AliceTuiLauncher with mocked Agent"
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))

        and: "a FeedbackCmd"
        def cmd = new ControlCmd.FeedbackCmd("测试反馈", "sess-01", "trace-abc")

        when: "dispatching the command"
        launcher.dispatchAgentCommand(cmd)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "AliceTuiLauncher dispatchAgentCommand accepts SwitchModelCmd"() {
        given: "an AliceTuiLauncher with mocked Agent"
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))

        and: "a SwitchModelCmd"
        def cmd = new AlignmentCmd.SwitchModelCmd("gpt-4o", "sess-01", "trace-abc")

        when: "dispatching the command"
        launcher.dispatchAgentCommand(cmd)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "AliceTuiLauncher dispatchAgentCommand accepts RegisterRoutineCmd"() {
        given: "an AliceTuiLauncher with mocked Agent"
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))

        and: "a RegisterRoutineCmd"
        def cmd = new RoutineTimeCmd.RegisterRoutineCmd("0 */5 * * * ?", "sess-01", "trace-abc")

        when: "dispatching the command"
        launcher.dispatchAgentCommand(cmd)

        then: "no exception is thrown (falls through to default/log)"
        noExceptionThrown()
    }

    def "AliceTuiLauncher dispatchAgentCommand handles null gracefully"() {
        given: "an AliceTuiLauncher with mocked Agent"
        def launcher = new AliceTuiLauncher(
            new org.cland.alice.core.agent.Agent(org.cland.alice.core.agent.AgentConfig.defaults()))

        when: "dispatching null"
        launcher.dispatchAgentCommand(null)

        then: "no exception is thrown"
        noExceptionThrown()
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
