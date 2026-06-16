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

    def "run() should return EXIT_SUCCESS when help requested"() {
        given:
        def origOut = System.out
        System.setOut(new PrintStream(new ByteArrayOutputStream()))

        when:
        def exitCode = AliceCliLauncher.run(["run", "--help"] as String[])

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS

        cleanup:
        System.setOut(origOut)
    }

    def "run() should return EXIT_SUCCESS for chat subcommand"() {
        when:
        def exitCode = AliceCliLauncher.run(["chat"] as String[])

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
    }

    def "run() should return EXIT_SUCCESS for top-level --help"() {
        given:
        def origOut = System.out
        System.setOut(new PrintStream(new ByteArrayOutputStream()))

        when:
        def exitCode = AliceCliLauncher.run(["--help"] as String[])

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS

        cleanup:
        System.setOut(origOut)
    }

    def "run() should return EXIT_SUCCESS for top-level --version"() {
        given:
        def origOut = System.out
        System.setOut(new PrintStream(new ByteArrayOutputStream()))

        when:
        def exitCode = AliceCliLauncher.run(["--version"] as String[])

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS

        cleanup:
        System.setOut(origOut)
    }

    def "exit codes are defined correctly"() {
        expect:
        AliceCliLauncher.EXIT_SUCCESS == 0
        AliceCliLauncher.EXIT_RUNTIME_ERROR == 1
        AliceCliLauncher.EXIT_PARAM_ERROR == 2
        AliceCliLauncher.EXIT_INTERRUPTED == 130
    }

    // ========================================================================
    // dispatchCommand 测试
    // ========================================================================

    def "dispatch null returns EXIT_PARAM_ERROR"() {
        when:
        def exitCode = AliceCliLauncher.dispatchCommand((org.cland.alice.agent.command.AgentCommand) null)

        then:
        exitCode == AliceCliLauncher.EXIT_PARAM_ERROR
    }

    def "dispatch /exec prints executing raw"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/exec ls -la")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Executing raw: ls -la")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /skill prints registering skill"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/skill filesystem")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Registering skill: filesystem")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /rules prints updating rules"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/rules security-policy")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Updating rules: security-policy")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /reload prints reloading"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/reload")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Reloading kernel")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /model prints switching model"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/model gpt-4o")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Switching model to: gpt-4o")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /new prints resetting session"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/new")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Resetting session")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /clear prints context cleared"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/clear")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("上下文已清除")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /context prints session info"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/context")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("会话 ID")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /compact prints compression info"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/compact")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("上下文压缩")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /feedback prints feedback message"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/feedback Great job!")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Feedback received: Great job!")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /exit prints interrupted"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/exit")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Interrupted")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /routine prints registering routine"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/routine 0 */5 * * * ?")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Registering routine: 0 */5 * * * ?")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /sub-agent spawn returns EXIT_SUCCESS"() {
        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/sub-agent spawn --goal 'analyze logs'")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
    }

    def "dispatch /sub-agent connect prints connecting"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/sub-agent connect --name worker1 --acp-endpoint http://localhost:8080")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Connecting to ACP agent: worker1")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /sub-agent list prints listing"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/sub-agent list")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Listing sub-agents")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /sub-agent cancel prints cancellation"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/sub-agent cancel abc-123")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Cancel sub-agent: abc-123")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /sub-agent results prints results"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/sub-agent results def-456")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Get results for sub-agent: def-456")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /sub-agent send prints message"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/sub-agent send agent1 --message hello")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Send message to sub-agent agent1")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch /sub-agent prompt prints prompt"() {
        given:
        def origOut = System.out
        def baos = new ByteArrayOutputStream()
        System.setOut(new PrintStream(baos))

        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/sub-agent prompt ext-agent --text 'do something'")

        then:
        exitCode == AliceCliLauncher.EXIT_SUCCESS
        baos.toString().contains("Prompt sub-agent ext-agent")

        cleanup:
        System.setOut(origOut)
    }

    def "dispatch unknown command returns EXIT_PARAM_ERROR"() {
        when:
        def exitCode = AliceCliLauncher.dispatchCommand("/nonexistent")

        then:
        exitCode == AliceCliLauncher.EXIT_PARAM_ERROR
    }
}
