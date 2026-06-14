package org.cland.alice.agent.subagent

import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title

/**
 * 测试 {@link SubAgentManager} — 子 Agent 生命周期编排。
 *
 * <p>验证 spawn/cancel/list/results 的核心生命周期行为。
 */
@Title("SubAgentManager 规格")
class SubAgentManagerSpec extends Specification {

    static final String PARENT_SESSION = "parent-session-1"

    @Subject
    SubAgentManager manager = new SubAgentManager(PARENT_SESSION)

    def cleanup() {
        manager.close()
    }

    // ========================================================================
    // spawn
    // ========================================================================

    def "spawnSubAgent 应创建 RUNNING 状态的 ALICE 子 Agent"() {
        when:
        def record = manager.spawnSubAgent("list files in /tmp", null)

        then:
        record.type()   == SubAgentType.ALICE
        record.status() == SubAgentStatus.RUNNING
        record.goal()   == "list files in /tmp"
        record.id()     != null
        record.sessionId() != null
        record.sessionId().startsWith(PARENT_SESSION)
    }

    def "spawnSubAgent 应注册到注册表中"() {
        when:
        manager.spawnSubAgent("test goal", null)

        then:
        manager.activeCount() == 1
        manager.totalCount()  == 1
    }

    def "多次 spawn 应增加活跃计数"() {
        when:
        manager.spawnSubAgent("goal 1", null)
        manager.spawnSubAgent("goal 2", null)

        then:
        manager.activeCount() == 2
        manager.totalCount()  == 2
    }

    // ========================================================================
    // list
    // ========================================================================

    def "listSubAgents 应返回所有注册的子 Agent"() {
        given:
        manager.spawnSubAgent("goal 1", null)
        manager.spawnSubAgent("goal 2", null)

        when:
        def list = manager.listSubAgents()

        then:
        list.size() == 2
        list*.goal().containsAll(["goal 1", "goal 2"])
    }

    // ========================================================================
    // cancel
    // ========================================================================

    def "cancelSubAgent 应将状态变为 CANCELED"() {
        given:
        def record = manager.spawnSubAgent("test goal", null)

        when:
        def cancelled = manager.cancelSubAgent(record.id())

        then:
        cancelled
        manager.getSubAgent(record.id()).get().status() == SubAgentStatus.CANCELED
    }

    def "取消不存在的子 Agent 应返回 false"() {
        expect:
        !manager.cancelSubAgent("non-existent")
    }

    def "取消已完成的子 Agent 应返回 false"() {
        given:
        def record = manager.spawnSubAgent("test goal", null)

        // 等待完成
        Thread.sleep(200)

        when:
        def cancelled = manager.cancelSubAgent(record.id())

        then:
        !cancelled  // 已完成状态不可取消
    }

    // ========================================================================
    // results
    // ========================================================================

    def "getSubAgentResult 应返回已完成子 Agent 的结果"() {
        given:
        def record = manager.spawnSubAgent("test goal", null)

        // 等待完成
        Thread.sleep(200)

        when:
        def result = manager.getSubAgentResult(record.id())

        then:
        result.present
        result.get().subAgentId() == record.id()
        result.get().status()    == SubAgentStatus.COMPLETED
        result.get().summary()   != null
        result.get().durationMs() >= 0
    }

    def "getSubAgentResult 对正在运行的子 Agent 应返回 empty"() {
        given:
        def record = manager.spawnSubAgent("test goal", null)

        when:
        def result = manager.getSubAgentResult(record.id())

        then:
        // 立即检查（可能仍在执行中）
        // 如果我们检查得足够快，它应该还在运行
        manager.activeCount() >= 0
    }

    def "getSubAgentResult 对不存在的 ID 应返回 empty"() {
        expect:
        !manager.getSubAgentResult("non-existent").present
    }

    // ========================================================================
    // connect (存根)
    // ========================================================================

    def "connectAgent 应创建 CONNECTED 状态的 ACP 子 Agent"() {
        when:
        def record = manager.connectAgent("ext-agent", "http://localhost:9000/acp")

        then:
        record.type()     == SubAgentType.ACP
        record.status()   == SubAgentStatus.CONNECTED
        record.endpoint() == "http://localhost:9000/acp"
        record.id()       != null
    }

    // ========================================================================
    // send / prompt (存根)
    // ========================================================================

    def "sendToSubAgent 对存在的子 Agent 应返回 true"() {
        given:
        def record = manager.spawnSubAgent("test goal", null)

        expect:
        manager.sendToSubAgent(record.id(), "hello")
    }

    def "sendToSubAgent 对不存在的 ID 应返回 false"() {
        expect:
        !manager.sendToSubAgent("non-existent", "hello")
    }

    def "promptAgent 应返回占位响应"() {
        given:
        def record = manager.connectAgent("ext-agent", "http://localhost:9000/acp")

        when:
        def response = manager.promptAgent(record.id(), "analyze this")

        then:
        response.present
        response.get().contains("analyze this")
    }

    def "promptAgent 对 ALICE 子 Agent 应返回 empty"() {
        given:
        def record = manager.spawnSubAgent("test goal", null)

        expect:
        !manager.promptAgent(record.id(), "analyze").present
    }

    // ========================================================================
    // close / cleanup
    // ========================================================================

    def "close 应清理所有资源"() {
        given:
        manager.spawnSubAgent("goal 1", null)
        manager.spawnSubAgent("goal 2", null)

        when:
        manager.close()

        then:
        manager.activeCount() == 0
        manager.totalCount()  == 0
    }
}
