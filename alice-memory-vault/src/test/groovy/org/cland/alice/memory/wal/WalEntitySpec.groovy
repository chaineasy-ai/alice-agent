package org.cland.alice.memory.wal

import spock.lang.Specification
import spock.lang.Subject

/**
 * Spock 测试：验证 WAL 实体层 (RawMessage / ToolCall / Checkpoint)。
 */
class WalEntitySpec extends Specification {

    // ============================================================
    // RawMessage 测试
    // ============================================================

    def "should create RawMessage with all fields"() {
        when:
        def msg = new RawMessage(
                1, "session-1", "user", "Hello", null, null, "Alice",
                System.currentTimeMillis(), ["token": 42])

        then:
        msg.messageId() == 1
        msg.sessionId() == "session-1"
        msg.role() == "user"
        msg.content() == "Hello"
        msg.toolCalls() == null
        msg.toolCallId() == null
        msg.name() == "Alice"
        msg.metadata() == ["token": 42]
    }

    def "should reject invalid role"() {
        when:
        new RawMessage(1, "s1", "invalid_role", "content", null, null, null, 0, [:])

        then:
        thrown(IllegalArgumentException)
    }

    def "should reject tool message without toolCallId"() {
        when:
        new RawMessage(1, "s1", "tool", "result", null, null, null, 0, [:])

        then:
        thrown(IllegalArgumentException)
    }

    def "should reject null content with no toolCalls"() {
        when:
        new RawMessage(1, "s1", "assistant", null, null, null, null, 0, [:])

        then:
        thrown(IllegalArgumentException)
    }

    def "should allow null content when toolCalls present"() {
        when:
        def msg = new RawMessage(1, "s1", "assistant", null,
                [new ToolCall("call_1", "function",
                        new ToolCall.Function("get_weather", '{"loc":"Beijing"}'))],
                null, null, 0, [:])

        then:
        msg.content() == null
        msg.toolCalls().size() == 1
        msg.toolCalls()[0].id() == "call_1"
    }

    def "should create messages via factory methods"() {
        expect:
        RawMessage.system(1, "s1", "be helpful").role() == "system"
        RawMessage.user(2, "s1", "Hi").role() == "user"
        RawMessage.assistant(3, "s1", "Hello!").role() == "assistant"
        RawMessage.userWithName(4, "s1", "Hi", "Bob").name() == "Bob"
    }

    def "factory should create assistant with tool calls"() {
        when:
        def calls = [new ToolCall("c1", "function", new ToolCall.Function("tool1", "{}"))]
        def msg = RawMessage.assistantWithToolCalls(5, "s1", calls)

        then:
        msg.role() == "assistant"
        msg.content() == null
        msg.toolCalls().size() == 1
    }

    def "factory should create tool result message"() {
        when:
        def msg = RawMessage.toolResult(6, "s1", "call_xyz", '{"result":"ok"}')

        then:
        msg.role() == "tool"
        msg.toolCallId() == "call_xyz"
        msg.content() == '{"result":"ok"}'
    }

    // ============================================================
    // ToolCall 测试
    // ============================================================

    def "should create ToolCall via of() factory"() {
        when:
        def tc = ToolCall.of("call_abc", "get_weather", ["location": "Beijing"])

        then:
        tc.id() == "call_abc"
        tc.type() == "function"
        tc.function().name() == "get_weather"
        tc.function().arguments().contains("Beijing")
    }

    def "should create ToolCall via ofJson() factory"() {
        when:
        def tc = ToolCall.ofJson("call_xyz", "read_file", '{"path":"/tmp/test.txt"}')

        then:
        tc.function().name() == "read_file"
        tc.function().arguments() == '{"path":"/tmp/test.txt"}'
    }

    def "should reject ToolCall with null id"() {
        when:
        new ToolCall(null, "function", new ToolCall.Function("f", "{}"))

        then:
        thrown(NullPointerException)
    }

    def "should reject ToolCall with invalid type"() {
        when:
        new ToolCall("c1", "invalid_type", new ToolCall.Function("f", "{}"))

        then:
        thrown(IllegalArgumentException)
    }

    // ============================================================
    // Checkpoint 测试
    // ============================================================

    def "should create Checkpoint with all fields"() {
        when:
        def cp = new Checkpoint(1, "s1", 42, "PLANNING",
                ["retry": 0, "goal": "test"], "{}", System.currentTimeMillis())

        then:
        cp.checkpointId() == 1
        cp.sessionId() == "s1"
        cp.lastAppliedMessageId() == 42
        cp.stateNode() == "PLANNING"
        cp.variableSnapshot()["retry"] == 0
        cp.planSnapshot() == "{}"
    }

    def "should reject Checkpoint with negative lastAppliedMessageId"() {
        when:
        new Checkpoint(1, "s1", -1, "PLANNING", [:], "", 0)

        then:
        thrown(IllegalArgumentException)
    }

    def "should create Checkpoint with default values for null fields"() {
        when:
        def cp = new Checkpoint(1, "s1", 0, "START", null, null, 0)

        then:
        cp.variableSnapshot() == [:]
        cp.planSnapshot() == ""
        cp.createdAt() > 0
    }

    def "should advance checkpoint pointer"() {
        given:
        def cp = new Checkpoint(5, "s1", 100, "ACTING", ["retry": 2], "{}", 1000)

        when:
        // 创建一个新 CP，推进指针
        def newCp = new Checkpoint(6, "s1", 150, "OBSERVING",
                ["retry": 2, "lastTool": "test"], "{}", 2000)

        then:
        newCp.lastAppliedMessageId() == 150
        newCp.stateNode() == "OBSERVING"
    }
}
