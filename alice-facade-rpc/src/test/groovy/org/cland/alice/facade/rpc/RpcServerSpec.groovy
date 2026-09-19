package org.cland.alice.facade.rpc

import org.cland.alice.agent.proto.ControlCmd
import org.cland.alice.agent.proto.ExecutionCmd
import org.cland.alice.agent.proto.codec.CommandCodec
import org.cland.alice.agent.proto.codec.EventCodec
import org.cland.alice.agent.proto.event.StepEvent
import org.cland.alice.agent.proto.event.StepEventType
import org.cland.alice.runtime.AgentHost
import spock.lang.Specification
import spock.lang.Title

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 测试 {@link RpcServer}：五路由 + SSE + 错误映射（400/404/409）。
 *
 * 请求体 = 协议 v1 命令信封（与 stdio 同构）；SSE 帧 = StepEvent v1。
 */
@Title("RpcServer — HTTP RPC 2.0")
class RpcServerSpec extends Specification {

    static final String SESSION = "sess-01"

    TestEngine engine
    AgentHost host
    RpcServer server
    HttpClient client

    def setup() {
        engine = new TestEngine()
        host = new AgentHost(engine)
        server = new RpcServer(0)
        server.start(host)
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    }

    def cleanup() {
        server.close()
    }

    // ========================================================================
    // health
    // ========================================================================

    def "GET /health 返回宿主快照"() {
        when:
        def r = get("/health")

        then:
        r.statusCode() == 200
        r.body().contains("\"sessionId\":\"sess-01\"")
        r.body().contains("\"roundActive\":false")
        r.body().contains("\"lastUsage\":")
    }

    // ========================================================================
    // chat/stream（SSE）
    // ========================================================================

    def "POST /chat/stream：SSE 帧到 DONE（含 payload 保真）"() {
        given:
        engine.askBody = { p -> engine.fireThought("想一下"); return "pong" }

        when:
        def r = post("/chat/stream", envelope(new ExecutionCmd.AcquireGoalCmd("ping", SESSION, "t-1")))

        then:
        r.statusCode() == 200
        r.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")

        and: "帧序列：THOUGHT → SUMMARY → DONE"
        def frames = parseSse(r.body())
        frames*.type() == [StepEventType.THOUGHT, StepEventType.SUMMARY, StepEventType.DONE]
        frames[0].payload().text == "想一下"
        frames[1].payload().text == "pong"
        engine.prompts == ["ping"]

        and: "宿主快照已更新"
        host.health().rounds() == 1L
    }

    def "POST /chat/stream 收到非 prompt ⇒ 400"() {
        when:
        def r = post("/chat/stream", envelope(new ControlCmd.AbortCmd(SESSION, "t-1")))

        then:
        r.statusCode() == 400
        r.body().contains("只接受 type=prompt")
    }

    // ========================================================================
    // steer / interrupt / session
    // ========================================================================

    def "POST /chat/steer ⇒ 202 且投递引擎"() {
        when:
        def r = post("/chat/steer", envelope(new ControlCmd.SteerCmd("先回这条", SESSION, "t-2")))

        then:
        r.statusCode() == 202
        r.body().contains("\"queued\":true")
        engine.feedbacks == ["先回这条"]
    }

    def "POST /chat/interrupt ⇒ 200 且 cancel 被调用"() {
        when:
        def r = post("/chat/interrupt", envelope(new ControlCmd.AbortCmd(SESSION, "t-3")))

        then:
        r.statusCode() == 200
        engine.cancelCount == 1
    }

    def "POST /session (new) ⇒ 200 且清上下文"() {
        when:
        def r = post("/session", envelope(new ControlCmd.ResetSessionCmd(SESSION, "t-4")))

        then:
        r.statusCode() == 200
        engine.cleared
    }

    def "POST /session 收到 resume 时 runtime 暂不支持 ⇒ 500（可读原因）"() {
        when:
        def r = post("/session", envelope(new ControlCmd.ResumeSessionCmd(SESSION, "t-5")))

        then: "runtime v1 不支持 resume ⇒ 校验异常 → 400"
        r.statusCode() == 400
        r.body().contains("暂不支持")
    }

    // ========================================================================
    // 错误映射
    // ========================================================================

    def "非法 JSON ⇒ 400"() {
        when:
        def r = post("/chat/stream", "{oops")

        then:
        r.statusCode() == 400
        r.body().contains("不是合法 JSON")
    }

    def "未知路由 ⇒ 404"() {
        when:
        def r = post("/nope", "{}")

        then:
        r.statusCode() == 404
    }

    def "轮次进行中再发 prompt ⇒ 409（一轮一锁）"() {
        given:
        def gate = new CountDownLatch(1)
        engine.askBody = { p -> gate.await(5, TimeUnit.SECONDS); return "done" }
        def first = client.sendAsync(
                HttpRequest.newBuilder(URI.create(base() + "/chat/stream"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(
                                envelope(new ExecutionCmd.AcquireGoalCmd("p1", SESSION, "t-a"))))
                        .build(),
                HttpResponse.BodyHandlers.ofString())
        waitFor { engine.prompts.size() == 1 }

        when:
        def r = post("/chat/stream", envelope(new ExecutionCmd.AcquireGoalCmd("p2", SESSION, "t-b")))

        then:
        r.statusCode() == 409
        r.body().contains("已有轮次")

        cleanup:
        gate.countDown()
        first.get(10, TimeUnit.SECONDS)
    }

    def "会话不一致 ⇒ 400（防跨会话串轮）"() {
        when:
        def r = post("/chat/stream", envelope(new ExecutionCmd.AcquireGoalCmd("ping", "other", "t-1")))

        then:
        r.statusCode() == 400
        r.body().contains("会话不一致")
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    String base() {
        return "http://127.0.0.1:" + server.port() + "/api/v1"
    }

    static String envelope(cmd) {
        return CommandCodec.encode(cmd as org.cland.alice.agent.proto.AgentCommand)
    }

    HttpResponse<String> post(String path, String body) {
        return client.send(
                HttpRequest.newBuilder(URI.create(base() + path))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString())
    }

    HttpResponse<String> get(String path) {
        return client.send(
                HttpRequest.newBuilder(URI.create(base() + path))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString())
    }

    /** SSE 文本 → StepEvent 列表。 */
    static List<StepEvent> parseSse(String body) {
        return body.split("\n\n")
                .collect { it.trim() }
                .findAll { it.startsWith("data: ") }
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
