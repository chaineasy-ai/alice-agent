/*
 * WalEpisodicVaultSpec — 验证 WalEpisodicVault 的正确性
 *
 * 测试目标：
 *   - WAL → Step 映射 (rawMessageToStep / rebuildSteps)
 *   - getTrace() 基于 WAL 重建
 *   - getRecentSteps() / getImportantSteps()
 *   - 遗忘策略 (session/global limit)
 *   - 清除操作
 */
package org.cland.alice.memory.vault

import spock.lang.Specification
import spock.lang.Title

import org.cland.alice.memory.core.Step
import org.cland.alice.core.agent.wal.InMemoryWalStore
import org.cland.alice.core.agent.wal.ToolCall
import org.cland.alice.core.agent.wal.WalSession

@Title("WalEpisodicVault — 基于 WAL 的情景记忆")
class WalEpisodicVaultSpec extends Specification {

    def walSession = new WalSession()
    def vault = new WalEpisodicVault(walSession)

    def cleanup() {
        vault.clearAll()
    }

    // ========== RawMessage → Step 映射 ==========

    def "rawMessageToStep maps system message correctly"() {
        given:
        def msg = walSession.system("s1", "You are Alice")

        when:
        def step = WalEpisodicVault.rawMessageToStep(
            walSession.getAllMessages("s1").get(0), 0)

        then:
        step.stepId() == "wal-" + msg
        step.action() == "system"
        step.input().contains("You are Alice")
        step.importance() == 0.5
        step.success()
    }

    def "rawMessageToStep maps user message correctly"() {
        given:
        def msg = walSession.user("s2", "Hello")

        when:
        def step = WalEpisodicVault.rawMessageToStep(
            walSession.getAllMessages("s2").get(0), 0)

        then:
        step.action() == "user"
        step.input() == "Hello"
    }

    def "rawMessageToStep maps assistant message correctly"() {
        given:
        def msg = walSession.assistant("s3", "I can help")

        when:
        def step = WalEpisodicVault.rawMessageToStep(
            walSession.getAllMessages("s3").get(0), 0)

        then:
        step.action() == "assistant"
        step.input() == "I can help"
    }

    def "rawMessageToStep maps tool result with higher importance"() {
        given:
        def msg = walSession.system("s4", "setup")
        walSession.assistant("s4", "Let me check")
        // Simulate tool call pattern: assistant → tool result
        def tc = [new ToolCall("call-1", "function",
            new ToolCall.Function("get_weather", "{}"))]
        walSession.assistantToolCalls("s4", tc)
        def toolMsg = walSession.toolResult("s4", "call-1", '{"temp":25}')

        when:
        def all = walSession.getAllMessages("s4")
        def toolStep = WalEpisodicVault.rawMessageToStep(
            all.find { it.role() == "tool" }, 0)

        then:
        toolStep.action() == "tool_result"
        toolStep.importance() == 0.8d
    }

    // ========== rebuildSteps ==========

    def "rebuildSteps returns empty list for null input"() {
        expect:
        WalEpisodicVault.rebuildSteps(null).isEmpty()
    }

    def "rebuildSteps returns empty list for empty input"() {
        expect:
        WalEpisodicVault.rebuildSteps([]).isEmpty()
    }

    def "rebuildSteps converts all messages to steps"() {
        given:
        walSession.system("s5", "sys")
        walSession.user("s5", "hello")
        walSession.assistant("s5", "hi")

        when:
        def steps = WalEpisodicVault.rebuildSteps(
            walSession.getAllMessages("s5"))

        then:
        steps.size() == 3
        steps*.action() == ["system", "user", "assistant"]
    }

    // ========== getTrace ==========

    def "getTrace returns steps from WAL playback"() {
        given:
        walSession.user("s10", "task1")
        walSession.assistant("s10", "done")

        when:
        def trace = vault.getTrace("s10")

        then:
        trace.size() == 2
        trace[0].action() == "user"
        trace[1].action() == "assistant"
    }

    def "getTrace returns empty list for unknown session"() {
        expect:
        vault.getTrace("unknown").isEmpty()
    }

    // ========== getRecentSteps ==========

    def "getRecentSteps returns last N steps"() {
        given:
        5.times { i ->
            walSession.user("s20", "msg$i")
            walSession.assistant("s20", "resp$i")
        }

        when:
        def recent = vault.getRecentSteps("s20", 2)

        then:
        recent.size() == 2
        // With 10 messages (5 user + 5 assistant), last 2 are msg4 + resp4
        recent[0].action() == "user"
        recent[0].input() == "msg4"
        recent[1].action() == "assistant"
        recent[1].input() == "resp4"
    }

    def "getRecentSteps returns all if N exceeds count"() {
        given:
        walSession.user("s21", "hi")
        walSession.assistant("s21", "hello")

        when:
        def recent = vault.getRecentSteps("s21", 100)

        then:
        recent.size() == 2
    }

    // ========== stepCount / sessionCount ==========

    def "stepCount returns WAL message count"() {
        given:
        walSession.user("s30", "a")
        walSession.assistant("s30", "b")
        walSession.toolResult("s30", "c1", "result")

        expect:
        vault.stepCount("s30") == 3
    }

    def "sessionCount returns active session count"() {
        given:
        walSession.user("s31", "1")
        walSession.user("s32", "2")

        // trigger getTrace to populate index
        vault.getTrace("s31")
        vault.getTrace("s32")

        expect:
        vault.sessionCount() == 2
    }

    // ========== clear ==========

    def "clearSession removes session data"() {
        given:
        walSession.user("s40", "test")
        vault.getTrace("s40")
        vault.stepCount("s40") == 1

        when:
        vault.clearSession("s40")

        then:
        vault.getTrace("s40").isEmpty()
        vault.stepCount("s40") == 0
    }

    def "clearAll removes all data"() {
        given:
        walSession.user("s41", "a")
        walSession.user("s42", "b")
        vault.getTrace("s41")
        vault.getTrace("s42")

        when:
        vault.clearAll()

        then:
        vault.getActiveSessionIds().isEmpty()
        vault.sessionCount() == 0
    }

    // ========== penalizeStep ==========

    def "penalizeStep reduces importance"() {
        given:
        walSession.user("s50", "hello")
        vault.getTrace("s50")
        def step = vault.getTrace("s50")[0]

        when:
        vault.penalizeStep("s50", step.stepId(), 0.3d)

        then:
        vault.getImportantSteps("s50", 0.5d).isEmpty()
    }

    // ========== toString ==========

    def "toString contains vault and wal info"() {
        expect:
        vault.toString().contains("WalEpisodicVault")
        vault.toString().contains("sessions")
    }
}
