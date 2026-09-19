package org.cland.alice.runtime

import org.cland.alice.agent.proto.ControlCmd
import org.cland.alice.agent.proto.ExecutionCmd
import org.cland.alice.agent.proto.event.StepEvent
import org.cland.alice.agent.proto.event.StepEventType
import spock.lang.Specification
import spock.lang.Title

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/**
 * 回放发布器压力测试：**先执行后订阅**与**边执行边订阅**两种时序各跑 N 轮，末帧（DONE）不得丢。
 *
 * 背景：偶发丢末帧（先订/后订竞态）——用高频循环把它钉死。
 */
@Title("RoundEventPublisher — 回放不丢帧（stress）")
class RoundEventPublisherStressSpec extends Specification {

    static final String SESSION = "sess-01"

    def "100 轮（立即订阅）：帧序列恒为 [SUMMARY, DONE]"() {
        given:
        def host = new AgentHost(new FakeEngine())

        when:
        (1..100).each { i ->
            def frames = collect(host.dispatch(new ExecutionCmd.AcquireGoalCmd("p" + i, SESSION, "t" + i)))
            assert frames*.type() == [StepEventType.SUMMARY, StepEventType.DONE]:
                "第 ${i} 轮丢帧：${frames*.type()}"
        }

        then:
        noExceptionThrown()
    }

    def "100 轮（先跑完再订阅）：回放仍完整"() {
        given:
        def host = new AgentHost(new FakeEngine())

        when:
        (1..100).each { i ->
            def pub = host.dispatch(new ExecutionCmd.AcquireGoalCmd("p" + i, SESSION, "t" + i))
            waitFor { host.health().rounds() == (i as long) }
            def frames = collect(pub)
            assert frames*.type() == [StepEventType.SUMMARY, StepEventType.DONE]:
                "第 ${i} 轮回放丢帧：${frames*.type()}"
        }

        then:
        noExceptionThrown()
    }

    def "100 轮控制类 ack（同步收口）：[OBSERVE, DONE]"() {
        given:
        def host = new AgentHost(new FakeEngine())

        when:
        (1..100).each { i ->
            def frames = collect(host.dispatch(new ControlCmd.AbortCmd(SESSION, "t" + i)))
            assert frames*.type() == [StepEventType.OBSERVE, StepEventType.DONE]:
                "第 ${i} 轮 ack 丢帧：${frames*.type()}"
        }

        then:
        noExceptionThrown()
    }

    // ── helpers ──

    static List<StepEvent> collect(Flow.Publisher<StepEvent> pub, long timeoutMs = 3000) {
        def frames = Collections.synchronizedList(new ArrayList<StepEvent>())
        def done = new CountDownLatch(1)
        pub.subscribe(new Flow.Subscriber<StepEvent>() {
            @Override
            void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE) }

            @Override
            void onNext(StepEvent e) { frames << e }

            @Override
            void onError(Throwable t) { done.countDown() }

            @Override
            void onComplete() { done.countDown() }
        })
        assert done.await(timeoutMs, TimeUnit.MILLISECONDS): "未收口，已收 ${frames.size()} 帧"
        return new ArrayList<>(frames)
    }

    static void waitFor(Closure<Boolean> cond, long timeoutMs = 3000) {
        def deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond.call()) return
            Thread.sleep(1)
        }
        throw new AssertionError("条件未在 ${timeoutMs}ms 内满足")
    }
}
