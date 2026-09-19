package org.cland.alice.runtime

import org.cland.alice.agent.proto.CapabilityCmd
import org.cland.alice.agent.proto.ControlCmd
import org.cland.alice.agent.proto.ExecutionCmd
import org.cland.alice.agent.proto.event.StepEvent
import org.cland.alice.agent.proto.event.StepEventType
import org.cland.alice.agent.proto.port.CommandValidationException
import org.cland.alice.agent.proto.port.DispatcherBusyException
import spock.lang.Specification
import spock.lang.Title

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/**
 * 测试 {@link AgentHost}：会话语义（一轮一锁 / 事件映射 / 控制类 ack / 健康快照）。
 *
 * 用 {@link FakeEngine} 打桩，不依赖 core 实现。
 */
@Title("AgentHost — 会话语义")
class AgentHostSpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE   = "t-1"

    // ========================================================================
    // 轮次：事件映射 + 收口（SUMMARY/DONE + usage）
    // ========================================================================

    def "prompt 轮：内核事件映射为 StepEvent，末尾 SUMMARY+DONE（含 usage）"() {
        given:
        def engine = new FakeEngine()
        engine.usage = new StepEvent.Usage(10L, 2L, 0L, 0L, 12L, 0.0005d)
        engine.askBody = { p ->
            engine.fireThought("想一想")
            engine.fireAction("bash", [command: "ls"])
            engine.fireObserve("out", "ok", 12L)
            "pong"
        }
        def host = new AgentHost(engine)

        when:
        def frames = collect(host.dispatch(new ExecutionCmd.AcquireGoalCmd("ping", SESSION, TRACE)))

        then: "事件类型序列（thought → action → observe → summary → done）"
        frames*.type() == [StepEventType.THOUGHT, StepEventType.TOOL_CALL, StepEventType.TOOL_RESULT,
                           StepEventType.SUMMARY, StepEventType.DONE]

        and: "载荷保真"
        frames[0].payload().text == "想一想"
        frames[1].payload().tool == "bash"
        frames[1].payload().args.command == "ls"
        frames[2].payload().elapsedMs == 12L
        frames[3].payload().text == "pong"

        and: "seq 单调且不重复；DONE 带 usage"
        frames*.seq() == frames*.seq().sort(false)
        frames*.seq().unique().size() == frames.size()
        frames[4].usage().totalTokens == 12L
        engine.prompts == ["ping"]

        and: "健康快照"
        def h = host.health()
        h.sessionId() == SESSION
        h.rounds() == 1L
        !h.roundActive()
    }

    def "ask 抛错 ⇒ ERROR 帧 + 仍以 DONE 收口（会话保留）"() {
        given:
        def engine = new FakeEngine()
        engine.askBody = { p -> throw new IllegalStateException("模型炸了") }
        def host = new AgentHost(engine)

        when:
        def frames = collect(host.dispatch(new ExecutionCmd.AcquireGoalCmd("ping", SESSION, TRACE)))

        then:
        frames*.type() == [StepEventType.ERROR, StepEventType.DONE]
        frames[0].payload().message.contains("模型炸了")
        host.health().rounds() == 1L
    }

    // ========================================================================
    // 一轮一锁
    // ========================================================================

    def "轮次进行中再发 prompt ⇒ DispatcherBusyException（一轮一锁）"() {
        given:
        def engine = new FakeEngine()
        def gate = new CountDownLatch(1)
        engine.askBody = { p -> gate.await(5, TimeUnit.SECONDS); "done" }
        def host = new AgentHost(engine)
        def first = host.dispatch(new ExecutionCmd.AcquireGoalCmd("p1", SESSION, "t-a"))
        def firstDone = new CountDownLatch(1)
        first.subscribe(subscriber([], firstDone))
        waitFor { engine.prompts.size() == 1 }

        when:
        host.dispatch(new ExecutionCmd.AcquireGoalCmd("p2", SESSION, "t-b"))

        then:
        def e = thrown(DispatcherBusyException)
        e.message.contains("已有轮次")

        cleanup:
        gate.countDown()
        assert firstDone.await(5, TimeUnit.SECONDS)
    }

    def "轮次进行中 steer 不抢轮锁（忙时插话）"() {
        given:
        def engine = new FakeEngine()
        def gate = new CountDownLatch(1)
        engine.askBody = { p -> gate.await(5, TimeUnit.SECONDS); "done" }
        def host = new AgentHost(engine)
        def first = host.dispatch(new ExecutionCmd.AcquireGoalCmd("long", SESSION, "t-a"))
        def firstDone = new CountDownLatch(1)
        first.subscribe(subscriber([], firstDone))
        waitFor { engine.prompts.size() == 1 }

        when:
        def ack = collect(host.dispatch(new ControlCmd.SteerCmd("先回这条", SESSION, "t-b")), 3000)

        then:
        ack*.type() == [StepEventType.OBSERVE, StepEventType.DONE]
        ack[0].payload().command == "steer"
        ack[0].payload().duringRound == true
        engine.feedbacks == ["先回这条"]

        cleanup:
        gate.countDown()
        assert firstDone.await(5, TimeUnit.SECONDS)
    }

    // ========================================================================
    // 控制类 ack
    // ========================================================================

    def "abort 调引擎 cancel（会话保留）"() {
        given:
        def engine = new FakeEngine()
        def host = new AgentHost(engine)

        when:
        def ack = collect(host.dispatch(new ControlCmd.AbortCmd(SESSION, TRACE)))

        then:
        ack*.type() == [StepEventType.OBSERVE, StepEventType.DONE]
        ack[0].payload().command == "abort"
        engine.cancelCount == 1
    }

    def "new 清上下文（并先 cancel 在跑的轮次）"() {
        given:
        def engine = new FakeEngine()
        def host = new AgentHost(engine)

        when:
        collect(host.dispatch(new ControlCmd.ResetSessionCmd(SESSION, TRACE)))

        then:
        engine.cleared
        engine.cancelCount == 1
    }

    def "feedback 投递引擎（HITL）"() {
        given:
        def engine = new FakeEngine()
        def host = new AgentHost(engine)

        when:
        collect(host.dispatch(new ControlCmd.FeedbackCmd("短一点", SESSION, TRACE)))

        then:
        engine.feedbacks == ["短一点"]
    }

    def "compact / context 返回文本帧"() {
        given:
        def engine = new FakeEngine()
        def host = new AgentHost(engine)

        when:
        def compact = collect(host.dispatch(new ControlCmd.CompactContextCmd(SESSION, TRACE)))
        def context = collect(host.dispatch(new ControlCmd.ViewContextCmd(SESSION, TRACE)))

        then:
        compact*.type() == [StepEventType.SUMMARY, StepEventType.DONE]
        compact[0].payload().text == "compacted-ok"
        context*.type() == [StepEventType.OBSERVE, StepEventType.DONE]
        context[0].payload().text == "| ctx |"
        engine.compactCalls.size() == 1
    }

    // ========================================================================
    // 契约校验
    // ========================================================================

    def "v1 不支持的命令 ⇒ CommandValidationException"() {
        given:
        def host = new AgentHost(new FakeEngine())

        when:
        host.dispatch(new CapabilityCmd.ReloadKernelCmd(SESSION, TRACE))

        then:
        def e = thrown(CommandValidationException)
        e.message.contains("暂不支持")
    }

    def "会话不一致 ⇒ CommandValidationException（防跨会话串轮）"() {
        given:
        def host = new AgentHost(new FakeEngine())

        when:
        host.dispatch(new ExecutionCmd.AcquireGoalCmd("ping", "other-session", TRACE))

        then:
        def e = thrown(CommandValidationException)
        e.message.contains("会话不一致")
    }

    // ========================================================================
    // 回放：先执行后订阅不丢帧
    // ========================================================================

    def "晚订阅也能拿到全量回放（含 DONE）"() {
        given:
        def engine = new FakeEngine()
        engine.askBody = { p -> engine.fireThought("t"); "ok" }
        def host = new AgentHost(engine)

        when: "先跑完（无任何订阅者）"
        host.dispatch(new ExecutionCmd.AcquireGoalCmd("ping", SESSION, TRACE))
        waitFor { host.health().rounds() == 1L }

        and: "再订阅"
        def frames = collect(host.dispatch(new ControlCmd.AbortCmd(SESSION, "t-2")))

        then: "控制类 ack 同样可回放"
        frames*.type() == [StepEventType.OBSERVE, StepEventType.DONE]
    }

    def "轮次事件在完成后订阅也可回放"() {
        given:
        def engine = new FakeEngine()
        engine.askBody = { p -> engine.fireThought("t"); "ok" }
        def host = new AgentHost(engine)
        def publisher = host.dispatch(new ExecutionCmd.AcquireGoalCmd("ping", SESSION, TRACE))
        waitFor { host.health().rounds() == 1L }

        when:
        def frames = collect(publisher)

        then:
        frames*.type() == [StepEventType.THOUGHT, StepEventType.SUMMARY, StepEventType.DONE]
    }


    // ========================================================================
    // 边界：异常隔离与缺省
    // ========================================================================

    def "lastUsage 抛错时按零用量收口"() {
        given:
        def engine = new FakeEngine() {
            @Override
            StepEvent.Usage lastUsage() { throw new IllegalStateException("usage 不可用") }
        }
        def host = new AgentHost(engine)

        when:
        def frames = collect(host.dispatch(new ExecutionCmd.AcquireGoalCmd("ping", SESSION, TRACE)))

        then:
        frames.last().type() == StepEventType.DONE
        frames.last().usage() == StepEvent.Usage.zero()
    }

    def "compact 抛错 ⇒ ERROR 帧（不吞）"() {
        given:
        def engine = new FakeEngine() {
            @Override
            String compactContext() { throw new IllegalStateException("WAL 未注入") }
        }
        def host = new AgentHost(engine)

        when:
        def frames = collect(host.dispatch(new ControlCmd.CompactContextCmd(SESSION, TRACE)))

        then:
        frames*.type() == [StepEventType.ERROR, StepEventType.DONE]
        frames[0].payload().message.contains("WAL 未注入")
    }

    def "steer 投递失败 ⇒ ERROR 帧"() {
        given:
        def engine = new FakeEngine() {
            @Override
            void injectFeedback(String message) { throw new IllegalStateException("无在跑的轮次") }
        }
        def host = new AgentHost(engine)

        when:
        def frames = collect(host.dispatch(new ControlCmd.SteerCmd("插话", SESSION, TRACE)))

        then:
        frames*.type() == [StepEventType.ERROR, StepEventType.DONE]
        frames[0].payload().command == "steer"
    }

    def "宿主会话为空时不校验（照常执行）"() {
        given:
        def engine = new FakeEngine()
        engine.session = ""
        def host = new AgentHost(engine)

        when:
        def frames = collect(host.dispatch(new ExecutionCmd.AcquireGoalCmd("ping", "any", TRACE)))

        then:
        frames.last().type() == StepEventType.DONE
    }

    def "HostHealth 的 usage 为空时取零缺省"() {
        expect:
        new HostHealth("s", false, 0L, 0L, null, java.time.Instant.now()).lastUsage() == StepEvent.Usage.zero()
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** 订阅并阻塞收集到收口。 */
    static List<StepEvent> collect(Flow.Publisher<StepEvent> pub, long timeoutMs = 5000) {
        def frames = Collections.synchronizedList(new ArrayList<StepEvent>())
        def done = new CountDownLatch(1)
        pub.subscribe(subscriber(frames, done))
        assert done.await(timeoutMs, TimeUnit.MILLISECONDS): "未在 ${timeoutMs}ms 内收口"
        return new ArrayList<>(frames)
    }

    static Flow.Subscriber<StepEvent> subscriber(List<StepEvent> frames, CountDownLatch done) {
        return new Flow.Subscriber<StepEvent>() {
            @Override
            void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE) }

            @Override
            void onNext(StepEvent e) { frames << e }

            @Override
            void onError(Throwable t) { done.countDown() }

            @Override
            void onComplete() { done.countDown() }
        }
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
