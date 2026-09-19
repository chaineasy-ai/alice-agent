/*
 * Alice Agent — 默认会话宿主（会话语义收口）
 *
 * 会话宿主 = 唯一组合根之下的"会话语义"层：单写者语义、一轮一锁、超时 abort、
 * new/resume 策略、事件 → StepEvent 映射、健康快照。
 * 只依赖协议契约（proto）+ 引擎端口（AgentEngine）。
 */
package org.cland.alice.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.cland.alice.agent.proto.AgentCommand;
import org.cland.alice.agent.proto.ControlCmd;
import org.cland.alice.agent.proto.ExecutionCmd;
import org.cland.alice.agent.proto.event.StepEvent;
import org.cland.alice.agent.proto.event.StepEventType;
import org.cland.alice.agent.proto.port.AgentCommandDispatcher;
import org.cland.alice.agent.proto.port.CommandValidationException;
import org.cland.alice.agent.proto.port.DispatcherBusyException;
import org.cland.alice.runtime.engine.AgentEngine;
import org.cland.alice.runtime.engine.EngineEvents;

/**
 * 默认会话宿主 — 协议契约的执行实现（{@link AgentCommandDispatcher}）。
 *
 * <p>会话语义（与协议 §4 对应）：
 *
 * <ul>
 *   <li><b>一轮一锁</b>：{@code ExecutionCmd} 走原子 CAS（{@code roundActive}）；忙 ⇒ {@link
 *       DispatcherBusyException}（→409）；控制类命令（steer/abort/new/…）**不抢轮锁**
 *   <li><b>单写者</b>：宿主是引擎的唯一驱动者；transport 只调 {@link #dispatch}
 *   <li><b>事件映射</b>：引擎 {@link EngineEvents} 的 thought/action/observe → {@code StepEvent}
 *   <li><b>收口帧</b>：每轮流末必发 {@code DONE}（含 usage）；失败先发 {@code ERROR}
 *   <li><b>会话校验</b>：命令 sessionId 必须与引擎一致（防跨会话串轮）
 * </ul>
 *
 * <p>线程：轮次跑在虚拟线程上；{@code dispatch} 立即返回可订阅的发布器（回放缓冲，见 {@link RoundEventPublisher}）。
 */
public final class AgentHost implements AgentCommandDispatcher {

  private final AgentEngine engine;

  /**
   * 一轮一锁：**CAS 而非 ReentrantLock**——轮次在工作线程收口，而 ReentrantLock 要求“谁加锁谁解锁” 且对同一调用线程可重入（实测：同线程二次
   * dispatch 会静默成功 ✗）。
   */
  private final AtomicBoolean roundActive = new AtomicBoolean(false);

  private final AtomicLong seq = new AtomicLong();
  private final AtomicLong rounds = new AtomicLong();
  private final Instant startedAt = Instant.now();

  private volatile long lastRoundMs;
  private volatile StepEvent.Usage lastUsage = StepEvent.Usage.zero();

  /**
   * @param engine 引擎端口（不得为 null）
   */
  public AgentHost(AgentEngine engine) {
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
  }

  @Override
  public Flow.Publisher<StepEvent> dispatch(AgentCommand cmd) {
    Objects.requireNonNull(cmd, "cmd must not be null");
    requireSameSession(cmd);
    return switch (cmd) {
      case ExecutionCmd c -> round(c, c.task());
      case ControlCmd.SteerCmd c ->
          control(c, "steer", () -> engine.injectFeedback(c.message()), true);
      case ControlCmd.FeedbackCmd c ->
          control(c, "feedback", () -> engine.injectFeedback(c.message()), true);
      case ControlCmd.AbortCmd c -> control(c, "abort", engine::cancel, false);
      case ControlCmd.ResetSessionCmd c ->
          control(
              c,
              "new",
              () -> {
                engine.cancel();
                engine.clearMemory();
              },
              false);
      case ControlCmd.CompactContextCmd c ->
          valueFrame(c, StepEventType.SUMMARY, engine::compactContext);
      case ControlCmd.ViewContextCmd c ->
          valueFrame(c, StepEventType.OBSERVE, engine::currentContext);
      default ->
          throw new CommandValidationException(
              "runtime v1 暂不支持的命令：" + cmd.getClass().getSimpleName() + "（按版本纪律可追加）");
    };
  }

