package org.cland.alice.model.common

import spock.lang.Specification

class ModelEnumSpec extends Specification {

    def "should have correct number of models"() {
        expect:
        ModelEnum.values().length == 15
    }

    def "GPT_4O should have correct metadata"() {
        given:
        def model = ModelEnum.GPT_4O

        expect:
        model.modelId() == "gpt-4o"
        model.supplierName() == "openai"
        model.capability() == ModelEnum.Capability.ALL
        model.inputPricePer1K() == 2.50
        model.outputPricePer1K() == 10.00
    }

    def "CLAUDE_3_5_SONNET should have correct metadata"() {
        given:
        def model = ModelEnum.CLAUDE_3_5_SONNET

        expect:
        model.modelId() == "claude-3-5-sonnet-latest"
        model.supplierName() == "anthropic"
        model.capability() == ModelEnum.Capability.ALL
        model.inputPricePer1K() == 3.00
        model.outputPricePer1K() == 15.00
    }

    def "DEEPSEEK_V3 should only have function call capability"() {
        given:
        def model = ModelEnum.DEEPSEEK_V3

        expect:
        model.capability() == ModelEnum.Capability.FC
        model.capability().supports(ModelEnum.Capability.FC)
        !model.capability().supports(ModelEnum.Capability.VISION)
        !model.capability().supports(ModelEnum.Capability.STREAMING)
    }

    def "fromModelId should find model by id"() {
        expect:
        ModelEnum.fromModelId("gpt-4o") == ModelEnum.GPT_4O
        ModelEnum.fromModelId("claude-3-5-sonnet-latest") == ModelEnum.CLAUDE_3_5_SONNET
        ModelEnum.fromModelId("deepseek-v4-flash") == ModelEnum.DEEPSEEK_V3
        ModelEnum.fromModelId("gemini-2.0-flash") == ModelEnum.GEMINI_2_0_FLASH
        ModelEnum.fromModelId("gemma-4") == ModelEnum.GEMMA_4
    }

    def "fromModelId should be case insensitive"() {
        expect:
        ModelEnum.fromModelId("GPT-4O") == ModelEnum.GPT_4O
        ModelEnum.fromModelId("Claude-3-5-Sonnet-Latest") == ModelEnum.CLAUDE_3_5_SONNET
    }

    def "fromModelId should throw for unknown model"() {
        when:
        ModelEnum.fromModelId("unknown-model")

        then:
        thrown(IllegalArgumentException)
    }

    def "Capability supports should work correctly"() {
        expect:
        ModelEnum.Capability.ALL.supports(ModelEnum.Capability.FC)
        ModelEnum.Capability.ALL.supports(ModelEnum.Capability.VISION)
        ModelEnum.Capability.ALL.supports(ModelEnum.Capability.STREAMING)
        ModelEnum.Capability.FC.supports(ModelEnum.Capability.FC)
        !ModelEnum.Capability.FC.supports(ModelEnum.Capability.VISION)
        !ModelEnum.Capability.NONE.supports(ModelEnum.Capability.NONE)  // 0 & 0 == 0
        !ModelEnum.Capability.NONE.supports(ModelEnum.Capability.FC)
    }

    def "Capability mask should produce correct bit values"() {
        expect:
        ModelEnum.Capability.NONE.mask() == 0
        ModelEnum.Capability.FC.mask() == 1
        ModelEnum.Capability.VISION.mask() == 2
        ModelEnum.Capability.STREAMING.mask() == 4
        ModelEnum.Capability.FC_VISION.mask() == 3
        ModelEnum.Capability.ALL.mask() == 7
    }
}
