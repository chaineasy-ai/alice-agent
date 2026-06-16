package org.cland.alice.facade.cmd.config

import spock.lang.Specification

class CommandParserSpec extends Specification {

    def parser = new CommandParser()

    def "should parse run subcommand with task"() {
        when:
        def config = parser.parse(["run", "count files in directory"] as String[])

        then:
        config != null
        config.task() == "count files in directory"
        config.model() == RunConfig.DEFAULT_MODEL
        !config.jsonOutput()
        !config.verbose()
    }

    def "should parse run with all options"() {
        when:
        def config = parser.parse([
            "run", "find 8080 port",
            "--model", "claude-3.5-sonnet",
            "--verbose",
            "--json",
            "--timeout", "120"
        ] as String[])

        then:
        config != null
        config.task() == "find 8080 port"
        config.model() == "claude-3.5-sonnet"
        config.verbose()
        config.jsonOutput()
        config.timeoutSeconds() == 120
    }

    def "should parse run with short options"() {
        when:
        def config = parser.parse([
            "run", "check system health",
            "-m", "gpt-4o",
            "-v"
        ] as String[])

        then:
        config != null
        config.task() == "check system health"
        config.model() == "gpt-4o"
        config.verbose()
        !config.jsonOutput()
    }

    def "should return null for --help on run subcommand"() {
        when:
        def config = parser.parse(["run", "--help"] as String[])

        then:
        config == null
    }

    def "should return null for --version on run subcommand"() {
        when:
        def config = parser.parse(["run", "--version"] as String[])

        then:
        config == null
    }

    def "should throw ParseException when no subcommand given"() {
        when:
        parser.parse([] as String[])

        then:
        def e = thrown(CommandParser.ParseException)
        e.exitCode() == 2
    }

    def "should throw ParseException on unknown option"() {
        when:
        parser.parse(["run", "--unknown-flag"] as String[])

        then:
        def e = thrown(CommandParser.ParseException)
        e.exitCode() == 2
    }

    def "should throw ParseException on unknown subcommand"() {
        when:
        parser.parse(["nonexistent"] as String[])

        then:
        def e = thrown(CommandParser.ParseException)
        e.exitCode() >= 1
    }

    def "should handle run with verbose and json flags"() {
        when:
        def config = parser.parse([
            "run", "analyze logs",
            "--verbose", "--json"
        ] as String[])

        then:
        config != null
        config.task() == "analyze logs"
        config.verbose()
        config.jsonOutput()
    }

    def "should parse run with default model when --model not given"() {
        when:
        def config = parser.parse(["run", "hello"] as String[])

        then:
        config.model() == RunConfig.DEFAULT_MODEL
    }

    def "should parse chat subcommand to RunConfig"() {
        when:
        def config = parser.parse(["chat"] as String[])

        then:
        config != null
        config.chat()
        config.task() == "chat"
    }

    def "should parse tools subcommand"() {
        when:
        def config = parser.parse(["tools"] as String[])

        then:
        config != null
        config.task() == "tools"
        config.listTools()
        !config.toolDetail()
    }

    def "should parse tools subcommand with --detail"() {
        when:
        def config = parser.parse(["tools", "--detail"] as String[])

        then:
        config != null
        config.task() == "tools"
        config.listTools()
        config.toolDetail()
    }

    def "should parse config subcommand (show all)"() {
        when:
        def config = parser.parse(["config"] as String[])

        then:
        config != null
        config.task() == "config"
        config.configAction() == "show"
        config.configKey() == null
        config.configValue() == null
    }

    def "should parse config subcommand with get"() {
        when:
        def config = parser.parse(["config", "get", "openai.api_key"] as String[])

        then:
        config != null
        config.task() == "config"
        config.configAction() == "get"
        config.configKey() == "openai.api_key"
        config.configValue() == null
    }

    def "should parse config subcommand with set"() {
        when:
        def config = parser.parse(["config", "set", "openai.api_key", "sk-xxx"] as String[])

        then:
        config != null
        config.task() == "config"
        config.configAction() == "set"
        config.configKey() == "openai.api_key"
        config.configValue() == "sk-xxx"
    }

