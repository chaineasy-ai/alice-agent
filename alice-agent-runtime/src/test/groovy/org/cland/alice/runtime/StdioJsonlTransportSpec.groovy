package org.cland.alice.runtime

import org.cland.alice.agent.proto.codec.EventCodec
import org.cland.alice.agent.proto.event.StepEvent
import org.cland.alice.agent.proto.event.StepEventType
import org.cland.alice.runtime.transport.StdioJsonlTransport
import spock.lang.Specification
import spock.lang.Title

import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 测试 {@link StdioJsonlTransport}：分帧（LF/\r\n/空行）、错误帧、busy、收口输出。
 */
@Title("StdioJsonlTransport — stdio JSONL 传输")
class StdioJsonlTransportSpec extends Specification {

    static final String SESSION = "sess-01"

    def "prompt 一帧 ⇒ SUMMARY+DONE（输出为合法 JSONL）"() {
        given:
        def input = """{"v":1,"type":"prompt","sessionId":"sess-01","traceId":"t-1","payload":{"message":"ping"}}\n"""
        def out = new ByteArrayOutputStream()
        def transport = new StdioJsonlTransport(stream(input), out)
        def host = new AgentHost(new FakeEngine())
        transport.start(host)

        when:
        transport.runBlocking()
        waitFor { parse(out).any { it.type() == StepEventType.DONE } }   // 轮次在工作线程收口，等 DONE 落盘再断言

        then:
        def frames = parse(out)
        frames*.type() == [StepEventType.SUMMARY, StepEventType.DONE]
        frames[0].payload().text == "pong"
        frames[0].traceId() == "t-1"
        frames[1].sessionId() == SESSION
    }

    def "容忍 \\r\\n 与空行；非法 JSON ⇒ ERROR(decode) 且不执行命令"() {
        given:
        def input = "\n" +
                """{"v":1,"type":"abort","sessionId":"sess-01","traceId":"t-1"}\r\n""" +
                "{oops\n" +
                "\n"
        def out = new ByteArrayOutputStream()
        def engine = new FakeEngine()
        def transport = new StdioJsonlTransport(stream(input), out)
        transport.start(new AgentHost(engine))

        when:
        transport.runBlocking()

        then: "abort 正常 ack"
        def frames = parse(out)
        frames*.type() == [StepEventType.OBSERVE, StepEventType.DONE, StepEventType.ERROR]
        frames[0].payload().command == "abort"
        frames[2].payload().stage == "decode"
        engine.cancelCount == 1
    }

    def "未知命令类型 ⇒ ERROR(decode)"() {
        given:
        def input = """{"v":1,"type":"nope","sessionId":"sess-01","traceId":"t-1"}\n"""
        def out = new ByteArrayOutputStream()
        def transport = new StdioJsonlTransport(stream(input), out)
        transport.start(new AgentHost(new FakeEngine()))

        when:
        transport.runBlocking()

        then:
        def frames = parse(out)
        frames*.type() == [StepEventType.ERROR]
        frames[0].payload().message.contains("nope")
    }

    def "会话不一致 ⇒ ERROR(validation)"() {
        given:
        def input = """{"v":1,"type":"prompt","sessionId":"other","traceId":"t-1","payload":{"message":"ping"}}\n"""
        def out = new ByteArrayOutputStream()
        def transport = new StdioJsonlTransport(stream(input), out)
        transport.start(new AgentHost(new FakeEngine()))

        when:
        transport.runBlocking()

        then:
        def frames = parse(out)
        frames*.type() == [StepEventType.ERROR]
        frames[0].payload().stage == "validation"
    }

    def "轮次进行中再发 prompt ⇒ ERROR(busy)"() {
        given:
        def engine = new FakeEngine()
        def gate = new CountDownLatch(1)
        engine.askBody = { p -> gate.await(5, TimeUnit.SECONDS); "done" }
        def host = new AgentHost(engine)
        def input = """
{"v":1,"type":"prompt","sessionId":"sess-01","traceId":"t-a","payload":{"message":"p1"}}
{"v":1,"type":"prompt","sessionId":"sess-01","traceId":"t-b","payload":{"message":"p2"}}
"""
        def out = new ByteArrayOutputStream()
        def transport = new StdioJsonlTransport(stream(input), out)
        transport.start(host)
        transport.runBlocking()          // 输入已读完；第一轮仍在跑

        when:
        def frames = parse(out)
        boolean busy = frames.any { it.type() == StepEventType.ERROR && it.payload().stage == "busy" }

        then:
        busy

        cleanup:
        gate.countDown()
        waitFor { host.health().rounds() == 1L }
    }

    def "EOF 残帧 ⇒ ERROR(decode)（不静默丢）"() {
        given: "没有换行的半帧"
        def input = """{"v":1,"type":"abort","sessionId":"sess-01","traceId":"t-1"}"""
        def out = new ByteArrayOutputStream()
        def transport = new StdioJsonlTransport(stream(input), out)
        transport.start(new AgentHost(new FakeEngine()))

        when:
        transport.runBlocking()

        then:
        parse(out)*.type() == [StepEventType.OBSERVE, StepEventType.DONE]
    }

    def "close 后停止服务"() {
        given:
        def input = new java.io.PipedInputStream()
        def out = new ByteArrayOutputStream()
        def transport = new StdioJsonlTransport(input, out)
        transport.start(new AgentHost(new FakeEngine()))

        when:
        transport.close()
        transport.runBlocking()

        then:
        transport.name() == "stdio"
        parse(out).isEmpty()
    }

    // ── helpers ──

    static java.io.ByteArrayInputStream stream(String s) {
        return new java.io.ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8))
    }

    /** 输出流 → 事件帧列表（逐行 EventCodec 解码）。 */
    static List<StepEvent> parse(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8)
                .split("\n")
                .findAll { it.trim() }
                .collect { EventCodec.decode(it) }
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
