package org.cland.alice.memory.wal

import spock.lang.Specification
import spock.lang.Subject

/**
 * Spock 测试：验证 WalSession 集成门面。
 */
class WalSessionSpec extends Specification {

    @Subject
    WalSession session
    String sid = "test-wal-session"

    def setup() {
        session = new WalSession()
    }

    // ============================================================
    // WAL 追加
    // ============================================================

    def "should append all message types"() {
        expect:
        session.system(sid, "be helpful") > 0
        session.user(sid, "Hello") > 0
        session.assistant(sid, "Hi!") > 0
        session.messageCount(sid) == 3
    }

    def "should append tool calls and results"() {
        given:
        session.assistantToolCalls(sid,
                [ToolCall.of("c1", "tool1", ["key": "val"])])
        session.toolResult(sid, "c1", "done")

        expect:
        session.messageCount(sid) == 2
        session.validateLinkage(sid).isComplete()
    }

    // ============================================================
    // Checkpoint 操作
    // ============================================================

    def "should trigger checkpoint on all safe points"() {
        when:
        session.checkpointOnUserInput(sid)
        session.user(sid, "new input") // 追加消息使 lastAppliedId 变化
        session.checkpointOnReActEnd(sid, "ACTING", [:], "")
        session.assistant(sid, "tool returned") // 追加消息
        session.checkpointOnToolReturn(sid, "tool1", true)
        session.user(sid, "error input") // 追加消息
        session.checkpointOnError(sid, "TOOL_EXEC", "error")

        then:
        session.getLatestCheckpoint(sid).present
        // 最后一个是 ERROR
        session.getLatestCheckpoint(sid).get().stateNode() == "ERROR"
    }

    // ============================================================
    // 恢复
    // ============================================================

    def "should recover after simulated crash"() {
        given:
        session.system(sid, "You're helpful")
        session.user(sid, "帮我查天气")
        session.assistantToolCalls(sid,
                [ToolCall.of("c1", "get_weather", ["city": "Beijing"])])
        session.checkpointOnReActEnd(sid, "ACTING",
                ["retry": 0, "pendingTool": "get_weather"], "")

        // 模拟崩溃：工具返回了，但没 Checkpoint
        session.toolResult(sid, "c1", '{"temp":"18"}')

        when:
        def result = session.recover(sid)

        then:
        result.isRecovered()
        session.lastRecoveryResult().present
        session.lastRecoveryResult().get().summary().contains("Replayed")
    }

    def "should detect missing tool response linkage"() {
        given:
        session.assistantToolCalls(sid,
                [ToolCall.of("orphan", "tool_x", [:])])

        when:
        def linkage = session.validateLinkage(sid)

        then:
        !linkage.isComplete()
        linkage.missingToolCallIds() == ["orphan"]
    }

    // ============================================================
    // Prompt 熔炼
    // ============================================================

    def "should melt prompt through session"() {
        given:
        session.user(sid, "Hello")
        session.assistant(sid, "World")
        session.checkpointOnReActEnd(sid, "REFLECTING", [:], "")

        when:
        def melted = session.melt(sid, "static system prompt")

        then:
        melted.staticTrunk() == "static system prompt"
        melted.totalMessages() == 2
        melted.fullPrompt().contains("[State]")
        melted.fullPrompt().contains("[Recent Messages]")
    }

    // ============================================================
    // 生命周期
    // ============================================================

    def "should clear session data"() {
        given:
        session.user(sid, "data")
        session.checkpointOnReActEnd(sid, "START", [:], "")

        when:
        session.clearSession(sid)

        then:
        session.messageCount(sid) == 0
        session.getLatestCheckpoint(sid).empty
    }

    def "should clear all data"() {
        given:
        session.user("s1", "A")
        session.user("s2", "B")

        when:
        session.clearAll()

        then:
        session.getAllMessages("s1").isEmpty()
        session.getAllMessages("s2").isEmpty()
    }
}
