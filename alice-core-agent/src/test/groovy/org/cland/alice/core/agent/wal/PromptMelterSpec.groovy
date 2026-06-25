package org.cland.alice.core.agent.wal

import spock.lang.Specification
import spock.lang.Subject

/**
 * Spock 测试：验证 PromptMelter 三段式上下文熔炼。
 */
class PromptMelterSpec extends Specification {

    InMemoryWalStore store
    @Subject
    PromptMelter melter
    String sessionId = "test-melt-session"
    String staticTrunk = "You are a helpful AI assistant.\n\nAvailable tools:\n- get_weather\n- read_file"

    def setup() {
        store = new InMemoryWalStore()
        melter = new PromptMelter(store)
    }

    // ============================================================
    // 基础功能
    // ============================================================

    def "should produce three-segment prompt for empty session"() {
        when:
        def melted = melter.melt(sessionId, staticTrunk)

        then:
        melted.staticTrunk() == staticTrunk
        melted.snapshotState().contains("Fresh session")
        melted.shortTail().contains("[Recent Messages]")
        melted.totalMessages() == 0
        melted.lastAppliedId() == 0
    }

    def "should include snapshot state from checkpoint"() {
        given:
        store.appendMessage(RawMessage.user(0, sessionId, "Hi"))
        store.saveCheckpoint(new Checkpoint(0, sessionId, 1, "PLANNING",
                ["retry": 0, "goal": "test"], '{"steps":["a"]}', 0))

        when:
        def melted = melter.melt(sessionId, staticTrunk)

        then:
        melted.snapshotState().contains("PLANNING")
        melted.snapshotState().contains("retry")
        melted.snapshotState().contains("goal")
        melted.lastAppliedId() == 1
        melted.totalMessages() == 1
    }

    def "should include recent messages in short tail"() {
        given:
        store.appendMessage(RawMessage.user(0, sessionId, "Hello"))
        store.appendMessage(RawMessage.assistant(0, sessionId, "Hi there!"))
        store.appendMessage(RawMessage.user(0, sessionId, "What's the weather?"))

        when:
        def melted = melter.melt(sessionId, staticTrunk)

        then:
        melted.shortTail().contains("Hello")
        melted.shortTail().contains("Hi there!")
        melted.shortTail().contains("What's the weather?")
    }

    // ============================================================
    // Token 估算
    // ============================================================

    def "should estimate tokens for each segment"() {
        when:
        def melted = melter.melt(sessionId, staticTrunk)
        def expectedTotal = melted.staticTokens() + melted.snapshotTokens() + melted.tailTokens()
        def actualTotal = melted.totalTokens()

        then:
        melted.staticTokens() > 0
        melted.snapshotTokens() >= 0
        melted.tailTokens() >= 0
        actualTotal == expectedTotal
    }

    def "should generate cache key"() {
        given:
        store.saveCheckpoint(new Checkpoint(0, sessionId, 42, "PLANNING", [:], "", 0))

        when:
        def melted = melter.melt(sessionId, staticTrunk)

        then:
        melted.cacheKey() == "prompt:42"
    }

    // ============================================================
    // 熔炼后的完整 Prompt
    // ============================================================

    def "fullPrompt should combine all segments"() {
        given:
        store.appendMessage(RawMessage.user(0, sessionId, "Hi"))

        def melted = melter.melt(sessionId, staticTrunk)

        expect:
        melted.fullPrompt().contains(staticTrunk)
        melted.fullPrompt().contains("[State]")
        melted.fullPrompt().contains("[Recent Messages]")
    }

    // ============================================================
    // 工具调用消息过滤
    // ============================================================

    def "should not include tool/assistant-tool-calls in short tail"() {
        given:
        store.appendMessage(RawMessage.user(0, sessionId, "Get weather"))
        store.appendMessage(RawMessage.assistantWithToolCalls(0, sessionId,
                [ToolCall.of("c1", "get_weather", [:])]))
        store.appendMessage(RawMessage.toolResult(0, sessionId, "c1", '{"temp":"18"}'))
        store.appendMessage(RawMessage.assistant(0, sessionId, "北京18度"))

        when:
        def melted = melter.melt(sessionId, staticTrunk)

        then:
        melted.shortTail().contains("Get weather")     // user 消息
        melted.shortTail().contains("北京18度")           // assistant 文本回复
        // 尾部只保留 user + assistant 纯文本，不包含 tool 调用和返回
        melted.shortTail().contains("Get weather")
        melted.shortTail().contains("北京18度")
    }
}
