package org.cland.alice.facade.rpc

import org.cland.alice.agent.proto.ControlCmd
import org.cland.alice.agent.proto.codec.CommandCodec
import org.cland.alice.agent.proto.codec.EventCodec
import org.cland.alice.agent.proto.event.StepEvent
import org.cland.alice.agent.proto.event.StepEventType
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
 * 测试 {@link AliceRpcFacade}（bootstrap SPI 实现）：stdio 模式（注入流）+ HTTP 模式（随机端口）。
 *
 * 用 `--model test-model --no-wal` 避免触网/写 home 目录（装配路径仍然真实）。
 */
@Title("AliceRpcFacade — SPI 门面")
class AliceRpcFacadeSpec extends Specification {

    static final String SESSION = "sess-it"

    def "name = rpc（bootstrap --facade rpc）"() {
        expect:
        new AliceRpcFacade().name() == "rpc"
    }

    def "stdio 模式（--mode rpc）：一帧 abort ⇒ ack 收口，退出码 0"() {
        given:
        def input = (CommandCodec.encode(new ControlCmd.AbortCmd(SESSION, "t-1")) + "\n")
                .getBytes(StandardCharsets.UTF_8)
        def out = new ByteArrayOutputStream()
        def facade = new AliceRpcFacade()

        when:
        int code = facade.launch(
                ["--mode", "rpc", "--session", SESSION, "--model", "test-model", "--no-wal"] as String[],
                new ByteArrayInputStream(input), out)

        then:
        code == 0
        def frames = out.toString(StandardCharsets.UTF_8).split("\n").findAll { it.trim() }
                .collect { EventCodec.decode(it) }
        frames*.type() == [StepEventType.OBSERVE, StepEventType.DONE]
        frames[0].payload().command == "abort"
    }

    def "HTTP 模式（--port 0）：/health 可达并可优雅停服"() {
        given:
        def facade = new AliceRpcFacade()
        def code = new java.util.concurrent.atomic.AtomicInteger(-1)
        def thread = Thread.ofVirtual().start {
            code.set(facade.launch(
                    ["--port", "0", "--session", SESSION, "--model", "test-model", "--no-wal"] as String[]))
        }
        waitFor({ facade.server() != null && facade.server().port() > 0 }, 30_000)

        when:
        def port = facade.server().port()
        def client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        def resp = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString())

        then:
        resp.statusCode() == 200
        resp.body().contains("\"sessionId\":\"" + SESSION + "\"")
        resp.body().contains("\"roundActive\":false")

        cleanup:
        facade.shutdown()
        thread.join(5_000)
        assert !thread.isAlive()
        assert code.get() == 0
    }

    static void waitFor(Closure<Boolean> cond, long timeoutMs = 5000) {
        def deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond.call()) return
            Thread.sleep(20)
        }
        throw new AssertionError("条件未在 ${timeoutMs}ms 内满足")
    }
}
