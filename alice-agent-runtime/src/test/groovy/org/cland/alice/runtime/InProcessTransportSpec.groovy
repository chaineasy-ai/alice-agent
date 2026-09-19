package org.cland.alice.runtime

import org.cland.alice.agent.proto.AgentCommand
import org.cland.alice.agent.proto.ControlCmd
import org.cland.alice.agent.proto.ExecutionCmd
import org.cland.alice.agent.proto.event.StepEvent
import org.cland.alice.agent.proto.event.StepEventType
import org.cland.alice.agent.proto.port.AgentCommandDispatcher
import org.cland.alice.runtime.transport.InProcessTransport
import spock.lang.Specification
import spock.lang.Title

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/**
 * 测试 {@link InProcessTransport}：直调 / 生命周期 / await 收敛 / 失败传播。
 */
@Title("InProcessTransport — 进程内直调")
class InProcessTransportSpec extends Specification {

    static final String SESSION = "sess-01"

    def "未 start 时 dispatch 抛 IllegalStateException"() {
        when:
        new InProcessTransport().dispatch(new ControlCmd.AbortCmd(SESSION, "t-1"))

        then:
        def e = thrown(IllegalStateException)
        e.message.contains("未启动")
    }

    def "start 后直调宿主：await 收集到 DONE；close 后不可用"() {
        given:
        def transport = new InProcessTransport()
        transport.start(new AgentHost(new FakeEngine()))

        expect:
        transport.name() == "inprocess"

        when:
        def frames = transport.await(new ExecutionCmd.AcquireGoalCmd("ping", SESSION, "t-1"), 5000)

        then:
        frames*.type() == [StepEventType.SUMMARY, StepEventType.DONE]
        frames[0].payload().text == "pong"

        when: "close 后拒绝服务"
        transport.close()
        transport.dispatch(new ControlCmd.AbortCmd(SESSION, "t-2"))

        then:
        thrown(IllegalStateException)
    }

    def "await 超时抛 IllegalStateException（未收口）"() {
        given:
        def engine = new FakeEngine()
        def gate = new CountDownLatch(1)
        engine.askBody = { p -> gate.await(5, TimeUnit.SECONDS); "late" }
        def transport = new InProcessTransport()
        transport.start(new AgentHost(engine))

        when:
        transport.await(new ExecutionCmd.AcquireGoalCmd("ping", SESSION, "t-1"), 200)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains("超时")

        cleanup:
        gate.countDown()
    }

    def "订阅出错 ⇒ await 抛 IllegalStateException（轮次失败）"() {
        given: "一个订阅即报错的发布器"
        def dispatcher =
                new AgentCommandDispatcher() {
                    @Override
                    Flow.Publisher<StepEvent> dispatch(AgentCommand cmd) {
                        return new Flow.Publisher<StepEvent>() {
                            @Override
                            void subscribe(Flow.Subscriber<? super StepEvent> s) {
                                s.onSubscribe(
                                        new Flow.Subscription() {
                                            @Override
                                            void request(long n) {
                                                s.onError(new RuntimeException("boom"))
                                            }

                                            @Override
                                            void cancel() {}
                                        })
                            }
                        }
                    }
                }
        def transport = new InProcessTransport()
        transport.start(dispatcher)

        when:
        transport.await(new ControlCmd.AbortCmd(SESSION, "t-1"), 1000)

        then:
        def e = thrown(IllegalStateException)
        e.message == "轮次失败"
        e.cause.message == "boom"
    }
}
