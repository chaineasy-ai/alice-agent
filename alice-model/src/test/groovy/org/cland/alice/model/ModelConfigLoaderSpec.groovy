/*
 * Tests for ModelConfigLoader.
 *
 * Covers:
 * - Config file parsing
 * - Environment variable expansion
 * - Config validation rules
 * - DeepSeek / OpenAI / Local provider parsing
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

    def "loads config with single provider"() {
        given:
        def configPath = writeConfig("""
            {
                "language_models": {
                    "openai_compatible": {
                        "deepseek": {
                            "base_url": "https://api.deepseek.com/v1",
                            "api_key": "\${DEEPSEEK_API_KEY}",
                            "available_models": [
                                {
                                    "name": "deepseek-chat",
                                    "max_tokens": 200000,
                                    "max_output_tokens": 32000,
                                    "max_completion_tokens": 200000,
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
            }
        """)

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then:
        loader.getProviders().size() == 1

        and:
        def deepseek = loader.getProvider("deepseek")
        deepseek != null
        deepseek.name() == "deepseek"
        deepseek.apiUrl() == "https://api.deepseek.com/v1"
        // api_key with ${ENV_VAR} is expanded to the env var value (if set)
        def expectedKey = System.getenv("DEEPSEEK_API_KEY") ?: '\${DEEPSEEK_API_KEY}'
        deepseek.apiKey() == expectedKey

        and:
        deepseek.models().size() == 1
        def model = deepseek.models()[0]
        model.name() == "deepseek-chat"
        model.maxTokens() == 200000
        model.maxOutputTokens() == 32000
        model.maxCompletionTokens() == 200000
        model.capabilities().get("tools") == true
        model.capabilities().get("images") == false
        model.capabilities().get("parallel_tool_calls") == true
        model.capabilities().get("prompt_cache_key") == true
        model.capabilities().get("chat_completions") == true
    }

    def "loads multiple providers"() {
        given:
        def configPath = writeConfig("""
            {
                "language_models": {
                    "openai_compatible": {
                        "openai": {
                            "base_url": "https://api.openai.com/v1",
                            "api_key": "\${OPENAI_API_KEY}",
                            "available_models": [
                                {
                                    "name": "gpt-4o",
                                    "max_tokens": 128000,
                                    "max_output_tokens": 16384,
                                    "max_completion_tokens": 128000,
                                    "capabilities": {
                                        "tools": true,
                                        "images": true,
                                        "parallel_tool_calls": true,
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
                                    "name": "deepseek-chat",
                                    "max_tokens": 200000,
                                    "max_output_tokens": 32000,
                                    "max_completion_tokens": 200000,
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
            }
        """)

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then:
        loader.getProviders().size() == 2
        loader.getProvider("openai") != null
        loader.getProvider("deepseek") != null
    }

    def "loads local provider without api_key"() {
        given:
        def configPath = writeConfig("""
            {
                "language_models": {
                    "openai_compatible": {
                        "local": {
                            "base_url": "http://localhost:8080/v1",
                            "available_models": [
                                {
                                    "name": "llama3",
                                    "max_tokens": 8192,
                                    "max_output_tokens": 2048,
                                    "max_completion_tokens": 8192,
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
            }
        """)

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then:
        def local = loader.getProvider("local")
        local != null
        local.apiUrl() == "http://localhost:8080/v1"
        local.apiKey() == null
        local.models().size() == 1
        local.models()[0].name() == "llama3"
    }

    def "returns empty list when config file does not exist"() {
        given:
        def nonExistentPath = tempDir.resolve("nonexistent.json")

        when:
        def loader = new ModelConfigLoader(nonExistentPath)
        loader.load()

        then:
        loader.getProviders().isEmpty()
        noExceptionThrown()
    }

    def "returns empty list when language_models section is missing"() {
        given:
        def configPath = writeConfig('{ "other_section": {} }')

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then:
        loader.getProviders().isEmpty()
    }

    def "returns empty list when openai_compatible section is missing"() {
        given:
        def configPath = writeConfig('{ "language_models": {} }')

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then:
        loader.getProviders().isEmpty()
    }

    def "skips provider with invalid api_url"() {
        given:
        def configPath = writeConfig("""
            {
                "language_models": {
                    "openai_compatible": {
                        "bad_provider": {
                            "base_url": "not-a-url",
                            "available_models": [
                                {
                                    "name": "test-model",
                                    "max_tokens": 4096,
                                    "max_output_tokens": 2048,
                                    "max_completion_tokens": 4096,
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
            }
        """)

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then:
        loader.getProviders().isEmpty()
    }

    def "expands environment variable in api_key"() {
        expect:
        ModelConfigLoader.expandEnvVar('${TEST_MODEL_KEY}') == System.getenv("TEST_MODEL_KEY") ?: '${TEST_MODEL_KEY}'
        ModelConfigLoader.expandEnvVar("literal-key") == "literal-key"
        ModelConfigLoader.expandEnvVar(null) == null
    }

    def "validates max_tokens >= max_output_tokens"() {
        given:
        def configPath = writeConfig("""
            {
                "language_models": {
                    "openai_compatible": {
                        "test": {
                            "base_url": "https://api.test.com/v1",
                            "api_key": "test-key",
                            "available_models": [
                                {
                                    "name": "test-model",
                                    "max_tokens": 1000,
                                    "max_output_tokens": 2000,
                                    "max_completion_tokens": 3000,
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
            }
        """)

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then:
        def model = loader.getProvider("test").models()[0]
        // max_tokens should be adjusted to at least max_output_tokens
        model.maxTokens() >= model.maxOutputTokens()
        // max_completion_tokens stays as is since it's already > max_tokens
        model.maxCompletionTokens() == 3000
    }

    def "registerTo populates ModelProvider"() {
        given:
        def configPath = writeConfig("""
            {
                "language_models": {
                    "openai_compatible": {
                        "deepseek": {
                            "base_url": "https://api.deepseek.com/v1",
                            "api_key": "\${DEEPSEEK_API_KEY}",
                            "available_models": [
                                {
                                    "name": "deepseek-chat",
                                    "max_tokens": 200000,
                                    "max_output_tokens": 32000,
                                    "max_completion_tokens": 200000,
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
            }
        """)
        def loader = new ModelConfigLoader(configPath)
        loader.load()
        def provider = ModelProvider.getInstance()
        provider.reset()

        when:
        loader.registerTo(provider)

        then:
        // getSupplier looks up by modelId, which routes to the supplier via the router
        provider.getSupplier("deepseek-chat") != null
        provider.getModel("deepseek-chat") != null
        provider.getModel("deepseek-chat").modelId() == "deepseek-chat"
        provider.getModel("deepseek-chat").supplierName() == "deepseek"

        cleanup:
        provider.reset()
    }

    def "handles null models section gracefully"() {
        given:
        def configPath = writeConfig("""
            {
                "language_models": {
                    "openai_compatible": {
                        "empty_provider": {
                            "base_url": "https://api.test.com/v1"
                        }
                    }
                }
            }
        """)

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then:
        // Provider without models is skipped
        loader.getProviders().isEmpty()
    }

    def "parses capabilities with default values"() {
        given:
        def configPath = writeConfig("""
            {
                "language_models": {
                    "openai_compatible": {
                        "test": {
                            "base_url": "https://api.test.com/v1",
                            "available_models": [
                                {
                                    "name": "minimal-model",
                                    "max_tokens": 4096,
                                    "max_output_tokens": 2048,
                                    "max_completion_tokens": 4096,
                                    "capabilities": {
                                        "tools": true
                                    }
                                }
                            ]
                        }
                    }
                }
            }
        """)

        when:
        def loader = new ModelConfigLoader(configPath)
        loader.load()

        then:
        def model = loader.getProvider("test").models()[0]
        model.capabilities().get("tools") == true
        // Defaults
        model.capabilities().get("images") == false
        model.capabilities().get("parallel_tool_calls") == false
        model.capabilities().get("prompt_cache_key") == false
        model.capabilities().get("chat_completions") == true
    }
}
