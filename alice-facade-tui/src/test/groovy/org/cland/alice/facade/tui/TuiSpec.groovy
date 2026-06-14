/*
 * Tests for alice-facade-tui module.
 */
package org.cland.alice.facade.tui

import spock.lang.Specification

import org.cland.alice.facade.tui.state.TuiState
import org.cland.alice.facade.tui.command.SlashCommand
import org.cland.alice.facade.tui.bridge.TuiEvent
import org.cland.alice.facade.tui.bridge.EventBridge

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
        ac instanceof org.cland.alice.agent.command.RoutineTimeCmd.RegisterRoutineCmd
        (ac as org.cland.alice.agent.command.RoutineTimeCmd.RegisterRoutineCmd).cronExpression() == "0 */2 * * * ?"
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
