package org.cland.alice.memory.wal

import spock.lang.Specification
import spock.lang.Subject

/**
 * Spock 测试：验证 WalAppender 和 CheckpointManager。
 */
class WalAppenderCheckpointSpec extends Specification {

    @Subject
    WalAppender appender

    CheckpointManager checkpointManager
    InMemoryWalStore store

    def setup() {
        store = new InMemoryWalStore()
        appender = new WalAppender(store)
        checkpointManager = new CheckpointManager(store, appender)
    }

    // ============================================================
    // WalAppender
    // ============================================================

    def "should append system message"() {
        when:
        def id = appender.appendSystem("s1", "You are helpful")

        then:
        id > 0
        appender.messageCount("s1") == 1
    }

    def "should append user message with name"() {
        when:
        def id = appender.appendUser("s1", "Hello", "Alice")

        then:
        id > 0
        def msg = store.getMessage(id).get()
        msg.role() == "user"
        msg.name() == "Alice"
    }

    def "should append assistant tool calls"() {
        given:
        def calls = [
            ToolCall.of("call_1", "get_weather", ["location": "Beijing"]),
            ToolCall.of("call_2", "get_time", ["zone": "UTC"])
        ]

        when:
        def id = appender.appendAssistantToolCalls("s1", calls)

        then:
        def msg = store.getMessage(id).get()
        msg.role() == "assistant"
        msg.content() == null
        msg.toolCalls().size() == 2
    }

    def "should append tool result"() {
        given:
        appender.appendAssistantToolCalls("s1",
                [ToolCall.of("call_xyz", "test", [:])])

        when:
        def id = appender.appendToolResult("s1", "call_xyz", '{"ok":true}')

        then:
        def msg = store.getMessage(id).get()
        msg.role() == "tool"
        msg.toolCallId() == "call_xyz"
        msg.content() == '{"ok":true}'
    }

    def "should batch append messages"() {
        given:
        def msgs = [
            RawMessage.user(0, "s1", "Q1"),
            RawMessage.assistant(0, "s1", "A1")
        ]

        when:
        def lastId = appender.appendBatch(msgs)

        then:
        lastId > 0
        appender.messageCount("s1") == 2
    }

    // ============================================================
    // 消息链路校验
    // ============================================================

    def "should validate complete message linkage"() {
        given:
        // 完整的 tool_calls → tool response 链路
        appender.appendAssistantToolCalls("s1",
                [ToolCall.of("call_a", "tool_a", [:])])
        appender.appendToolResult("s1", "call_a", "done")

        when:
        def result = appender.validateLinkage("s1")

        then:
        result.isComplete()
        result.totalMessages() == 2
        result.missingToolCallResponses() == 0
    }

    def "should detect missing tool responses"() {
        given:
        // 缺少 tool response
        appender.appendAssistantToolCalls("s1",
                [ToolCall.of("call_missing", "tool_x", [:])])

        when:
        def result = appender.validateLinkage("s1")

        then:
        !result.isComplete()
        result.missingToolCallResponses() == 1
        result.missingToolCallIds() == ["call_missing"]
    }

    // ============================================================
    // CheckpointManager
    // ============================================================

    def "should trigger checkpoint on ReAct end"() {
        given:
        appender.appendAssistant("s1", "Hello")

        when:
        def cpId = checkpointManager.onReActCycleEnd("s1", "PLANNING",
                ["retry": 0], "{}")

        then:
        cpId > 0
        store.getLatestCheckpoint("s1").present
        store.getLatestCheckpoint("s1").get().stateNode() == "PLANNING"
    }

    def "should be idempotent within same safe point"() {
        given:
        appender.appendAssistant("s1", "Hello") // id=1

        when:
        def cpId1 = checkpointManager.onReActCycleEnd("s1", "ACTING",
                ["retry": 0], "{}")
        def cpId2 = checkpointManager.onReActCycleEnd("s1", "ACTING",
                ["retry": 0], "{}")

        then:
        cpId1 == cpId2  // 幂等：返回同一个 CP ID
    }

    def "should trigger new checkpoint after new message"() {
        given:
        appender.appendAssistant("s1", "First")
        def cpId1 = checkpointManager.onReActCycleEnd("s1", "ACTING", [:], "")

        when:
        appender.appendAssistant("s1", "Second") // 新消息
        def cpId2 = checkpointManager.onReActCycleEnd("s1", "OBSERVING", [:], "")

        then:
        cpId2 != cpId1
        cpId2 > cpId1
    }

    def "should trigger checkpoint on user input"() {
        when:
        def cpId = checkpointManager.onUserInput("s1")

        then:
        store.getLatestCheckpoint("s1").get().stateNode() == "START"
    }

    def "should trigger checkpoint on tool return"() {
        when:
        def cpId = checkpointManager.onToolReturn("s1", "test_tool", true)

        then:
        store.getLatestCheckpoint("s1").get().stateNode() == "ACTING"
        store.getLatestCheckpoint("s1").get().variableSnapshot()["lastTool"] == "test_tool"
    }

    def "should trigger checkpoint on error"() {
        when:
        def cpId = checkpointManager.onError("s1", "TOOL_EXEC", "Timeout")

        then:
        store.getLatestCheckpoint("s1").get().stateNode() == "ERROR"
        store.getLatestCheckpoint("s1").get().variableSnapshot()["error"] == "Timeout"
    }
}
