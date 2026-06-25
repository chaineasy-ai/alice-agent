package org.cland.alice.core.agent.wal

import spock.lang.Specification
import spock.lang.Subject

/**
 * Spock 测试：验证 RecoveryEngine 灾难恢复流程。
 */
class RecoveryEngineSpec extends Specification {

    InMemoryWalStore store
    WalAppender appender
    CheckpointManager checkpointManager
    @Subject
    RecoveryEngine recoveryEngine
    String sessionId = "test-recovery-session"

    def setup() {
        store = new InMemoryWalStore()
        appender = new WalAppender(store)
        checkpointManager = new CheckpointManager(store, appender)
        recoveryEngine = new RecoveryEngine(store, checkpointManager)
    }

    // ============================================================
    // 全新会话恢复
    // ============================================================

    def "should return FRESH_START for empty session"() {
        when:
        def result = recoveryEngine.recover(sessionId)

        then:
        result.status() == RecoveryEngine.RecoveryStatus.FRESH_START
        result.summary().contains("Fresh")
        !result.isRecovered()
    }

    // ============================================================
    // 无 Checkpoint 的全量回放
    // ============================================================

    def "should perform full replay when no checkpoint exists"() {
        given:
        appender.appendUser(sessionId, "Hello")
        appender.appendAssistant(sessionId, "Hi there")

        when:
        def result = recoveryEngine.recover(sessionId)

        then:
        result.status() == RecoveryEngine.RecoveryStatus.FULL_REPLAY
        result.replayedMessages().size() == 2
        result.recoveredNode() != null
    }

    // ============================================================
    // Checkpoint 是最新的 — 干净恢复
    // ============================================================

    def "should perform clean recovery when checkpoint is current"() {
        given:
        appender.appendUser(sessionId, "Hello")
        appender.appendAssistant(sessionId, "Hi")
        checkpointManager.onReActCycleEnd(sessionId, "REFLECTING", [:], "{}")

        when:
        def result = recoveryEngine.recover(sessionId)

        then:
        result.status() == RecoveryEngine.RecoveryStatus.CLEAN_RECOVERY
        result.recoveredNode() == "REFLECTING"
        result.lastAppliedId() > 0
    }

    // ============================================================
    // 崩溃后恢复 — 有脏消息需要重放
    // ============================================================

    def "should replay dirty messages after checkpoint"() {
        given:
        // Phase 1: 正常执行，生成 Checkpoint
        appender.appendUser(sessionId, "帮我查天气")
        appender.appendAssistantToolCalls(sessionId,
                [ToolCall.of("call_1", "get_weather", ["city": "Beijing"])])
        def cpId = checkpointManager.onReActCycleEnd(sessionId, "ACTING",
                ["retry": 0, "pendingTool": "get_weather"], "")

        // Phase 2: 模拟崩溃 — 工具返回了结果，但没来得及 Checkpoint
        appender.appendToolResult(sessionId, "call_1", '{"temp":"18度"}')

        when:
        def result = recoveryEngine.recover(sessionId)

        then:
        result.status() == RecoveryEngine.RecoveryStatus.REPLAYED_RECOVERY
        result.oldCheckpointId() == cpId
        result.newCheckpointId() > cpId
        result.replayedMessages().size() == 1  // 只有 tool 消息是脏的
        result.replayedMessages()[0].role() == "tool"
        // 恢复后的变量来自 Checkpoint + 重放更新
        result.recoveredVariables()["pendingTool"] == "get_weather"
        result.recoveredVariables()["replayedCount"] == 1
    }

    def "should replay multiple dirty messages and restore state"() {
        given:
        // Phase 1: 正常执行
        appender.appendUser(sessionId, "执行任务")
        appender.appendAssistantToolCalls(sessionId,
                [ToolCall.of("call_a", "step1", [:])])
        checkpointManager.onReActCycleEnd(sessionId, "ACTING",
                ["step": 1], "step1")

        // Phase 2: 崩溃前执行了 2 步但没有 Checkpoint
        appender.appendToolResult(sessionId, "call_a", "step1 done")
        appender.appendAssistantToolCalls(sessionId,
                [ToolCall.of("call_b", "step2", [:])])
        appender.appendToolResult(sessionId, "call_b", "step2 done")

        when:
        def result = recoveryEngine.recover(sessionId)

        then:
        result.status() == RecoveryEngine.RecoveryStatus.REPLAYED_RECOVERY
        result.replayedMessages().size() == 3
        // 恢复后应推进到最后一条消息
        result.lastAppliedId() > 0
    }

    // ============================================================
    // 恢复后生成新 Checkpoint
    // ============================================================

    def "should create new checkpoint after recovery"() {
        given:
        appender.appendUser(sessionId, "Hello")
        appender.appendAssistant(sessionId, "World")

        when:
        recoveryEngine.recover(sessionId)

        then:
        store.getLatestCheckpoint(sessionId).present
        store.getLatestCheckpoint(sessionId).get().stateNode() != null
    }

    // ============================================================
    // 多 session 隔离
    // ============================================================

    def "should not mix sessions during recovery"() {
        given:
        // Session A: 有 Checkpoint
        appender.appendUser("session-a", "A1")
        appender.appendUser("session-a", "A2")
        checkpointManager.onReActCycleEnd("session-a", "PLANNING", [:], "")

        // Session B: 无 Checkpoint，只有消息
        appender.appendUser("session-b", "B1")

        when:
        def resultA = recoveryEngine.recover("session-a")
        def resultB = recoveryEngine.recover("session-b")

        then:
        resultA.status() == RecoveryEngine.RecoveryStatus.CLEAN_RECOVERY
        resultB.status() == RecoveryEngine.RecoveryStatus.FULL_REPLAY
    }
}
