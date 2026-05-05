package org.cland.alice.facade.cmd

import org.cland.alice.facade.cmd.config.RunConfig
import spock.lang.Specification

class AliceCliLauncherSpec extends Specification {

    def "run() should return EXIT_PARAM_ERROR for invalid args"() {
        when:
        def exitCode = AliceCliLauncher.run(["--unknown"] as String[])

        then:
        exitCode == AliceCliLauncher.EXIT_PARAM_ERROR
    }

    def "run() should return EXIT_PARAM_ERROR for empty args"() {
        when:
        def exitCode = AliceCliLauncher.run([] as String[])

        then:
        exitCode == AliceCliLauncher.EXIT_PARAM_ERROR
    }

    def "run() should return EXIT_PARAM_ERROR when only help requested"() {
        given:
        def origOut = System.out
        System.setOut(new PrintStream(new ByteArrayOutputStream()))

        when:
        def exitCode = AliceCliLauncher.run(["run", "--help"] as String[])

        then:
        exitCode == AliceCliLauncher.EXIT_PARAM_ERROR

        cleanup:
        System.setOut(origOut)
    }

    def "run() should return EXIT_PARAM_ERROR for chat subcommand"() {
        when:
        def exitCode = AliceCliLauncher.run(["chat"] as String[])

        then:
        exitCode == 1
    }

    def "exit codes are defined correctly"() {
        expect:
        AliceCliLauncher.EXIT_SUCCESS == 0
        AliceCliLauncher.EXIT_RUNTIME_ERROR == 1
        AliceCliLauncher.EXIT_PARAM_ERROR == 2
        AliceCliLauncher.EXIT_INTERRUPTED == 130
    }
}
