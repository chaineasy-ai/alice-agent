package org.cland.alice.runtime

import org.cland.alice.agent.proto.event.StepEvent
import org.cland.alice.agent.proto.event.StepEventType
import spock.lang.Specification
import spock.lang.Title

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 测试回放缓冲发布器（package-private）：demand 排队 / cancel / 非法 demand / 订阅者异常隔离 / 完成后回放。
 */
@Title("RoundEventPublisher — 回放缓冲")
class RoundEventPublisherSpec extends Specification {

    static StepEvent ev(long seq) {
        StepEvent.of(StepEventType.THOUGHT, "s-01", "t-1", seq, [i: seq])
    }

    def "按 demand 投递：不足时排队，后续 request 继续（不丢帧）"() {
        given:
        def pub = new RoundEventPublisher()
        def sub = new ManualSubscriber()
        pub.subscribe(sub)

        when: "只请求 1 帧，却发了 3 帧"
        sub.request(1)
        pub.emit(ev(0))
        pub.emit(ev(1))
        pub.emit(ev(2))

        then: "只投递 1 帧，其余排队"
        sub.frames*.seq() == [0L]

        when: "再请求 2"
        sub.request(2)

        then: "补齐排队帧"
        sub.frames*.seq() == [0L, 1L, 2L]

        when: "完成"
        pub.complete()

        then: "收口通知可达"
        sub.completed.await(2, TimeUnit.SECONDS)
    }

    def "cancel 后既不再投递也不再收 complete"() {
        given:
        def pub = new RoundEventPublisher()
        def sub = new ManualSubscriber()
        pub.subscribe(sub)
        sub.request(Long.MAX_VALUE)

        when:
        sub.cancel()
        pub.emit(ev(0))
        pub.complete()

        then:
        sub.frames.isEmpty()
        !sub.completed.await(100, TimeUnit.MILLISECONDS)
    }

    def "request(0) ⇒ onError（非法 demand，规范要求）"() {
        given:
        def pub = new RoundEventPublisher()
        def sub = new ManualSubscriber()
        pub.subscribe(sub)

        when:
        sub.request(0)

        then:
        sub.error.get() instanceof IllegalArgumentException
    }

    def "订阅者 onNext 抛错被隔离（不影响其他订阅者与宿主）"() {
        given:
        def pub = new RoundEventPublisher()
        def bad = new Flow.Subscriber<StepEvent>() {
            void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE) }
            void onNext(StepEvent e) { throw new IllegalStateException("订阅者自己炸了") }
            void onError(Throwable t) {}
            void onComplete() {}
        }
        def good = new ManualSubscriber()
        pub.subscribe(bad)
        pub.subscribe(good)
        good.request(Long.MAX_VALUE)

        when:
        pub.emit(ev(0))
        pub.complete()

        then: "好订阅者照常收帧与收口；异常没有冒出来"
        good.frames*.seq() == [0L]
        good.completed.await(2, TimeUnit.SECONDS)
    }

    def "完成后订阅：先回放缓冲，再收 complete"() {
        given:
        def pub = new RoundEventPublisher()
        pub.emit(ev(0))
        pub.emit(ev(1))
        pub.complete()

        when:
        def sub = new ManualSubscriber()
        pub.subscribe(sub)
        sub.request(Long.MAX_VALUE)

        then:
        sub.frames*.seq() == [0L, 1L]
        sub.completed.await(2, TimeUnit.SECONDS)
    }

    /** 手工 demand 控制的订阅者（收集帧/错误/完成）。 */
    static class ManualSubscriber implements Flow.Subscriber<StepEvent> {
        final List<StepEvent> frames = Collections.synchronizedList(new ArrayList<StepEvent>())
        final CountDownLatch completed = new CountDownLatch(1)
        final AtomicReference<Throwable> error = new AtomicReference<>()
        private Flow.Subscription subscription

        @Override
        void onSubscribe(Flow.Subscription s) { this.subscription = s }

        @Override
        void onNext(StepEvent e) { frames << e }

        @Override
        void onError(Throwable t) { error.set(t); completed.countDown() }

        @Override
        void onComplete() { completed.countDown() }

        void request(long n) { subscription.request(n) }

        void cancel() { subscription.cancel() }
    }
}
