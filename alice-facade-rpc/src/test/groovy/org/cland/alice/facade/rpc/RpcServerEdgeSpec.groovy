package org.cland.alice.facade.rpc

import org.cland.alice.agent.proto.AgentCommand
import org.cland.alice.agent.proto.ControlCmd
import org.cland.alice.agent.proto.ExecutionCmd
import org.cland.alice.agent.proto.codec.CommandCodec
import org.cland.alice.agent.proto.codec.EventCodec
import org.cland.alice.agent.proto.event.StepEvent
import org.cland.alice.agent.proto.event.StepEventType
import org.cland.alice.agent.proto.port.AgentCommandDispatcher
import org.cland.alice.runtime.AgentHost
import spock.lang.Specification
import spock.lang.Title

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Flow

/**
 * RpcServer 边界：通用路由 / 非 AgentHost 健康面 / SSE onError / 失败收口 / 类型校验 / 生命周期。
 */
@Title("RpcServer — 边界与错误路径")
class RpcServerEdgeSpec extends Specification {

    static final String SESSION = "sess-01"

    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    // ========================================================================
    // 通用路由 /api/v1/command
    // ========================================================================

    def "POST /command + prompt ⇒ SSE"() {
        given:
        def server = start(new AgentHost(new TestEngine()))

        when:
        def r = post(server, "/command", envelope(new ExecutionCmd.AcquireGoalCmd("ping", SESSION, "t-1")))

        then:
        r.statusCode() == 200
        parseSse(r.body())*.type() == [StepEventType.SUMMARY, StepEventType.DONE]

        cleanup:
        server.close()
    }

    def "POST /command + steer ⇒ 200"() {
        given:
        def server = start(new AgentHost(new TestEngine()))

        when:
        def r = post(server, "/command", envelope(new ControlCmd.SteerCmd("插话", SESSION, "t-2")))

        then:
        r.statusCode() == 200

        cleanup:
        server.close()
    }

    // ========================================================================
    // 类型校验（typed/oneShot 路由）
    // ========================================================================

    def "POST /session 收到不属于本路由的类型（abort）⇒ 400"() {
        given:
        def server = start(new AgentHost(new TestEngine()))

        when:
        def r = post(server, "/session", envelope(new ControlCmd.AbortCmd(SESSION, "t-3")))

        then:
        r.statusCode() == 400
        r.body().contains("只接受 type=")

        cleanup:
        server.close()
    }

    def "POST /chat/steer 收到 prompt ⇒ 400"() {
        given:
        def server = start(new AgentHost(new TestEngine()))

        when:
        def r = post(server, "/chat/steer", envelope(new ExecutionCmd.AcquireGoalCmd("ping", SESSION, "t-4")))

        then:
        r.statusCode() == 400

        cleanup:
        server.close()
    }

    def "POST /chat/interrupt 收到 steer ⇒ 400"() {
        given:
        def server = start(new AgentHost(new TestEngine()))

        when:
        def r = post(server, "/chat/interrupt", envelope(new ControlCmd.SteerCmd("x", SESSION, "t-5")))

        then:
        r.statusCode() == 400

        cleanup:
        server.close()
    }

    // ========================================================================
    // 健康面：非 AgentHost 的 Dispatcher（fallback 分支）+ JSON 转义
    // ========================================================================

    def "GET /health：Dispatcher 非 AgentHost ⇒ fallback 快照"() {
        given:
        def server = start(new FixedDispatcher({ cmd -> publisher([], null) }))

        when:
        def r = get(server, "/health")

        then:
        r.statusCode() == 200
        r.body().contains("\"startedAt\":null")
        r.body().contains("\"rounds\":0")

        cleanup:
        server.close()
    }

    def "GET /health：sessionId 含引号/换行 ⇒ JSON 转义"() {
        given:
        def engine = new TestEngine()
        engine.session = "bad\"id\nline"
        def server = start(new AgentHost(engine))

        when:
        def r = get(server, "/health")

        then:
        r.statusCode() == 200
        r.body().contains("\\\"")
        r.body().contains("\\n")
        !r.body().contains("bad\"id\nline")

        cleanup:
        server.close()
    }

