package org.cland.alice.facade.cmd.config

import spock.lang.Specification
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class AliceConfigStoreSpec extends Specification {

    Path tempDir
    Path configPath
    AliceConfigStore store

    def setup() {
        tempDir = Files.createTempDirectory("alice-config-test-")
        configPath = tempDir.resolve("config.json")
        store = new AliceConfigStore(configPath)
    }

    def cleanup() {
        if (Files.exists(configPath)) {
            Files.deleteIfExists(configPath)
        }
        if (Files.exists(tempDir)) {
            Files.deleteIfExists(tempDir)
        }
    }

    def "should create store with empty state when file does not exist"() {
        when:
        def all = store.getAll()

        then:
        all.isEmpty()
        store.get("openai.api_key") == null
    }

    def "should set and get a value"() {
        when:
        store.set("openai.api_key", "sk-test123")
        def result = store.get("openai.api_key")

        then:
        result == "sk-test123"
        Files.exists(configPath)
    }

    def "should persist value to disk and reload"() {
        given:
        store.set("anthropic.api_key", "sk-ant-test")

        when:
        def store2 = new AliceConfigStore(configPath)
        def result = store2.get("anthropic.api_key")

        then:
        result == "sk-ant-test"
    }

    def "should delete a key"() {
        given:
        store.set("openai.api_key", "sk-test")

        when:
        def deleted = store.delete("openai.api_key")

        then:
        deleted
        store.get("openai.api_key") == null
        store.getAll().isEmpty()
    }

    def "delete returns false for non-existent key"() {
        expect:
        !store.delete("nonexistent.key")
    }

    def "should convert dot notation to underscore"() {
        expect:
        AliceConfigStore.normalizeKey("openai.api_key") == "openai_api_key"
        AliceConfigStore.normalizeKey("anthropic.api_key") == "anthropic_api_key"
        AliceConfigStore.normalizeKey("default.model") == "default_model"
        AliceConfigStore.normalizeKey("agent.max_iterations") == "agent_max_iterations"
    }

    def "should return all keys in getAll"() {
        given:
        store.set("openai.api_key", "sk-1")
        store.set("anthropic.api_key", "sk-2")

        when:
        def all = store.getAll()

        then:
        all.size() == 2
        all["openai_api_key"] == "sk-1"
        all["anthropic_api_key"] == "sk-2"
    }

    def "getAll returns unmodifiable map"() {
        given:
        store.set("openai.api_key", "sk-test")

        when:
        store.getAll().put("x", "y")

        then:
        thrown(UnsupportedOperationException)
    }

    def "should handle corrupt JSON gracefully"() {
        given:
        Files.writeString(configPath, "not valid json",
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

        when:
        def store2 = new AliceConfigStore(configPath)
        def all = store2.getAll()

        then:
        all.isEmpty()
        store2.get("openai.api_key") == null
    }

    def "should handle empty JSON object"() {
        given:
        Files.writeString(configPath, "{}",
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

        when:
        def store2 = new AliceConfigStore(configPath)
        def all = store2.getAll()

        then:
        all.isEmpty()
    }

    def "should persist to actual JSON file on set"() {
        when:
        store.set("openai.api_key", "sk-xxx")
        store.set("anthropic.api_key", "sk-yyy")
        def raw = new String(Files.readAllBytes(configPath))

        then:
        raw.contains("openai_api_key")
        raw.contains("sk-xxx")
        raw.contains("anthropic_api_key")
        raw.contains("sk-yyy")
    }

    // ========================================================================
    // Nested provider path tests
    // ========================================================================

    def "should store providers.openai.api_key as nested structure"() {
        when:
        store.set("providers.openai.api_key", "sk-nested")
        store.set("providers.openai.model", "gpt-4o")
        store.set("providers.openai.base_url", "https://api.openai.com/v1")

        then:
        store.get("providers.openai.api_key") == "sk-nested"
        store.get("providers.openai.model") == "gpt-4o"
        store.get("providers.openai.base_url") == "https://api.openai.com/v1"
    }

    def "should store nested provider as JSON nested object"() {
        when:
        store.set("providers.openai.api_key", "sk-nested")
        def raw = new String(Files.readAllBytes(configPath))

        then:
        raw.contains('"providers"')
        raw.contains('"openai"')
        raw.contains('"api_key"')
        raw.contains("sk-nested")
    }

    def "should read nested provider value from reloaded store"() {
        given:
        store.set("providers.openai.api_key", "sk-nested")
        store.set("providers.anthropic.api_key", "sk-ant-nested")

        when:
        def store2 = new AliceConfigStore(configPath)

        then:
        store2.get("providers.openai.api_key") == "sk-nested"
        store2.get("providers.anthropic.api_key") == "sk-ant-nested"
    }

    def "should delete nested provider key"() {
        given:
        store.set("providers.openai.api_key", "sk-nested")
        store.set("providers.openai.model", "gpt-4o")

        when:
        def deleted = store.delete("providers.openai.api_key")

        then:
        deleted
        store.get("providers.openai.api_key") == null
        store.get("providers.openai.model") == "gpt-4o"  // sibling preserved
    }

    def "getAll returns nested structure"() {
        given:
        store.set("providers.openai.api_key", "sk-nested")
        store.set("providers.openai.model", "gpt-4o")

        when:
        def all = store.getAll()

        then:
        all.containsKey("providers")
        // deep copy is unmodifiable
        all.get("providers") instanceof Map
    }

    def "should keep flat keys for known 2-segment namespaces"() {
        when:
        store.set("default.timeout", "180")
        store.set("openai.api_key", "sk-flat")

        then:
        store.get("default.timeout") == "180"
        store.get("openai.api_key") == "sk-flat"
        // stored as flat underscore keys
        !new String(Files.readAllBytes(configPath)).contains('"default"')
    }

    def "should support both flat and nested provider keys simultaneously"() {
        when:
        store.set("openai.api_key", "sk-flat")
        store.set("providers.openai.api_key", "sk-nested")

        then:
        store.get("openai.api_key") == "sk-flat"
        store.get("providers.openai.api_key") == "sk-nested"

        when:
        def raw = new String(Files.readAllBytes(configPath))

        then:
        raw.contains("openai_api_key")
        raw.contains('"providers"')
        raw.contains('"openai"')
        raw.contains('"api_key"')
    }

    def "should handle 3-segment keys not starting with providers"() {
        when:
        store.set("a.b.c", "val")

        then:  // 3 segments → nested
        store.get("a.b.c") == "val"
        def raw = new String(Files.readAllBytes(configPath))
        raw.contains('"a"')
        raw.contains('"b"')
        raw.contains('"c"')
    }

    def "should handle splitKey for various formats"() {
        expect:
        AliceConfigStore.splitKey("openai.api_key") == ["openai_api_key"]
        AliceConfigStore.splitKey("providers.openai.api_key") == ["providers", "openai", "api_key"]
        AliceConfigStore.splitKey("providers.anthropic.model") == ["providers", "anthropic", "model"]
        AliceConfigStore.splitKey("max_iterations") == ["max_iterations"]
        AliceConfigStore.splitKey("default.timeout") == ["default_timeout"]
        AliceConfigStore.splitKey("default.model") == ["default_model"]
        AliceConfigStore.splitKey("a.b.c.d") == ["a", "b", "c", "d"]
    }

    def "delete on nested path should persist"() {
        given:
        store.set("providers.openai.api_key", "sk-nested")
        store.set("providers.openai.model", "gpt-4o")
        store.set("default.timeout", "180")

        when:
        store.delete("providers.openai.api_key")
        def store2 = new AliceConfigStore(configPath)

        then:
        store2.get("providers.openai.api_key") == null
        store2.get("providers.openai.model") == "gpt-4o"
        store2.get("default.timeout") == "180"
    }
}
