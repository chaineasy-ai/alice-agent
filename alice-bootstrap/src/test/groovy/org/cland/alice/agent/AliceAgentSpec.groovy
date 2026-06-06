/*
 * Spock specification for AliceAgent app module.
 */
package org.cland.alice.agent

import spock.lang.Specification

class AliceAgentSpec extends Specification {

    def "AliceAgent has a valid version string"() {
        expect:
        AliceAgent.VERSION != null
        !AliceAgent.VERSION.isEmpty()
    }

    def "AliceAgent can be instantiated with default config"() {
        given:
        def agent = new AliceAgent()

        expect:
        agent.agent() != null
        agent.agent().agentId() != null
        !agent.isRunning()
    }

    def "FacadeSelector detects TUI mode from --tui flag"() {
        expect:
        FacadeSelector.detect(["--tui"] as String[]) == FacadeSelector.FacadeType.TUI
    }

    def "FacadeSelector detects TUI mode from -t flag"() {
        expect:
        FacadeSelector.detect(["-t"] as String[]) == FacadeSelector.FacadeType.TUI
    }

    def "FacadeSelector detects CLI mode from --cli flag"() {
        expect:
        FacadeSelector.detect(["--cli"] as String[]) == FacadeSelector.FacadeType.CLI
    }

    def "FacadeSelector defaults to CLI when no flags given"() {
        expect:
        FacadeSelector.detect([] as String[]) == FacadeSelector.FacadeType.CLI
        FacadeSelector.detect(null) == FacadeSelector.FacadeType.CLI
    }

    def "AliceApp has correct exit codes"() {
        expect:
        AliceApp.EXIT_SUCCESS == 0
        AliceApp.EXIT_RUNTIME_ERROR == 1
        AliceApp.EXIT_PARAM_ERROR == 2
        AliceApp.EXIT_INTERRUPTED == 130
    }
}
