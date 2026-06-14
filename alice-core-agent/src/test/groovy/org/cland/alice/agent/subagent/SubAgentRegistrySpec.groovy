package org.cland.alice.agent.subagent

import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title

/**
 * 测试 {@link SubAgentRegistry} — 线程安全的子 Agent 注册表。
 *
 * <p>验证 register/list/updateStatus/updateResult/remove/activeCount 行为。
 */
@Title("SubAgentRegistry 规格")
class SubAgentRegistrySpec extends Specification {

    @Subject
    SubAgentRegistry registry = new SubAgentRegistry()

    def "注册 ALICE 子 Agent 后应可在列表中找到"() {
        given:
        def record = SubAgentRecord.createAlice("id-1", "test goal", "session-1")

        when:
        registry.register(record)

        then:
        registry.size() == 1
        registry.list().contains(record)
    }

    def "按 ID 查找子 Agent 应返回正确的记录"() {
        given:
        registry.register(SubAgentRecord.createAlice("id-1", "goal1", "session-1"))
        registry.register(SubAgentRecord.createAlice("id-2", "goal2", "session-2"))

        expect:
        registry.get("id-1").present
        registry.get("id-1").get().goal() == "goal1"
        registry.get("id-2").present
        registry.get("id-2").get().goal() == "goal2"
        !registry.get("non-existent").present
    }

    def "更新子 Agent 状态应生效"() {
        given:
        registry.register(SubAgentRecord.createAlice("id-1", "goal", "session-1"))

        when:
        def updated = registry.updateStatus("id-1", SubAgentStatus.COMPLETED)

        then:
        updated
        registry.get("id-1").get().status() == SubAgentStatus.COMPLETED
    }

    def "更新不存在的子 Agent 状态应返回 false"() {
        expect:
        !registry.updateStatus("non-existent", SubAgentStatus.COMPLETED)
    }

    def "更新子 Agent 结果摘要应生效"() {
        given:
        registry.register(SubAgentRecord.createAlice("id-1", "goal", "session-1"))

        when:
        def updated = registry.updateResult("id-1", "completed successfully")

        then:
        updated
        registry.get("id-1").get().resultSummary() == "completed successfully"
    }

    def "移除子 Agent 后应不再存在于列表中"() {
        given:
        registry.register(SubAgentRecord.createAlice("id-1", "goal", "session-1"))

        when:
        def removed = registry.remove("id-1")

        then:
        removed
        registry.size() == 0
        !registry.get("id-1").present
    }

    def "activeCount 应正确反映 RUNNING 和 CONNECTED 状态的数量"() {
        given:
        registry.register(SubAgentRecord.createAlice("id-1", "goal1", "session-1"))
        registry.register(SubAgentRecord.createAcp("id-2", "ext-agent", "http://localhost:9000"))
        registry.register(SubAgentRecord.createAlice("id-3", "goal3", "session-3"))

        when:
        registry.updateStatus("id-3", SubAgentStatus.COMPLETED)

        then:
        registry.activeCount() == 2  // id-1 (RUNNING) + id-2 (CONNECTED)
    }

    def "达到最大并发限制时应抛出 IllegalStateException"() {
        given:
        (1..SubAgentRegistry.MAX_CONCURRENT).each { i ->
            registry.register(SubAgentRecord.createAlice("id-$i", "goal $i", "session-$i"))
        }

        when:
        registry.register(SubAgentRecord.createAlice("overflow", "extra goal", "session-extra"))

        then:
        thrown(IllegalStateException)
    }

    def "某个子 Agent 完成后应释放一个并发槽位"() {
        given:
        (1..SubAgentRegistry.MAX_CONCURRENT).each { i ->
            registry.register(SubAgentRecord.createAlice("id-$i", "goal $i", "session-$i"))
        }

        when: "完成一个子 Agent 释放槽位"
        registry.updateStatus("id-1", SubAgentStatus.COMPLETED)
        then:
        registry.activeCount() == 4  // 5 - 1

        when: "现在可以注册新的子 Agent"
        registry.register(SubAgentRecord.createAlice("new-id", "new goal", "session-new"))
        then:
        registry.size() == 6  // 5 original + 1 new
    }

    def "列表应按注册时间排序"() {
        given:
        // 强制不同创建时间以确保排序确定性
        def r1 = new SubAgentRecord("id-1", SubAgentType.ALICE, SubAgentStatus.RUNNING,
            "first", "session-1", null, 100, null, null)
        def r2 = new SubAgentRecord("id-2", SubAgentType.ALICE, SubAgentStatus.RUNNING,
            "second", "session-2", null, 200, null, null)
        def r3 = new SubAgentRecord("id-3", SubAgentType.ALICE, SubAgentStatus.RUNNING,
            "third", "session-3", null, 300, null, null)

        registry.register(r2)
        registry.register(r3)
        registry.register(r1)

        expect:
        registry.list()*.id() == ["id-1", "id-2", "id-3"]  // Sorted by createdAt ascending
    }
}
