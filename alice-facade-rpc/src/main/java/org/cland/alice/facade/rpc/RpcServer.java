/*
 * Alice Agent — HTTP RPC 2.0 服务端（协议适配层）
 *
 * 请求体 = 协议 v1 命令信封（与 stdio 同构）；响应帧 = StepEvent v1（SSE data:）。
 * 只依赖 proto + runtime；Dispatcher 由组合根注入。
 */
package org.cland.alice.facade.rpc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.cland.alice.agent.proto.AgentCommand;
import org.cland.alice.agent.proto.ControlCmd;
import org.cland.alice.agent.proto.ExecutionCmd;
import org.cland.alice.agent.proto.codec.CommandCodec;
import org.cland.alice.agent.proto.codec.EventCodec;
import org.cland.alice.agent.proto.event.StepEvent;
import org.cland.alice.agent.proto.event.StepEventType;
import org.cland.alice.agent.proto.port.AgentCommandDispatcher;
import org.cland.alice.agent.proto.port.CommandValidationException;
import org.cland.alice.agent.proto.port.DispatcherBusyException;
import org.cland.alice.agent.proto.port.SessionTransport;
import org.cland.alice.runtime.AgentHost;
import org.cland.alice.runtime.HostHealth;

/**
 * HTTP RPC 2.0 服务端 —— 把协议契约面暴露给外部调度器/看板/其他 agent。
 *
 * <p>路由（v1）：
 *
 * <pre>
 * POST /api/v1/session          {type:new|resume}         → 200 {"ok":true}
 * POST /api/v1/chat/stream      {type:prompt}             → 200 text/event-stream（StepEvent 帧）
 * POST /api/v1/chat/steer       {type:steer}              → 202 {"ok":true,"queued":true}
 * POST /api/v1/chat/interrupt   {type:abort}              → 200 {"ok":true}
 * POST /api/v1/command          任意命令信封               → 200/202（按命令语义）
 * GET  /api/v1/health                                     → 200 HostHealth JSON
 * </pre>
 *
 * <p>错误映射（协议 §4）：校验 400｜忙 409｜未就绪 503｜其他 500。
 */
public final class RpcServer implements SessionTransport {

  private static final String CT_JSON = "application/json; charset=utf-8";
  private static final String CT_SSE = "text/event-stream; charset=utf-8";
  private static final Set<String> SESSION_TYPES = Set.of("new", "resume");

  private final String host;
  private final int port;

  private volatile AgentCommandDispatcher dispatcher;
  private HttpServer server;
  private ExecutorService executor;

  /** 默认 127.0.0.1:8080。 */
  public RpcServer() {
    this("127.0.0.1", 8080);
  }

  /**
   * @param port 监听端口（0 = 随机空闲端口，测试用）
   */
  public RpcServer(int port) {
    this("127.0.0.1", port);
  }

  /**
   * @param host 绑定地址（默认仅本机；对外暴露请显式指定并置于网关/鉴权之后）
   * @param port 端口（0 = 随机）
   */
  public RpcServer(String host, int port) {
    this.host = Objects.requireNonNull(host, "host must not be null");
    this.port = port;
  }

  @Override
  public String name() {
    return "http";
  }

  /** 实际监听端口（start 后）。 */
  public int port() {
    return server == null ? -1 : server.getAddress().getPort();
  }

  @Override
  public void start(AgentCommandDispatcher dispatcher) throws IOException {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
    server = HttpServer.create(new InetSocketAddress(host, port), 0);
    server.createContext("/api/v1", this::handle);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.start();
  }

