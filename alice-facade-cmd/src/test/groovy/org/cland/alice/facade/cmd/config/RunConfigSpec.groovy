package org.cland.alice.facade.cmd.config

import spock.lang.Specification

class RunConfigSpec extends Specification {

    def "should create with defaults"() {
        when:
        def config = RunConfig.builder()
            .task("test task")
            .build()

        then:
        config.task() == "test task"
        config.model() == RunConfig.DEFAULT_MODEL
        !config.jsonOutput()
        !config.verbose()
        config.timeoutSeconds() == RunConfig.DEFAULT_TIMEOUT_SECONDS
        config.envVars().isEmpty()
    }

    def "should set all fields via builder"() {
        when:
        def config = RunConfig.builder()
            .task("analyze logs")
            .model("gpt-4o")
            .jsonOutput(true)
            .verbose(true)
            .timeoutSeconds(300)
            .envVar("KEY", "value")
            .build()

        then:
        config.task() == "analyze logs"
        config.model() == "gpt-4o"
        config.jsonOutput()
        config.verbose()
        config.timeoutSeconds() == 300
        config.envVars() == ["KEY": "value"]
    }

    def "should reject null task"() {
        when:
        RunConfig.builder().task(null).build()

        then:
        thrown(NullPointerException)
    }

    def "should set envVars map"() {
        given:
        def vars = ["A": "1", "B": "2"]

        when:
        def config = RunConfig.builder()
            .task("hello")
            .envVars(vars)
            .build()

        then:
        config.envVars() == vars
        !config.envVars().is(vars) // immutable copy
        config.envVars().size() == 2
    }

    def "toString contains key fields"() {
        given:
        def config = RunConfig.builder()
            .task("test")
            .model("gpt-4o")
            .jsonOutput(true)
            .build()

        when:
        def str = config.toString()

        then:
        str.contains("test")
        str.contains("gpt-4o")
        str.contains("jsonOutput=true")
    }

    def "should ignore zero timeout"() {
        when:
        def config = RunConfig.builder()
            .task("test")
            .timeoutSeconds(0)
            .build()

        then:
        config.timeoutSeconds() == RunConfig.DEFAULT_TIMEOUT_SECONDS
    }

    def "should ignore negative timeout"() {
        when:
        def config = RunConfig.builder()
            .task("test")
            .timeoutSeconds(-1)
            .build()

        then:
        config.timeoutSeconds() == RunConfig.DEFAULT_TIMEOUT_SECONDS
    }

    def "should set empty string model to default"() {
        when:
        def config = RunConfig.builder()
            .task("test")
            .model("")
            .build()

        then:
        config.model() == ""
    }

    // ========================================================================
    // Routine fields
    // ========================================================================

    def "should set routineCron via builder"() {
        when:
        def config = RunConfig.builder()
            .task("test")
            .routineCron("0 */2 * * * ?")
            .build()

        then:
        config.routineCron() == "0 */2 * * * ?"
        !config.listRoutines()
    }

    def "should set listRoutines via builder"() {
        when:
        def config = RunConfig.builder()
            .task("test")
            .listRoutines(true)
            .build()

        then:
        config.listRoutines()
        config.routineCron() == null
    }

    def "should set both routineCron and listRoutines"() {
        when:
        def config = RunConfig.builder()
            .task("test")
            .routineCron("0 */2 * * * ?")
            .listRoutines(true)
            .build()

        then:
        config.routineCron() == "0 */2 * * * ?"
        config.listRoutines()
    }

    def "routineCron defaults to null"() {
        when:
        def config = RunConfig.builder()
            .task("test")
            .build()

        then:
        config.routineCron() == null
        !config.listRoutines()
    }

    def "toString should not contain routineCron when not set"() {
        given:
        def config = RunConfig.builder()
            .task("test")
            .build()

        when:
        def str = config.toString()

        then:
        !str.contains("routineCron")
    }

    // ========================================================================
    // Tools fields
    // ========================================================================

    def "should set listTools via builder"() {
        when:
        def config = RunConfig.builder()
            .task("tools")
            .listTools(true)
            .build()

        then:
        config.listTools()
        !config.toolDetail()
    }

    def "should set toolDetail via builder"() {
        when:
        def config = RunConfig.builder()
            .task("tools")
            .listTools(true)
            .toolDetail(true)
            .build()

        then:
        config.listTools()
        config.toolDetail()
    }

    def "listTools defaults to false"() {
        when:
        def config = RunConfig.builder()
            .task("test")
            .build()

        then:
        !config.listTools()
        !config.toolDetail()
    }

    def "toString contains listTools when set"() {
        given:
        def config = RunConfig.builder()
            .task("tools")
            .listTools(true)
            .build()

        when:
        def str = config.toString()

        then:
        str.contains("listTools=true")
    }

    def "toString contains toolDetail when set"() {
        given:
        def config = RunConfig.builder()
            .task("tools")
            .listTools(true)
            .toolDetail(true)
            .build()

        when:
        def str = config.toString()

        then:
        str.contains("toolDetail=true")
    }

    // ========================================================================
    // Config fields
    // ========================================================================

