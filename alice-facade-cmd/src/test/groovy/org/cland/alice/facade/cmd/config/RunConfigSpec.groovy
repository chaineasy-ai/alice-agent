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
}