  @Override
  public void close() {
    if (server != null) {
      server.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 路由
  // ──────────────────────────────────────────────────────────────────────────

  private void handle(HttpExchange ex) {
    try {
      if (dispatcher == null) {
        respond(ex, 503, "{\"ok\":false,\"error\":\"宿主未就绪\"}");
        return;
      }
      String route = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
      switch (route) {
        case "GET /api/v1/health" -> health(ex);
        case "POST /api/v1/command" -> command(ex);
        case "POST /api/v1/session" -> typed(ex, SESSION_TYPES);
        case "POST /api/v1/chat/stream" -> stream(ex);
        case "POST /api/v1/chat/steer" -> oneShot(ex, "steer");
        case "POST /api/v1/chat/interrupt" -> oneShot(ex, "abort");
        default -> respond(ex, 404, "{\"ok\":false,\"error\":\"not found\"}");
      }
    } catch (CommandValidationException e) {
      safeRespond(ex, 400, errorJson(e.getMessage()));
    } catch (DispatcherBusyException e) {
      safeRespond(ex, 409, errorJson(e.getMessage()));
    } catch (Throwable e) {
      safeRespond(ex, 500, errorJson(String.valueOf(e.getMessage())));
    } finally {
      ex.close();
    }
  }

  private void health(HttpExchange ex) throws IOException {
    AgentCommandDispatcher d = dispatcher;
    String json;
    if (d instanceof AgentHost agentHost) {
      HostHealth h = agentHost.health();
      json =
          "{\"ok\":true,\"sessionId\":"
              + quote(h.sessionId())
              + ",\"roundActive\":"
              + h.roundActive()
              + ",\"rounds\":"
              + h.rounds()
              + ",\"lastRoundMs\":"
              + h.lastRoundMs()
              + ",\"lastUsage\":"
              + usageJson(h.lastUsage())
              + ",\"startedAt\":"
              + quote(String.valueOf(h.startedAt()))
              + "}";
    } else {
      json =
          "{\"ok\":true,\"sessionId\":\"\",\"roundActive\":false,\"rounds\":0,\"lastRoundMs\":0,"
              + "\"lastUsage\":"
              + usageJson(StepEvent.Usage.zero())
              + ",\"startedAt\":null}";
    }
    respond(ex, 200, json);
  }

  /** 通用命令路由：任意信封 → 一轮/ack（按命令语义）。 */
  private void command(HttpExchange ex) throws Exception {
    AgentCommand cmd = readCommand(ex);
    Flow.Publisher<StepEvent> pub = dispatcher.dispatch(cmd);
    if (cmd instanceof ExecutionCmd) {
      streamSse(ex, pub);
      return;
    }
    List<StepEvent> frames = collect(pub, 30_000);
    failIfError(frames);
    respond(ex, 200, "{\"ok\":true}");
  }

  /** 类型受限路由（session：new|resume）。 */
  private void typed(HttpExchange ex, Set<String> allowed) throws Exception {
    AgentCommand cmd = readCommand(ex);
    String type = typeOf(cmd);
    if (!allowed.contains(type)) {
      throw new CommandValidationException("该路由只接受 type=" + allowed + "，收到：" + type);
    }
    List<StepEvent> frames = collect(dispatcher.dispatch(cmd), 30_000);
    failIfError(frames);
    respond(ex, 200, "{\"ok\":true}");
  }

  /** 流式路由（chat/stream：只接受 prompt）。 */
  private void stream(HttpExchange ex) throws Exception {
    AgentCommand cmd = readCommand(ex);
    if (!(cmd instanceof ExecutionCmd)) {
      throw new CommandValidationException("该路由只接受 type=prompt（ExecutionCmd），收到：" + typeOf(cmd));
    }
    streamSse(ex, dispatcher.dispatch(cmd));
  }

  /** 短命令路由（steer/interrupt）：收口后一次性应答。 */
  private void oneShot(HttpExchange ex, String expected) throws Exception {
    AgentCommand cmd = readCommand(ex);
    String type = typeOf(cmd);
    if (!expected.equals(type)) {
      throw new CommandValidationException("该路由只接受 type=" + expected + "，收到：" + type);
    }
    List<StepEvent> frames = collect(dispatcher.dispatch(cmd), 30_000);
    failIfError(frames);
    int code = "steer".equals(expected) ? 202 : 200;
    String body = "steer".equals(expected) ? "{\"ok\":true,\"queued\":true}" : "{\"ok\":true}";
    respond(ex, code, body);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // SSE 写出
  // ──────────────────────────────────────────────────────────────────────────

  private void streamSse(HttpExchange ex, Flow.Publisher<StepEvent> publisher) throws IOException {
    ex.getResponseHeaders().set("Content-Type", CT_SSE);
    ex.getResponseHeaders().set("Cache-Control", "no-cache");
    ex.sendResponseHeaders(200, 0); // 0 = chunked（无 Content-Length）
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Flow.Subscription> subRef = new AtomicReference<>();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subRef.set(subscription);
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(StepEvent item) {
            try {
              writeSse(ex, item);
            } catch (IOException e) {
              Flow.Subscription s = subRef.get();
              if (s != null) {
                s.cancel(); // 客户端断开：取消订阅（宿主轮次不受影响）
              }
              done.countDown();
            }
          }

          @Override
          public void onError(Throwable throwable) {
            try {
              writeSse(
                  ex,
                  StepEvent.of(
                      StepEventType.ERROR,
                      "unknown",
                      "unknown",
                      0L,
                      Map.of(
                          "stage", "stream", "message", String.valueOf(throwable.getMessage()))));
            } catch (IOException ignored) {
              // 连接已断
            }
            done.countDown();
          }

          @Override
          public void onComplete() {
            done.countDown();
          }
        });
    try {
      done.await(); // 虚拟线程上阻塞无碍；SSE 期间保持连接
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void writeSse(HttpExchange ex, StepEvent event) throws IOException {
    byte[] bytes = ("data: " + EventCodec.encode(event) + "\n\n").getBytes(StandardCharsets.UTF_8);
    ex.getResponseBody().write(bytes);
    ex.getResponseBody().flush();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 工具
  // ──────────────────────────────────────────────────────────────────────────

  private AgentCommand readCommand(HttpExchange ex) throws IOException {
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    return CommandCodec.decode(body); // 与 stdio 同构：命令信封是唯二真源 ✓
  }

  private static List<StepEvent> collect(Flow.Publisher<StepEvent> pub, long timeoutMs)
      throws InterruptedException {
    List<StepEvent> frames = Collections.synchronizedList(new ArrayList<StepEvent>());
    CountDownLatch done = new CountDownLatch(1);
    pub.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription s) {
            s.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(StepEvent item) {
            frames.add(item);
          }

          @Override
          public void onError(Throwable t) {
            done.countDown();
          }

          @Override
          public void onComplete() {
            done.countDown();
          }
        });
    if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
      throw new IllegalStateException("命令超时未收口（" + timeoutMs + "ms）");
    }
    return new ArrayList<>(frames);
  }

  private static void failIfError(List<StepEvent> frames) {
    for (StepEvent f : frames) {
      if (f.type() == StepEventType.ERROR) {
        throw new IllegalStateException(String.valueOf(f.payload().get("message")));
      }
    }
  }

  private static String typeOf(AgentCommand cmd) {
    return switch (cmd) {
      case ControlCmd.SteerCmd c -> "steer";
      case ControlCmd.AbortCmd c -> "abort";
      case ControlCmd.ResetSessionCmd c -> "new";
      case ControlCmd.ResumeSessionCmd c -> "resume";
      case ExecutionCmd c -> "prompt";
      default -> "other";
    };
  }

  private static String usageJson(StepEvent.Usage u) {
    StepEvent.Usage usage = u == null ? StepEvent.Usage.zero() : u;
    return "{\"input\":"
        + usage.input()
        + ",\"output\":"
        + usage.output()
        + ",\"cacheRead\":"
        + usage.cacheRead()
        + ",\"cacheWrite\":"
        + usage.cacheWrite()
        + ",\"totalTokens\":"
        + usage.totalTokens()
        + ",\"cost\":"
        + usage.cost()
        + "}";
  }

  private static String errorJson(String message) {
    return "{\"ok\":false,\"error\":" + quote(message == null ? "" : message) + "}";
  }

  private static String quote(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }

  private static void respond(HttpExchange ex, int code, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", CT_JSON);
    ex.sendResponseHeaders(code, bytes.length);
    ex.getResponseBody().write(bytes);
  }

  private static void safeRespond(HttpExchange ex, int code, String json) {
    try {
      respond(ex, code, json);
    } catch (IOException ignored) {
      // 响应头可能已发出（SSE 中途失败）：忽略
    }
  }
}