    // ========================================================================
    // 失败路径：SSE onError / 短命令 failIfError
    // ========================================================================

    def "SSE：发布器 onError ⇒ 流内 ERROR 帧"() {
        given:
        def server = start(new FixedDispatcher({ cmd -> publisher([], new IllegalStateException("爆炸")) }))

        when:
        def r = post(server, "/chat/stream", envelope(new ExecutionCmd.AcquireGoalCmd("ping", SESSION, "t-6")))

        then:
        r.statusCode() == 200
        def frames = parseSse(r.body())
        frames*.type() == [StepEventType.ERROR]
        frames[0].payload().message.contains("爆炸")

        cleanup:
        server.close()
    }

    def "短命令：ERROR 帧 ⇒ 500（可读原因）"() {
        given:
        def err = StepEvent.of(StepEventType.ERROR, SESSION, "t-7", 0L, [message: "投递失败"])
        def server = start(new FixedDispatcher({ cmd -> publisher([err], null) }))

        when:
        def r = post(server, "/chat/steer", envelope(new ControlCmd.SteerCmd("x", SESSION, "t-7")))

        then:
        r.statusCode() == 500
        r.body().contains("投递失败")

        cleanup:
        server.close()
    }

    // ========================================================================
    // 生命周期
    // ========================================================================

    def "port() 在 start 前为 -1；close() 幂等"() {
        given:
        def server = new RpcServer(0)

        expect:
        server.name() == "http"
        server.port() == -1

        when:
        server.close()
        server.close()

        then:
        noExceptionThrown()
    }

    // ── helpers ──

    static RpcServer start(AgentCommandDispatcher dispatcher) {
        def server = new RpcServer(0)
        server.start(dispatcher)
        assert server.port() > 0
        return server
    }

    static String base(RpcServer server) {
        return "http://127.0.0.1:" + server.port() + "/api/v1"
    }

    static String envelope(AgentCommand cmd) {
        return CommandCodec.encode(cmd)
    }

    HttpResponse<String> post(RpcServer server, String path, String body) {
        return client.send(
                HttpRequest.newBuilder(URI.create(base(server) + path))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString())
    }

    HttpResponse<String> get(RpcServer server, String path) {
        return client.send(
                HttpRequest.newBuilder(URI.create(base(server) + path))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString())
    }

    static List<StepEvent> parseSse(String body) {
        return body.split("\n\n")
                .collect { it.trim() }
                .findAll { it.startsWith("data: ") }
                .collect { EventCodec.decode(it.substring("data: ".length())) }
    }

    static Flow.Publisher<StepEvent> publisher(List<StepEvent> frames, Throwable error) {
        return new FixedPublisher(frames, error)
    }

    /** 固定帧序列发布器（request 时一次性投递）。 */
    static class FixedPublisher implements Flow.Publisher<StepEvent> {
        private final List<StepEvent> frames
        private final Throwable error

        FixedPublisher(List<StepEvent> frames, Throwable error) {
            this.frames = frames
            this.error = error
        }

        @Override
        void subscribe(Flow.Subscriber<? super StepEvent> s) {
            s.onSubscribe(new Flow.Subscription() {
                @Override
                void request(long n) {
                    frames.each { f -> s.onNext(f) }
                    if (error != null) {
                        s.onError(error)
                    } else {
                        s.onComplete()
                    }
                }

                @Override
                void cancel() {}
            })
        }
    }

    /** 固定行为 Dispatcher（不依赖 core/宿主）。 */
    static class FixedDispatcher implements AgentCommandDispatcher {
        private final Closure<Flow.Publisher<StepEvent>> onDispatch

        FixedDispatcher(Closure<Flow.Publisher<StepEvent>> onDispatch) {
            this.onDispatch = onDispatch
        }

        @Override
        Flow.Publisher<StepEvent> dispatch(AgentCommand cmd) {
            return onDispatch.call(cmd)
        }
    }
}
