package org.cland.alice.agent.subagent

import org.cland.alice.core.agent.AgentConfig
import spock.lang.Specification
import spock.lang.Title

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * B5：子 Agent 执行端口（{@link SubAgentRunner}）注入验证。
 *
 * 口径：`SubAgentManager` 不再硬编码"进程内 new Agent"——注入 runner 即走端口，
 * 状态/结果仍按原协议回写注册表。
 */
@Title("SubAgentRunner — 执行端口注入")
class SubAgentRunnerInjectionSpec extends Specification {

    static final String PARENT = "parent-runner-1"

    def "注入 runner：spawn 走端口且结果回传注册表（不 new Agent）"() {
        given:
        def registry = new SubAgentRegistry()
        def executor = Executors.newSingleThreadExecutor()
        def manager = new SubAgentManager(PARENT, registry, executor)
        def calls = Collections.synchronizedList([])
        manager.setRunner({ String id, String goal, AgentConfig cfg ->
            calls << [id: id, goal: goal, model: cfg.defaultModelId()]
            return "runner-answer"
        } as SubAgentRunner)

        when:
        def record = manager.spawnSubAgent("do something", "test-model")
        waitFor { registry.get(record.id()).map { it.status() == SubAgentStatus.COMPLETED }.orElse(false) }

        then:
        calls.size() == 1
        calls[0].goal == "do something"
        calls[0].model == "test-model"
        calls[0].id == record.id()
        registry.get(record.id()).get().resultSummary() == "runner-answer"

        cleanup:
        manager.close()
        executor.shutdownNow()
    }

    def "runner 抛错 ⇒ 注册表记 FAILED（含可读原因）"() {
        given:
        def registry = new SubAgentRegistry()
        def executor = Executors.newSingleThreadExecutor()
        def manager = new SubAgentManager(PARENT, registry, executor)
        manager.setRunner({ String id, String goal, AgentConfig cfg ->
            throw new IllegalStateException("runner boom")
        } as SubAgentRunner)

        when:
        def record = manager.spawnSubAgent("fail please", null)
        waitFor { registry.get(record.id()).map { it.status() == SubAgentStatus.FAILED }.orElse(false) }

        then:
        registry.get(record.id()).get().resultSummary().contains("runner boom")

        cleanup:
        manager.close()
        executor.shutdownNow()
    }

    static void waitFor(Closure<Boolean> cond, long timeoutMs = 5000) {
        def deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond.call()) return
            Thread.sleep(10)
        }
        throw new AssertionError("条件未在 ${timeoutMs}ms 内满足")
    }
}
