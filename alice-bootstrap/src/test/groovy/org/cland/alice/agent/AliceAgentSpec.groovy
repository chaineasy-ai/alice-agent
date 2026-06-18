/*
 * Spock specification for alice-bootstrap module (Pure Bootstrapper, SPI-based).
 *
 * Tests FacadeSelector SPI discovery and AliceApp exit codes.
 * Does NOT test Agent/Model/Config — those are handled by facade modules.
 *
 * Note: These tests require alice-facade-cmd on the test classpath so that
 * ServiceLoader can discover AliceCliFacade. The bootstrap build.gradle
 * declares testImplementation project(':alice-facade-cmd') for this reason.
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
    // FacadeSelector.launch() — SPI-based routing
    // ================================================================

    def "FacadeSelector.launch with no args delegates to CLI facade (requires subcommand → exit 2)"() {
        expect:
        FacadeSelector.launch([] as String[]) == AliceApp.EXIT_PARAM_ERROR
    }

    def "FacadeSelector.launch with null args returns runtime error (NPE from picocli)"() {
        expect:
        FacadeSelector.launch(null) == AliceApp.EXIT_RUNTIME_ERROR
    }

    def "FacadeSelector.launch with --cli flag delegates to CLI facade"() {
        expect:
        FacadeSelector.launch(["--cli"] as String[]) == AliceApp.EXIT_PARAM_ERROR
    }

    def "FacadeSelector.launch with --tui flag delegates to TUI facade (returns 0 in test mode)"() {
        expect:
        FacadeSelector.launch(["--tui"] as String[]) == AliceApp.EXIT_SUCCESS
    }

    def "FacadeSelector.launch with 'run' subcommand delegates to AliceCliLauncher"() {
        expect:
        FacadeSelector.launch(["run", "测试任务"] as String[]) == AliceApp.EXIT_SUCCESS
    }
}
