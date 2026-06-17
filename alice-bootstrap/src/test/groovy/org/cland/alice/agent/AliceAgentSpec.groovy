/*
 * Spock specification for alice-bootstrap module (Pure Bootstrapper).
 *
 * Tests FacadeSelector routing and AliceApp exit codes.
 * Does NOT test Agent/Model/Config — those are handled by facade modules.
 */
package org.cland.alice.agent

import spock.lang.Specification

class AliceAgentSpec extends Specification {

    // ================================================================
    // AliceApp exit codes
    // ================================================================

    def "AliceApp has correct exit codes"() {
        expect:
        AliceApp.EXIT_SUCCESS == 0
        AliceApp.EXIT_RUNTIME_ERROR == 1
        AliceApp.EXIT_PARAM_ERROR == 2
        AliceApp.EXIT_INTERRUPTED == 130
    }

    // ================================================================
    // FacadeSelector routing logic
    // ================================================================

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

    def "FacadeSelector --cli overrides --tui"() {
        expect:
        FacadeSelector.detect(["--tui", "--cli"] as String[]) == FacadeSelector.FacadeType.CLI
    }

    // ================================================================
    // FacadeSelector.launch() basic smoke tests
    // ================================================================

    def "FacadeSelector.launch CLI with no args returns EXIT_SUCCESS (prints help)"() {
        expect:
        FacadeSelector.launch(FacadeSelector.FacadeType.CLI, [] as String[]) == AliceApp.EXIT_SUCCESS
    }

    def "FacadeSelector.launch CLI with null args returns EXIT_SUCCESS (prints help)"() {
        expect:
        FacadeSelector.launch(FacadeSelector.FacadeType.CLI, null) == AliceApp.EXIT_SUCCESS
    }

    def "FacadeSelector.launch CLI with 'run' subcommand delegates to AliceCliLauncher"() {
        expect:
        FacadeSelector.launch(FacadeSelector.FacadeType.CLI, ["run", "测试任务"] as String[]) == AliceApp.EXIT_SUCCESS
    }
}
