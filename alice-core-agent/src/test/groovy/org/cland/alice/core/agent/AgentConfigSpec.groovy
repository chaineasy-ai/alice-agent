package org.cland.alice.core.agent

import spock.lang.Specification

class AgentConfigSpec extends Specification {

    def "should use defaults"() {
        when:
        def config = AgentConfig.defaults()

        then:
        config.defaultModelId() == "gpt-4o-mini"
        config.maxIterations() == 10
        config.actionTimeoutMs() == 30_000
        config.preVerifyEnabled()
        config.postVerifyEnabled()
        !config.debug()
    }

    def "should customise configuration"() {
        when:
        def config = AgentConfig.builder()
            .defaultModelId("gpt-4")
            .maxIterations(5)
            .actionTimeoutMs(60_000)
            .preVerifyEnabled(false)
            .debug(true)
            .build()

        then:
        config.defaultModelId() == "gpt-4"
        config.maxIterations() == 5
        config.actionTimeoutMs() == 60_000
        !config.preVerifyEnabled()
        config.postVerifyEnabled()
        config.debug()
    }
}