    def "should parse config subcommand with help"() {
        when:
        def config = parser.parse(["config", "--help"] as String[])

        then:
        config == null
    }

    // ========================================================================
    // routine subcommand
    // ========================================================================

    def "should parse routine subcommand with cron expression"() {
        when:
        def config = parser.parse(["routine", "0 */2 * * * ?"] as String[])

        then:
        config != null
        config.routineCron() == "0 */2 * * * ?"
        !config.listRoutines()
    }

    def "should parse routine subcommand with --list flag"() {
        when:
        def config = parser.parse(["routine", "--list"] as String[])

        then:
        config != null
        config.listRoutines()
        config.routineCron() == null
    }

    def "should parse routine subcommand with short -l flag"() {
        when:
        def config = parser.parse(["routine", "-l"] as String[])

        then:
        config != null
        config.listRoutines()
    }

    def "should parse routine subcommand with cron and later list"() {
        when:
        def config = parser.parse(["routine", "0 */2 * * * ?", "--list"] as String[])

        then:
        config != null
        config.routineCron() == "0 */2 * * * ?"
        config.listRoutines()
    }

    def "should return null for --help on routine subcommand"() {
        when:
        def config = parser.parse(["routine", "--help"] as String[])

        then:
        config == null
    }

    def "should parse routine subcommand with no args"() {
        when:
        def config = parser.parse(["routine"] as String[])

        then:
        config != null
        config.routineCron() == null
        !config.listRoutines()
    }

    // ========================================================================
    // sub-agent subcommand
    // ========================================================================

    def "should parse sub-agent with --spawn goal"() {
        when:
        def config = parser.parse(["sub-agent", "--spawn", "analyze logs"] as String[])

        then:
        config != null
        config.subAgentSpawnGoal() == "analyze logs"
        !config.subAgentList()
    }

    def "should parse sub-agent with --connect and --acp-endpoint"() {
        when:
        def config = parser.parse(["sub-agent", "--connect", "worker1", "--acp-endpoint", "http://localhost:8080"] as String[])

        then:
        config != null
        config.subAgentConnectName() == "worker1"
        config.subAgentConnectEndpoint() == "http://localhost:8080"
    }

    def "should parse sub-agent with --list"() {
        when:
        def config = parser.parse(["sub-agent", "--list"] as String[])

        then:
        config != null
        config.subAgentList()
    }

    def "should parse sub-agent with --cancel"() {
        when:
        def config = parser.parse(["sub-agent", "--cancel", "abc-123"] as String[])

        then:
        config != null
        config.subAgentCancelId() == "abc-123"
    }

    def "should parse sub-agent with --results"() {
        when:
        def config = parser.parse(["sub-agent", "--results", "def-456"] as String[])

        then:
        config != null
        config.subAgentResultsId() == "def-456"
    }

    def "should parse sub-agent with --send and --message"() {
        when:
        def config = parser.parse(["sub-agent", "--send", "agent1", "--message", "hello world"] as String[])

        then:
        config != null
        config.subAgentSendId() == "agent1"
        config.subAgentSendMessage() == "hello world"
    }

    def "should parse sub-agent with --prompt and --agent-id"() {
        when:
        def config = parser.parse(["sub-agent", "--prompt", "do task", "--agent-id", "ext-agent"] as String[])

        then:
        config != null
        config.subAgentPromptText() == "do task"
        config.subAgentPromptAgentId() == "ext-agent"
    }

    def "should return null for --help on sub-agent"() {
        when:
        def config = parser.parse(["sub-agent", "--help"] as String[])

        then:
        config == null
    }

    def "should parse sub-agent with no options"() {
        when:
        def config = parser.parse(["sub-agent"] as String[])

        then:
        config != null
        config.subAgentSpawnGoal() == null
        config.subAgentConnectName() == null
        !config.subAgentList()
        config.subAgentCancelId() == null
    }
}
