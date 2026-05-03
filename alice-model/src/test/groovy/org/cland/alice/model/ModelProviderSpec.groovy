package org.cland.alice.model

import org.cland.alice.model.common.ModelEnum
import spock.lang.Specification

class ModelProviderSpec extends Specification {

    def cleanup() {
        ModelProvider.reset()
    }

    def "should be a singleton"() {
        when:
        def instance1 = ModelProvider.getInstance()
        def instance2 = ModelProvider.getInstance()

        then:
        instance1.is(instance2)
    }

    def "should register and retrieve a supplier"() {
        given:
        def provider = ModelProvider.getInstance()
        def supplier = new ModelSupplier() {
            @Override
            String name() { return "test-vendor" }
            @Override
            Call.Response request(Call call) {
                return new Call.Response("test", null, [:])
            }
        }

        when:
        provider.registerSupplier(supplier)
        def model = Model.builder()
            .modelId("test-model")
            .supplierName("test-vendor")
            .build()
        provider.registerModel(model)

        then:
        provider.getSupplier("test-model") == supplier
    }

    def "should register builtin models"() {
        given:
        def provider = ModelProvider.getInstance()

        when:
        provider.registerBuiltinModels()

        then:
        provider.getModel("gpt-4o") != null
        provider.getModel("gpt-4o").supplierName() == "openai"
        provider.getModel("claude-3-5-sonnet-latest") != null
        provider.getModel("claude-3-5-sonnet-latest").supplierName() == "anthropic"
        provider.getModel("deepseek-chat") != null
        provider.getModel("deepseek-chat").supplierName() == "deepseek"
    }

    def "get(ModelEnum) should return null if supplier not registered"() {
        given:
        def provider = ModelProvider.getInstance()
        provider.registerBuiltinModels()

        expect:
        provider.getSupplier(ModelEnum.GPT_4O.modelId()) == null
    }

    def "get(ModelEnum) should return supplier after registration"() {
        given:
        def provider = ModelProvider.getInstance()
        def supplier = new ModelSupplier() {
            @Override String name() { return "openai" }
            @Override Call.Response request(Call call) {
                return new Call.Response("test", null, [:])
            }
        }
        provider.registerBuiltinModels()
        provider.registerSupplier(supplier)

        expect:
        provider.getSupplier(ModelEnum.GPT_4O.modelId()).is(supplier)
    }

    def "dispatch should execute call and return finished Call"() {
        given:
        def provider = ModelProvider.getInstance()
        def supplier = new ModelSupplier() {
            @Override String name() { return "openai" }
            @Override Call.Response request(Call call) {
                return new Call.Response("Mock response", null, [:])
            }
        }
        provider.registerSupplier(supplier)
        provider.registerModel(Model.builder()
            .modelId("gpt-4o")
            .supplierName("openai")
            .build())

        when:
        def call = provider.dispatch("gpt-4o", "Hello!")

        then:
        call.status() == CallStatus.FINISHED
        call.payload().modelId() == "gpt-4o"
        call.payload().prompt() == "Hello!"
        call.result().content() == "Mock response"
        call.metrics().latencyMs() >= 0
    }

    def "dispatch should throw if no supplier found"() {
        given:
        def provider = ModelProvider.getInstance()

        when:
        provider.dispatch("unknown-model", "Hello!")

        then:
        thrown(IllegalStateException)
    }

    def "dispatch should throw and mark call as FAILED on supplier error"() {
        given:
        def provider = ModelProvider.getInstance()
        def supplier = new ModelSupplier() {
            @Override String name() { return "openai" }
            @Override Call.Response request(Call call) {
                throw new RuntimeException("API error")
            }
        }
        provider.registerSupplier(supplier)
        provider.registerModel(Model.builder()
            .modelId("gpt-4o")
            .supplierName("openai")
            .build())

        when:
        provider.dispatch("gpt-4o", "Hello!")

        then:
        def e = thrown(RuntimeException)
        e.message.contains("Model call failed")
    }

    def "custom router should override default routing"() {
        given:
        def provider = ModelProvider.getInstance()
        def supplier1 = new ModelSupplier() {
            @Override String name() { return "vendor-a" }
            @Override Call.Response request(Call call) {
                return new Call.Response("a", null, [:])
            }
        }
        def supplier2 = new ModelSupplier() {
            @Override String name() { return "vendor-b" }
            @Override Call.Response request(Call call) {
                return new Call.Response("b", null, [:])
            }
        }
        provider.registerSupplier(supplier1)
        provider.registerSupplier(supplier2)
        provider.registerModel(Model.builder()
            .modelId("model-x")
            .supplierName("vendor-a")
            .build())

        when: "route all models to vendor-b"
        provider.setRouter({ modelId -> "vendor-b" })

        then:
        provider.getSupplier("model-x").is(supplier2)
    }

    def "reset should clear singleton"() {
        given:
        def provider1 = ModelProvider.getInstance()

        when:
        ModelProvider.reset()
        def provider2 = ModelProvider.getInstance()

        then:
        !provider1.is(provider2)
    }
}
