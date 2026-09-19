/*
 * Alice Agent — stdio JSONL 传输（RPC 模式）
 *
 * 帧规范见 docs/alice-agent-proto/PROTOCOL.md：**只按 \n 分帧**、容忍 \r\n、UTF-8；
 * 错误帧（ERROR）不执行命令；输出单写者。
 */
package org.cland.alice.runtime.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import org.cland.alice.agent.proto.AgentCommand;
import org.cland.alice.agent.proto.codec.CommandCodec;
import org.cland.alice.agent.proto.codec.EventCodec;
import org.cland.alice.agent.proto.event.StepEvent;
import org.cland.alice.agent.proto.event.StepEventType;
import org.cland.alice.agent.proto.port.AgentCommandDispatcher;
import org.cland.alice.agent.proto.port.CommandValidationException;
import org.cland.alice.agent.proto.port.DispatcherBusyException;
import org.cland.alice.agent.proto.port.SessionTransport;

/**
 * stdio JSONL 传输 —— 进程被外部调度器/宿主驱动时的默认形态（对应 pi 的 {@code --mode rpc}）。
 *
 * <p>协议要点（PROTOCOL §1/§4）：
 *
 * <ul>
 *   <li><b>分帧</b>：以字节 0x0A 切帧（**不用 BufferedReader.readLine 之类的宽松语义**）， 容忍 {@code \r\n}（剥行尾 {@code
 *       \r}），UTF-8 解码
 *   <li><b>错误帧</b>：非法 JSON / 未知命令 / 版本不支持 ⇒ 回一帧 {@code ERROR}，命令不执行
 *   <li><b>单写者</b>：输出经一把写锁；帧之间以 {@code \n} 分隔
 *   <li><b>每帧带 traceId</b>：命令里的 traceId 原样回写，便于调度器串联
 * </ul>
 *
 * <p>用法：{@code new StdioJsonlTransport().start(host); runBlocking();}
 */
public final class StdioJsonlTransport implements SessionTransport {

  private static final String UNKNOWN = "unknown";

  private final InputStream in;
  private final OutputStream out;
  private final Object writeLock = new Object();

  private volatile AgentCommandDispatcher dispatcher;
  private volatile boolean closed;
  private volatile Thread io;

  /** 默认绑定进程 stdio。 */
  public StdioJsonlTransport() {
    this(System.in, System.out);
  }

  /**
   * 可注入流的构造（测试用）。
   *
   * @param in 输入流（命令帧，JSONL）
   * @param out 输出流（事件帧，JSONL）
   */
  public StdioJsonlTransport(InputStream in, OutputStream out) {
    this.in = Objects.requireNonNull(in, "in must not be null");
    this.out = Objects.requireNonNull(out, "out must not be null");
  }

  @Override
  public String name() {
    return "stdio";
  }

  @Override
  public void start(AgentCommandDispatcher dispatcher) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
    this.io = Thread.ofVirtual().name("alice-stdio").start(this::loop);
  }

  /**
   * 阻塞到输入结束（CLI 入口用）。
   *
   * @throws InterruptedException 等待被中断
   */
  public void runBlocking() throws InterruptedException {
    Thread t = io;
    if (t != null) {
      t.join();
    }
  }

  @Override
  public void close() {
    closed = true;
    Thread t = io;
    if (t != null) {
      t.interrupt();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 读循环：字节级按 \n 分帧
  // ──────────────────────────────────────────────────────────────────────────

  private void loop() {
    ByteArrayOutputStream frame = new ByteArrayOutputStream(256);
    try {
      int b;
      while (!closed && (b = in.read()) != -1) {
        if (b == '\n') {
          handle(frame.toString(StandardCharsets.UTF_8));
          frame.reset();
        } else {
          frame.write(b);
        }
      }
      if (frame.size() > 0) { // EOF 时的残帧：按错误帧处理（不静默丢）
        handle(frame.toString(StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      if (!closed) {
        write(error(UNKNOWN, UNKNOWN, "stdio", "输入读取失败：" + e.getMessage()));
      }
    }
  }

  private void handle(String line) {
    String trimmed = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    if (trimmed.isBlank()) {
      return;
    }
    AgentCommandDispatcher d = dispatcher;
    if (d == null || closed) {
      return;
    }
    AgentCommand cmd;
    try {
      cmd = CommandCodec.decode(trimmed);
    } catch (CommandValidationException e) {
      write(error(UNKNOWN, UNKNOWN, "decode", e.getMessage()));
      return;
    }
    try {
      subscribeAndWrite(d.dispatch(cmd));
    } catch (CommandValidationException e) {
      write(error(cmd.sessionId(), cmd.traceId(), "validation", e.getMessage()));
    } catch (DispatcherBusyException e) {
      write(error(cmd.sessionId(), cmd.traceId(), "busy", e.getMessage()));
    } catch (RuntimeException e) {
      write(error(cmd.sessionId(), cmd.traceId(), "dispatch", String.valueOf(e.getMessage())));
    }
  }

  private void subscribeAndWrite(Flow.Publisher<StepEvent> publisher) {
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(StepEvent item) {
            write(item);
          }

          @Override
          public void onError(Throwable throwable) {
            write(error(UNKNOWN, UNKNOWN, "stream", String.valueOf(throwable.getMessage())));
          }

          @Override
          public void onComplete() {
            // 轮次收口（DONE 帧已在流内）
          }
        });
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 写出：单写者 + JSONL
  // ──────────────────────────────────────────────────────────────────────────

  private void write(StepEvent event) {
    byte[] bytes = (EventCodec.encode(event) + "\n").getBytes(StandardCharsets.UTF_8);
    synchronized (writeLock) {
      if (closed) {
        return;
      }
      try {
        out.write(bytes);
        out.flush();
      } catch (IOException e) {
        closed = true; // 下游断开：停止服务（reader 循环随后退出）
      }
    }
  }

  private static StepEvent error(String sessionId, String traceId, String stage, String message) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("stage", stage);
    payload.put("message", message == null ? "" : message);
    return StepEvent.of(
        StepEventType.ERROR,
        sessionId == null || sessionId.isBlank() ? UNKNOWN : sessionId,
        traceId == null || traceId.isBlank() ? UNKNOWN : traceId,
        0L,
        payload);
  }
}