    def "should set configAction via builder"() {
        when:
        def config = RunConfig.builder()
            .task("config")
            .configAction("show")
            .build()

        then:
        config.configAction() == "show"
        config.configKey() == null
        config.configValue() == null
    }

    def "should set config get key via builder"() {
        when:
        def config = RunConfig.builder()
            .task("config")
            .configAction("get")
            .configKey("openai.api_key")
            .build()

        then:
        config.configAction() == "get"
        config.configKey() == "openai.api_key"
        config.configValue() == null
    }

    def "should set config set key+value via builder"() {
        when:
        def config = RunConfig.builder()
            .task("config")
            .configAction("set")
            .configKey("openai.api_key")
            .configValue("sk-xxx")
            .build()

        then:
        config.configAction() == "set"
        config.configKey() == "openai.api_key"
        config.configValue() == "sk-xxx"
    }

    def "configAction defaults to null"() {
        when:
        def config = RunConfig.builder()
            .task("test")
            .build()

        then:
        config.configAction() == null
        config.configKey() == null
        config.configValue() == null
    }

    def "toString contains configAction when set"() {
        given:
        def config = RunConfig.builder()
            .task("config")
            .configAction("show")
            .build()

        when:
        def str = config.toString()

        then:
        str.contains("configAction='show'")
    }

    def "toString contains configKey when set"() {
        given:
        def config = RunConfig.builder()
            .task("config")
            .configAction("get")
            .configKey("openai.api_key")
            .build()

        when:
        def str = config.toString()

        then:
        str.contains("configKey='openai.api_key'")
    }

    def "toString contains configValue when set"() {
        given:
        def config = RunConfig.builder()
            .task("config")
            .configAction("set")
            .configKey("openai.api_key")
            .configValue("sk-xxx")
            .build()

        when:
        def str = config.toString()

        then:
        str.contains("configValue='sk-xxx'")
    }

    // ========================================================================
    // sub-agent builder fields
    // ========================================================================

    def "should set subAgentSpawnGoal via builder"() {
        when:
        def config = RunConfig.builder()
            .task("sub-agent")
            .subAgentSpawnGoal("analyze logs")
            .build()

        then:
        config.subAgentSpawnGoal() == "analyze logs"
        config.subAgentConnectName() == null
        config.subAgentConnectEndpoint() == null
        !config.subAgentList()
    }

    def "should set subAgentConnectName and endpoint via builder"() {
        when:
        def config = RunConfig.builder()
            .task("sub-agent")
            .subAgentConnectName("worker1")
            .subAgentConnectEndpoint("http://localhost:8080")
            .build()

        then:
        config.subAgentConnectName() == "worker1"
        config.subAgentConnectEndpoint() == "http://localhost:8080"
    }

    def "should set subAgentList via builder"() {
        when:
        def config = RunConfig.builder()
            .task("sub-agent")
            .subAgentList(true)
            .build()

        then:
        config.subAgentList()
    }

    def "should set subAgentCancelId via builder"() {
        when:
        def config = RunConfig.builder()
            .task("sub-agent")
            .subAgentCancelId("abc-123")
            .build()

        then:
        config.subAgentCancelId() == "abc-123"
    }

    def "should set subAgentResultsId via builder"() {
        when:
        def config = RunConfig.builder()
            .task("sub-agent")
            .subAgentResultsId("def-456")
            .build()

        then:
        config.subAgentResultsId() == "def-456"
    }

    def "should set subAgentSendId and subAgentSendMessage via builder"() {
        when:
        def config = RunConfig.builder()
            .task("sub-agent")
            .subAgentSendId("agent1")
            .subAgentSendMessage("hello")
            .build()

        then:
        config.subAgentSendId() == "agent1"
        config.subAgentSendMessage() == "hello"
    }

    def "should set subAgentPromptAgentId and subAgentPromptText via builder"() {
        when:
        def config = RunConfig.builder()
            .task("sub-agent")
            .subAgentPromptAgentId("ext-agent")
            .subAgentPromptText("do task")
            .build()

        then:
        config.subAgentPromptAgentId() == "ext-agent"
        config.subAgentPromptText() == "do task"
    }

    def "subAgent fields default to null/false"() {
        when:
        def config = RunConfig.builder()
            .task("run")
            .build()

        then:
        config.subAgentSpawnGoal() == null
        config.subAgentConnectName() == null
        config.subAgentConnectEndpoint() == null
        !config.subAgentList()
        config.subAgentCancelId() == null
        config.subAgentResultsId() == null
        config.subAgentSendId() == null
        config.subAgentSendMessage() == null
        config.subAgentPromptAgentId() == null
        config.subAgentPromptText() == null
    }

    def "toString contains subAgentSpawnGoal when set"() {
        given:
        def config = RunConfig.builder()
            .task("sub-agent")
            .subAgentSpawnGoal("test goal")
            .build()

        when:
        def str = config.toString()

        then:
        str.contains("subAgentSpawnGoal='test goal'")
    }

    def "toString contains subAgentConnectName when set"() {
        given:
        def config = RunConfig.builder()
            .task("sub-agent")
            .subAgentConnectName("worker1")
            .build()

        when:
        def str = config.toString()

        then:
        str.contains("subAgentConnectName='worker1'")
    }
}
