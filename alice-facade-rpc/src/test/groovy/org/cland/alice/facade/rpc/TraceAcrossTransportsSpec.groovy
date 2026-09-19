package org.cland.alice.facade.rpc

import org.cland.alice.agent.proto.ControlCmd
import org.cland.alice.agent.proto.ExecutionCmd
import org.cland.alice.agent.proto.codec.CommandCodec
import org.cland.alice.agent.proto.codec.EventCodec
import org.cland.alice.agent.proto.event.StepEvent
import org.cland.alice.agent.proto.event.StepEventType
import org.cland.alice.runtime.AgentHost
import org.cland.alice.runtime.transport.InProcessTransport
import org.cland.alice.runtime.transport.StdioJsonlTransport
import spock.lang.Specification
import spock.lang.Title

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * E2：同一 `traceId` 在三路（InProcess / stdio / HTTP）都能回放完整时间线。
 *
 * 口径：帧序列（类型）一致、`traceId` 贯穿、`seq` 单调——三条传输共享同一契约。
 */
@Title("E2 — 三路传输的 traceId 时间线")
class TraceAcrossTransportsSpec extends Specification {

    static final String SESSION = "sess-e2"
    static final String TRACE = "trace-e2"

    def "同一命令经 InProcess / stdio / HTTP：帧序列一致且 traceId 贯穿"() {
        given: "同一个轮次在三个独立宿主上跑（同参数）"
        def engine = new TestEngine()
        engine.session = SESSION
        engine.askBody = { p -> engine.fireThought("想一下"); return "pong" }
        def host = new AgentHost(engine)
        def cmd = new ExecutionCmd.AcquireGoalCmd("ping", SESSION, TRACE)

        when: "① InProcess"
        def inprocTransport = new InProcessTransport()
        inprocTransport.start(host)
        def inprocFrames = inprocTransport.await(cmd, 5000)

        and: "② stdio（注入流）"
        def out = new ByteArrayOutputStream()
        def stdio = new StdioJsonlTransport(
                new ByteArrayInputStream((CommandCodec.encode(cmd) + "\n").getBytes(StandardCharsets.UTF_8)), out)
        stdio.start(host)
        stdio.runBlocking()
        waitFor { parseLines(out.toString(StandardCharsets.UTF_8)).any { it.type() == StepEventType.DONE } }
        def stdioFrames = parseLines(out.toString(StandardCharsets.UTF_8))

        and: "③ HTTP（SSE）"
        def server = new RpcServer(0)
        server.start(host)
        def client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        def resp = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/chat/stream"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(CommandCodec.encode(cmd)))
                        .build(),
                HttpResponse.BodyHandlers.ofString())
        def httpFrames = parseSse(resp.body())
        server.close()

        then: "三路帧序列一致（同契约 ✓）"
        def expected = [StepEventType.THOUGHT, StepEventType.SUMMARY, StepEventType.DONE]
        inprocFrames*.type() == expected
        stdioFrames*.type() == expected
        httpFrames*.type() == expected

        and: "traceId 贯穿三路、seq 单调"
        [inprocFrames, stdioFrames, httpFrames].every { frames ->
            frames.every { it.traceId() == TRACE } &&
                    frames*.seq() == frames*.seq().sort(false) &&
                    frames*.seq().unique().size() == frames.size()
        }

        and: "SUMMARY 文本一致（同一轮语义）"
        [inprocFrames, stdioFrames, httpFrames].every { frames ->
            frames.find { it.type() == StepEventType.SUMMARY }.payload().text == "pong"
        }

        cleanup:
        inprocTransport.close()
        stdio.close()
    }

    // ── helpers ──

    static List<StepEvent> parseLines(String text) {
        return text.readLines().findAll { it.trim() }.collect { EventCodec.decode(it) }
    }

    static List<StepEvent> parseSse(String body) {
        return body.split("\n\n").collect { it.trim() }.findAll { it.startsWith("data: ") }
                .collect { EventCodec.decode(it.substring("data: ".length())) }
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
