/*
 * CrashRecoveryE2ESpec — 模拟崩溃恢复端到端测试
 *
 * 测试目标：
 *   - 启动 Agent → 执行工具调用 → 模拟崩溃 → 重启 → 验证状态恢复
 *   - 多轮 tool_calls 链的完整恢复
 *   - 穿插用户中断场景的恢复
 *
 * 不涉及真实的 LLM 调用，使用 InMemoryWalStore 模拟崩溃恢复流程。
 */
package org.cland.alice.core.agent.wal

import spock.lang.Specification
import spock.lang.Title

@Title("模拟崩溃恢复 E2E — WAL + Checkpoint 双轨制")
class CrashRecoveryE2ESpec extends Specification {

    String sid = "crash-e2e-session"

    // ========== 基础崩溃恢复流程 ==========

    def "simulated crash recovery: write + crash + restore matches pre-crash state"() {
        given: "a WalSession writes a series of messages + checkpoint"
        def preCrash = new WalSession()
        preCrash.system(sid, "You are Alice Agent")
        preCrash.user(sid, "帮我查北京天气")

        // Agent 输出 assistant tool calls
        def toolCalls = [ToolCall.of("tc-weather", "get_weather", [city: "Beijing"])]
        preCrash.assistantToolCalls(sid, toolCalls)
        preCrash.checkpointOnReActEnd(sid, "ACTING",
            [retry: 0, goal: "query_weather"], "")

        // 工具返回结果
        preCrash.toolResult(sid, "tc-weather", '{"temp": 18, "humidity": 45}')
        preCrash.checkpointOnToolReturn(sid, "get_weather", true)

        // 记录崩溃前的状态快照
        def preCrashMsgCount = preCrash.messageCount(sid)
        def preCrashCheckpoint = preCrash.getLatestCheckpoint(sid)
        def preCrashMessages = preCrash.getAllMessages(sid)

        when: "simulating a crash — creating a new WalSession with the same store"
        // 注意：InMemoryWalStore 无法跨进程保留，但这里模拟"同进程内恢复"
        // 实际生产用 PostgreSQL 实现，测试用 InMemory 验证逻辑正确性
        def store = preCrash.store  // 共享同一个 store 模拟"持久化存储"

        and: "a brand new WalSession recovers from the same store"
        def postCrash = new WalSession(store)
        def result = postCrash.recover(sid)

        then: "recovery succeeds"
        result.isRecovered()
        result.replayedMessages() != null
        result.summary() != null

        and: "post-crash message count matches pre-crash"
        postCrash.messageCount(sid) == preCrashMsgCount

        and: "post-crash messages match pre-crash content"
        def postCrashMessages = postCrash.getAllMessages(sid)
        postCrashMessages.size() == preCrashMessages.size()
        // Verify content integrity
        postCrashMessages[0].content() == preCrashMessages[0].content()
        postCrashMessages[1].content() == preCrashMessages[1].content()

        and: "post-crash linkage is intact"
        postCrash.validateLinkage(sid).isComplete()
    }

    // ========== 多轮工具调用链恢复 ==========