  /** 健康快照（协议面 {@code /health}）。 */
  public HostHealth health() {
    return new HostHealth(
        engine.sessionId(), roundActive.get(), rounds.get(), lastRoundMs, lastUsage, startedAt);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 轮次（一轮一锁 + 事件映射）
  // ──────────────────────────────────────────────────────────────────────────

  private Flow.Publisher<StepEvent> round(AgentCommand cmd, String prompt) {
    if (!roundActive.compareAndSet(false, true)) {
      throw new DispatcherBusyException(
          "已有轮次在执行（sessionId=" + engine.sessionId() + "）；请等待完成或改用 steer 插话");
    }
    final String sessionId = cmd.sessionId();
    final String traceId = cmd.traceId();
    final RoundEventPublisher pub = new RoundEventPublisher();
    Thread.ofVirtual()
        .name("alice-round-" + traceId)
        .start(
            () -> {
              long t0 = System.currentTimeMillis();
              EngineEvents.Listener listener = kernelListener(pub, sessionId, traceId);
              EngineEvents events = null;
              try {
                // 事件订阅必须在 try 内：订阅失败也要能收口，否则 roundActive 永远不释放（卡死 busy ✗）
                events = engine.events();
                events.subscribe(listener);
                String answer = engine.ask(prompt);
                emit(
                    pub,
                    StepEventType.SUMMARY,
                    sessionId,
                    traceId,
                    payload("text", answer == null ? "" : answer));
              } catch (Throwable e) { // Throwable：引擎任何错误都要收口（不让独立线程静默死亡 ✗）
                emit(
                    pub,
                    StepEventType.ERROR,
                    sessionId,
                    traceId,
                    payload("stage", "round", "message", String.valueOf(e.getMessage())));
              } finally {
                if (events != null) {
                  try {
                    events.unsubscribe(listener);
                  } catch (RuntimeException ignored) {
                    // 引擎事件源注销失败不影响收口
                  }
                }
                StepEvent.Usage usage = safeUsage();
                lastUsage = usage;
                lastRoundMs = System.currentTimeMillis() - t0;
                rounds.incrementAndGet();
                emit(pub, StepEventType.DONE, sessionId, traceId, Map.of(), usage);
                pub.complete();
                roundActive.set(false);
              }
            });
    return pub;
  }

  private EngineEvents.Listener kernelListener(
      RoundEventPublisher pub, String sessionId, String traceId) {
    return new EngineEvents.Listener() {
      @Override
      public void onThought(String reasoning) {
        emit(pub, StepEventType.THOUGHT, sessionId, traceId, payload("text", reasoning));
      }

      @Override
      public void onAction(String target, Map<String, Object> params) {
        emit(
            pub,
            StepEventType.TOOL_CALL,
            sessionId,
            traceId,
            payload("tool", target, "args", params));
      }

      @Override
      public void onObserve(String rawData, String summary, long elapsedMs) {
        emit(
            pub,
            StepEventType.TOOL_RESULT,
            sessionId,
            traceId,
            payload(
                "result",
                rawData == null ? "" : rawData,
                "summary",
                summary == null ? "" : summary,
                "elapsedMs",
                elapsedMs,
                "isError",
                false));
      }
    };
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 控制类（不抢轮锁，即时 ack）
  // ──────────────────────────────────────────────────────────────────────────

  private Flow.Publisher<StepEvent> control(
      AgentCommand cmd, String action, Runnable op, boolean duringRound) {
    RoundEventPublisher pub = new RoundEventPublisher();
    try {
      op.run();
      emit(
          pub,
          StepEventType.OBSERVE,
          cmd.sessionId(),
          cmd.traceId(),
          payload(
              "command",
              action,
              "accepted",
              true,
              "duringRound",
              duringRound && roundActive.get()));
    } catch (RuntimeException e) {
      emit(
          pub,
          StepEventType.ERROR,
          cmd.sessionId(),
          cmd.traceId(),
          payload("command", action, "message", String.valueOf(e.getMessage())));
    }
    finish(pub, cmd);
    return pub;
  }

  private Flow.Publisher<StepEvent> valueFrame(
      AgentCommand cmd, StepEventType type, ValueSupplier supplier) {
    RoundEventPublisher pub = new RoundEventPublisher();
    try {
      String value = supplier.get();
      emit(pub, type, cmd.sessionId(), cmd.traceId(), payload("text", value == null ? "" : value));
    } catch (RuntimeException e) {
      emit(
          pub,
          StepEventType.ERROR,
          cmd.sessionId(),
          cmd.traceId(),
          payload("message", String.valueOf(e.getMessage())));
    }
    finish(pub, cmd);
    return pub;
  }

  private void finish(RoundEventPublisher pub, AgentCommand cmd) {
    emit(pub, StepEventType.DONE, cmd.sessionId(), cmd.traceId(), Map.of());
    pub.complete();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 工具
  // ──────────────────────────────────────────────────────────────────────────

  private void emit(
      RoundEventPublisher pub,
      StepEventType type,
      String sessionId,
      String traceId,
      Map<String, Object> payload) {
    emit(pub, type, sessionId, traceId, payload, StepEvent.Usage.zero());
  }

  private void emit(
      RoundEventPublisher pub,
      StepEventType type,
      String sessionId,
      String traceId,
      Map<String, Object> payload,
      StepEvent.Usage usage) {
    pub.emit(
        new StepEvent(
            StepEvent.V1,
            type,
            sessionId,
            traceId,
            seq.getAndIncrement(),
            Instant.now(),
            payload,
            usage));
  }

  private StepEvent.Usage safeUsage() {
    try {
      StepEvent.Usage usage = engine.lastUsage();
      return usage == null ? StepEvent.Usage.zero() : usage;
    } catch (RuntimeException e) {
      return StepEvent.Usage.zero();
    }
  }

  private void requireSameSession(AgentCommand cmd) {
    String engineSession = engine.sessionId();
    if (engineSession != null
        && !engineSession.isBlank()
        && !engineSession.equals(cmd.sessionId())) {
      throw new CommandValidationException(
          "会话不一致：宿主 sessionId=" + engineSession + "，命令 sessionId=" + cmd.sessionId());
    }
  }

  private static Map<String, Object> payload(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      if (kv[i + 1] != null) {
        m.put(String.valueOf(kv[i]), kv[i + 1]);
      }
    }
    return m;
  }

  /** 可读文本供给（compact/context 等查询类命令）。 */
  @FunctionalInterface
  private interface ValueSupplier {
    String get();
  }
}
