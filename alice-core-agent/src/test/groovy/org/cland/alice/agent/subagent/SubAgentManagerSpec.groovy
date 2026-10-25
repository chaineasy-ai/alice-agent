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

    def "取消终端状态的子 Agent 应返回 false"() {
        given:
        def record = manager.spawnSubAgent("test goal", null)

        // 使用 cancel 将其变为 CANCELED 状态
        manager.cancelSubAgent(record.id())

        when:
        def cancelledAgain = manager.cancelSubAgent(record.id())

        then:
        !cancelledAgain  // 终端状态不可取消
    }

    // ========================================================================
    // results
    // ========================================================================

    def "getSubAgentResult 对终端状态的子 Agent 应返回结果"() {
        given:
        def record = manager.spawnSubAgent("test goal", null)

        // 取消以快速达到终端状态
        manager.cancelSubAgent(record.id())

        when:
        def result = manager.getSubAgentResult(record.id())

        then:
        result.present
        result.get().subAgentId() == record.id()
        result.get().summary()   != null
        result.get().durationMs() >= 0
    }

    def "getSubAgentResult 对正在运行的子 Agent 应返回 empty"() {
        given: '注入会阻塞的 runner，把子 Agent 钉在 RUNNING（不依赖时序碰运气）'
        def started = new java.util.concurrent.CountDownLatch(1)
        def release = new java.util.concurrent.CountDownLatch(1)
        manager.setRunner({ id, goal, cfg ->
            started.countDown()
            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            ("done:" + goal) as String
        } as SubAgentRunner)

        when: 'spawn 后等到 runner 真的开跑'
        def record = manager.spawnSubAgent("test goal", null)
        assert started.await(10, java.util.concurrent.TimeUnit.SECONDS)
        def runningResult = manager.getSubAgentResult(record.id())

        then: '运行中必须 empty（删掉 isTerminal 过滤这条会红）'
        record.status() == SubAgentStatus.RUNNING
        !runningResult.present

        when: '放行并等完成'
        release.countDown()
        def deadline = System.currentTimeMillis() + 10_000
        while (!manager.getSubAgentResult(record.id()).present
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        def doneResult = manager.getSubAgentResult(record.id())

        then: '完成后 present（同一条断言不能恒 empty）'
        doneResult.present
        doneResult.get().summary().contains("done:test goal")
    }

    def "getSubAgentResult 对不存在的 ID 应返回 empty"() {
        expect:
        !manager.getSubAgentResult("non-existent").present
    }

    // ========================================================================
    // connect (实际实现 — 连接到不存在的端点会抛出异常)
    // ========================================================================

    def "connectAgent 对不可达端点应抛出 AcpClientException"() {
        when:
        manager.connectAgent("ext-agent", "http://localhost:1/acp")

        then:
        def e = thrown(org.cland.alice.agent.internal.acp.AcpClientException)
        e.message.contains("Failed to connect to ACP agent")
    }

    def "connectAgent 在失败时应在注册表中创建 FAILED 记录"() {
        when:
        try {
            manager.connectAgent("ext-agent", "http://localhost:1/acp")
        } catch (Exception ignored) {
            // expected
        }

        then:
        // 注册表中应有 FAILED 状态的记录
        def list = manager.listSubAgents()
        list.size() == 1
        list[0].status() == SubAgentStatus.FAILED
        list[0].type() == SubAgentType.ACP
    }

    // ========================================================================
    // send / prompt
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

    def "promptAgent 对 ALICE 子 Agent 应返回 empty"() {
        given:
        def record = manager.spawnSubAgent("test goal", null)

        expect:
        !manager.promptAgent(record.id(), "analyze").present
    }

    def "promptAgent 对未初始化的 ACP 子 Agent 应返回 empty"() {
        given:
        // 直接创建一个 FAILED 状态的 ACP 记录以测试 promptAgent
        def failedRecord = new SubAgentRecord(
            "test-id", SubAgentType.ACP, SubAgentStatus.FAILED,
            "test-agent", null, "http://localhost:1/acp",
            System.currentTimeMillis(), System.currentTimeMillis(), "not connected")

        when:
        def result = manager.promptAgent(failedRecord.id(), "analyze")

        then:
        !result.present
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