    def "multi-round tool chain recovery: maintains call-result pairing"() {
        given: "a session with multiple tool call rounds"
        def session = new WalSession()

        // Round 1: check weather
        session.user(sid, "今天北京天气如何？")
        session.assistantToolCalls(sid,
            [ToolCall.of("c1", "get_weather", [city: "Beijing"])])
        session.checkpointOnReActEnd(sid, "ACTING", [round: 1], "")
        session.toolResult(sid, "c1", '{"temp": 25}')

        // Round 2: check air quality (dependent on round 1)
        session.assistantToolCalls(sid,
            [ToolCall.of("c2", "get_air_quality", [city: "Beijing"])])
        session.checkpointOnReActEnd(sid, "ACTING", [round: 2], "")
        session.toolResult(sid, "c2", '{"aqi": 85}')

        // Round 3: final answer
        session.assistant(sid, "北京今天气温25°C，空气质量良好。")

        def preRecoveryMessages = session.getAllMessages(sid)
        def preRecoveryLinkage = session.validateLinkage(sid)

        when: "recovering from the same store"
        def recovered = new WalSession(session.store)
        def result = recovered.recover(sid)
        def postRecoveryMessages = recovered.getAllMessages(sid)
        def postRecoveryLinkage = recovered.validateLinkage(sid)

        then: "all tool call-result pairs are preserved"
        preRecoveryLinkage.isComplete()
        postRecoveryLinkage.isComplete()

        and: "message count matches"
        postRecoveryMessages.size() == preRecoveryMessages.size()

        and: "pairing ids are intact"
        def toolCallMsg = postRecoveryMessages.find { it.toolCalls() != null }
        def toolResultMsg = postRecoveryMessages.find { it.role() == "tool" }

        toolCallMsg != null
        toolResultMsg != null
        toolCallMsg.toolCalls()[0].id() == toolResultMsg.toolCallId()

        and: "recovery replayed messages"
        result.replayedMessages() != null
    }

    // ========== 穿插用户中断恢复 ==========

    def "recovery with user interruption mid-tool-chain"() {
        given: "a session where user interrupts mid-execution"
        def session = new WalSession()

        // Round 1: user question → assistant tool call
        session.user(sid, "帮我查上海和深圳的天气")
        session.assistantToolCalls(sid,
            [ToolCall.of("c-sh", "get_weather", [city: "Shanghai"]),
             ToolCall.of("c-sz", "get_weather", [city: "Shenzhen"])])
        session.checkpointOnReActEnd(sid, "ACTING", [round: 1], "")

        // Tool 1 returns
        session.toolResult(sid, "c-sh", '{"temp": 28}')

        // USER INTERRUPTS: new user input arrives before tool 2 completes
        session.user(sid, "等一下，先查北京")
        session.checkpointOnUserInput(sid)

        // Agent now works on Beijing instead
        session.assistantToolCalls(sid,
            [ToolCall.of("c-bj", "get_weather", [city: "Beijing"])])
        session.checkpointOnReActEnd(sid, "ACTING", [round: 2], "")

        // Tool returns
        session.toolResult(sid, "c-bj", '{"temp": 22}')

        when: "recovering"
        def recovered = new WalSession(session.store)
        def result = recovered.recover(sid)
        def postMessages = recovered.getAllMessages(sid)

        then: "recovery succeeds despite interleaved messages"
        result.isRecovered()
        postMessages.size() == 6  // 2 user + 2 assistant_tool_calls + 2 tool

        and: "the interrupted tool call (c-sz) is tracked as missing"
        // c-sz was orphaned by user interruption — this is expected
        def linkage = recovered.validateLinkage(sid)
        linkage.missingToolCallIds().size() == 1
        linkage.missingToolCallIds().contains("c-sz")
    }

    // ========== 空会话恢复 ==========

    def "recovery on empty session returns FRESH_START"() {
        given: "a brand new store"
        def store = new InMemoryWalStore()
        def session = new WalSession(store)

        when:
        def result = session.recover("unknown-session")

        then:
        !result.isRecovered()
        result.status() == RecoveryEngine.RecoveryStatus.FRESH_START
    }

    // ========== 无 Checkpoint 全量回放 ==========

    def "full replay when no checkpoint exists"() {
        given: "messages written without any checkpoint"
        def session = new WalSession()
        session.user(sid, "msg1")
        session.assistant(sid, "reply1")
        session.user(sid, "msg2")
        session.assistant(sid, "reply2")

        when: "recovering"
        def recovered = new WalSession(session.store)
        def result = recovered.recover(sid)

        then: "full replay occurred"
        result.status() == RecoveryEngine.RecoveryStatus.FULL_REPLAY
        result.replayedMessages().size() == 4
    }
}
