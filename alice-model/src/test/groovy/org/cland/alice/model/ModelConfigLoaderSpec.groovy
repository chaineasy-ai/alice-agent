/*
 * Tests for ModelConfigLoader — Jackson-based, new format only.
 *
 * Config:
 *   default_model { provider, model, enable_thinking, reasoning_effort }
 *   planner { instruction_model_id, reasoning_model_id, instruction{}, reasoning{} }
 *   providers: <name> { base_url, api_key, available_models[{ name, model, max_tokens, ... }] }
 */
package org.cland.alice.model

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ModelConfigLoaderSpec extends Specification {

    @TempDir
    Path tempDir

    private Path writeConfig(String content) {
        def configPath = tempDir.resolve("model.json")
        Files.writeString(configPath, content.stripIndent())
        return configPath
    }

    // ==================== Core ====================

    def "loads config with single provider and model"() {
        given:
        def configPath = writeConfig("""
            {
                "default_model": {
                    "provider": "deepseek",
                    "model": "deepseek-v4-flash",
                    "enable_thinking": true,
                    "reasoning_effort": "high"
                },
                "providers": {
                    "deepseek": {
                        "base_url": "https://api.deepseek.com/v1",
                        "api_key": "\${DEEPSEEK_API_KEY}",
                        "available_models": [
                            {
                                "name": "deepseek-v4-flash",
                                "model": "deepseek-v4-flash",
                                "max_tokens": 131072,
                                "max_output_tokens": 32000,
                                "capabilities": {
                                    "tools": true,
                                    "images": false,
                                    "parallel_tool_calls": true,
                                    "prompt_cache_key": true,
                                    "chat_completions": true
                                }
                            }
                        ]
                    }
                }
            }
        """)

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then: "default model"
        loader.getDefaultModelConfig() != null
        loader.getDefaultModelConfig().provider() == "deepseek"
        loader.getDefaultModelConfig().model() == "deepseek-v4-flash"
        loader.getDefaultModelConfig().enableThinking() == true
        loader.getDefaultModelConfig().reasoningEffort() == "high"
        loader.getDefaultModel() == "deepseek-v4-flash"

        and: "providers"
        loader.getProviders().size() == 1
        def pe = loader.getProviders().get("deepseek")
        pe.baseUrl() == "https://api.deepseek.com/v1"
        pe.availableModels().size() == 1
        pe.availableModels()[0].name() == "deepseek-v4-flash"

        and: "model pool (aggregated)"
        loader.getModelPool().size() == 1
        def m = loader.getModelPoolEntry("deepseek-v4-flash")
        m.name() == "deepseek-v4-flash"
        m.maxTokens() == 131072
        m.capabilities().get("tools") == true
    }

    def "loads config with planner section"() {
        given:
        def configPath = writeConfig("""
            {
                "default_model": {
                    "provider": "deepseek",
                    "model": "deepseek-v4-flash"
                },
                "planner": {
                    "instruction_model_id": "deepseek-v4-flash",
                    "reasoning_model_id": "deepseek-v4-flash",
                    "instruction": {
                        "enable_thinking": false,
                        "reasoning_effort": "low"
                    },
                    "reasoning": {
                        "enable_thinking": true,
                        "reasoning_effort": "high"
                    }
                },
                "providers": {
                    "deepseek": {
                        "base_url": "https://api.deepseek.com/v1",
                        "available_models": [
                            {
                                "name": "deepseek-v4-flash",
                                "model": "deepseek-v4-flash",
                                "max_tokens": 131072,
                                "max_output_tokens": 32000,
                                "capabilities": {
                                    "tools": true,
                                    "images": false,
                                    "parallel_tool_calls": true,
                                    "prompt_cache_key": true,
                                    "chat_completions": true
                                }
                            }
                        ]
                    }
                }
            }
        """)

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then:
        loader.getPlannerConfig() != null
        loader.getPlannerConfig().instructionModelId() == "deepseek-v4-flash"
        loader.getPlannerConfig().reasoningModelId() == "deepseek-v4-flash"
        loader.getPlannerConfig().instruction().enableThinking() == false
        loader.getPlannerConfig().instruction().reasoningEffort() == "low"
        loader.getPlannerConfig().reasoning().enableThinking() == true
        loader.getPlannerConfig().reasoning().reasoningEffort() == "high"
    }

    def "planner config defaults when omitted"() {
        given:
        def configPath = writeConfig("""
            {
                "default_model": {
                    "provider": "deepseek",
                    "model": "deepseek-v4-flash"
                },
                "providers": {
                    "deepseek": {
                        "base_url": "https://api.deepseek.com/v1",
                        "available_models": [
                            {
                                "name": "deepseek-v4-flash",
                                "model": "deepseek-v4-flash",
                                "max_tokens": 131072,
                                "max_output_tokens": 32000,
                                "capabilities": {
                                    "tools": true,
                                    "images": false,
                                    "parallel_tool_calls": true,
                                    "prompt_cache_key": true,
                                    "chat_completions": true
                                }
                            }
                        ]
                    }
                }
            }
        """)

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then:
        loader.getPlannerConfig() == null
    }

    def "multiple providers"() {
        given:
        def configPath = writeConfig("""
            {
                "default_model": {
                    "provider": "openai",
                    "model": "o3-mini"
                },
                "providers": {
                    "openai": {
                        "base_url": "https://api.openai.com/v1",
                        "api_key": "\${OPENAI_API_KEY}",
                        "available_models": [
                            {
                                "name": "o3-mini",
                                "model": "o3-mini",
                                "max_tokens": 128000,
                                "max_output_tokens": 16000,
                                "capabilities": {
                                    "tools": true,
                                    "images": false,
                                    "parallel_tool_calls": false,
                                    "prompt_cache_key": false,
                                    "chat_completions": true
                                }
                            }
                        ]
                    },
                    "deepseek": {
                        "base_url": "https://api.deepseek.com/v1",
                        "api_key": "\${DEEPSEEK_API_KEY}",
                        "available_models": [
                            {
                                "name": "deepseek-v4-flash",
                                "model": "deepseek-v4-flash",
                                "max_tokens": 131072,
                                "max_output_tokens": 32000,
                                "capabilities": {
                                    "tools": true,
                                    "images": false,
                                    "parallel_tool_calls": true,
                                    "prompt_cache_key": true,
                                    "chat_completions": true
                                }
                            }
                        ]
                    }
                }
            }
        """)

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then:
        loader.getProviders().size() == 2
        loader.getModelPool().size() == 2
    }

    def "local provider without api_key"() {
        given:
        def configPath = writeConfig("""
            {
                "default_model": {
                    "provider": "local",
                    "model": "llama3"
                },
                "providers": {
                    "local": {
                        "base_url": "http://localhost:8080/v1",
                        "available_models": [
                            {
                                "name": "llama3",
                                "model": "llama3",
                                "max_tokens": 8192,
                                "max_output_tokens": 2048,
                                "capabilities": {
                                    "tools": false,
                                    "images": false,
                                    "parallel_tool_calls": false,
                                    "prompt_cache_key": false,
                                    "chat_completions": true
                                }
                            }
                        ]
                    }
                }
            }
        """)

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then:
        def pe = loader.getProviders().get("local")
        pe.apiKey() == null
        pe.availableModels().size() == 1
    }

    // ==================== Edge cases ====================

    def "missing config file"() {
        given:
        def p = tempDir.resolve("nonexistent.json")

        when:
        def loader = new ModelConfigLoader(p)
        loader.load()

        then:
        loader.getDefaultModel() == null
        loader.getModelPool().isEmpty()
        loader.getProviders().isEmpty()
        loader.getPlannerConfig() == null
        noExceptionThrown()
    }

    def "env var expansion"() {
        expect:
        ModelConfigLoader.expandEnvVar('${TEST_KEY}') == System.getenv("TEST_KEY") ?: '${TEST_KEY}'
        ModelConfigLoader.expandEnvVar("plain") == "plain"
        ModelConfigLoader.expandEnvVar(null) == null
    }

    // ==================== registerTo ====================

    def "registerTo populates ModelProvider"() {
        given:
        def configPath = writeConfig("""
            {
                "default_model": {
                    "provider": "deepseek",
                    "model": "deepseek-v4-flash"
                },
                "providers": {
                    "deepseek": {
                        "base_url": "https://api.deepseek.com/v1",
                        "api_key": "\${DEEPSEEK_API_KEY}",
                        "available_models": [
                            {
                                "name": "deepseek-v4-flash",
                                "model": "deepseek-v4-flash",
                                "max_tokens": 131072,
                                "max_output_tokens": 32000,
                                "capabilities": {
                                    "tools": true,
                                    "images": false,
                                    "parallel_tool_calls": true,
                                    "prompt_cache_key": true,
                                    "chat_completions": true
                                }
                            }
                        ]
                    }
                }
            }
        """)
        def loader = new ModelConfigLoader(configPath)
        loader.load()
        def provider = ModelProvider.getInstance()
        provider.reset()

        when:
        loader.registerTo(provider)

        then:
        provider.getSupplier("deepseek-v4-flash") != null
        provider.getModel("deepseek-v4-flash") != null
        provider.getModel("deepseek-v4-flash").modelId() == "deepseek-v4-flash"
        provider.getModel("deepseek-v4-flash").supplierName() == "deepseek"

        cleanup:
        provider.reset()
    }
}
