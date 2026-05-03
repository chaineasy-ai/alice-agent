package org.cland.alice.model

import spock.lang.Specification

class ModelSpec extends Specification {

    def "should create Model with all fields"() {
        when:
        def model = Model.builder()
            .modelId("gpt-4o")
            .supplierName("openai")
            .capability(Model.Capability.ALL)
            .pricing(new Model.Pricing(2.50, 10.00))
            .build()

        then:
        model.modelId() == "gpt-4o"
        model.supplierName() == "openai"
        model.capability() == Model.Capability.ALL
        model.pricing().inputPer1K() == 2.50
        model.pricing().outputPer1K() == 10.00
        model.config().isEmpty()
    }

    def "should create Model with config"() {
        given:
        def config = ["temperature": 0.7, "maxTokens": 4096]

        when:
        def model = Model.builder()
            .modelId("claude-3-5-sonnet-latest")
            .supplierName("anthropic")
            .config(config)
            .build()

        then:
        model.config()["temperature"] == 0.7
        model.config()["maxTokens"] == 4096
    }

    def "should reject null modelId"() {
        when:
        Model.builder().modelId(null).build()

        then:
        thrown(NullPointerException)
    }

    def "should reject null supplierName"() {
        when:
        Model.builder().modelId("gpt-4o").supplierName(null).build()

        then:
        thrown(NullPointerException)
    }

    def "default capability should be NONE"() {
        when:
        def model = Model.builder()
            .modelId("gpt-4o")
            .supplierName("openai")
            .build()

        then:
        model.capability() == Model.Capability.NONE
    }

    def "default pricing should be ZERO"() {
        when:
        def model = Model.builder()
            .modelId("gpt-4o")
            .supplierName("openai")
            .build()

        then:
        model.pricing() == Model.Pricing.ZERO
    }

    def "Capability supports() should work for matching capabilities"() {
        expect:
        Model.Capability.ALL.supports(Model.Capability.FUNCTION_CALL)
        Model.Capability.ALL.supports(Model.Capability.VISION)
        Model.Capability.ALL.supports(Model.Capability.STREAMING)
        Model.Capability.FUNCTION_CALL.supports(Model.Capability.FUNCTION_CALL)
    }

    def "Capability supports() should return false for unsupported capabilities"() {
        expect:
        !Model.Capability.NONE.supports(Model.Capability.FUNCTION_CALL)
        !Model.Capability.FUNCTION_CALL.supports(Model.Capability.VISION)
    }

    def "Pricing.estimateCost should calculate correctly"() {
        given:
        def pricing = new Model.Pricing(2.50, 10.00)

        expect:
        pricing.estimateCost(1000, 500) == 2.50 * 1 + 10.00 * 0.5  // $2.50 + $5.00 = $7.50
    }

    def "Pricing.estimateCost with zero tokens"() {
        given:
        def pricing = new Model.Pricing(2.50, 10.00)

        expect:
        pricing.estimateCost(0, 0) == 0.0
    }

    def "Capability fromMask should return correct enum"() {
        expect:
        Model.Capability.fromMask(0) == Model.Capability.NONE
        Model.Capability.fromMask(1) == Model.Capability.FUNCTION_CALL
        Model.Capability.fromMask(2) == Model.Capability.VISION
        Model.Capability.fromMask(4) == Model.Capability.STREAMING
        Model.Capability.fromMask(7) == Model.Capability.ALL
    }

    def "toString should return readable representation"() {
        given:
        def model = Model.builder()
            .modelId("gpt-4o")
            .supplierName("openai")
            .build()

        expect:
        model.toString() == "Model{modelId='gpt-4o', supplier='openai'}"
    }
}
