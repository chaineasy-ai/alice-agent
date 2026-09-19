/*
 * Alice Agent — StepEvent（出方向事件帧 v1）
 *
 * 与 AgentCommand（入方向）共同构成协议契约的"出/入"双向；字段与兼容规则见
 * docs/alice-agent-proto/PROTOCOL.md。
 */
package org.cland.alice.agent.proto.event;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * StepEvent — 出方向事件帧（协议 v1）。
 *
 * <p>兼容规则：帧必须带 {@code v}；字段只增不改不删；消费者必须忽略未知字段。
 *
 * @param v 协议版本（当前 {@link #V1}）
 * @param type 事件类型（见 {@link StepEventType}）
 * @param sessionId 会话标识
 * @param traceId 链路追踪标识（同一次唤醒/同一轮共享）
 * @param seq 同一 {@code sessionId} 内单调递增序号（从 0 起）
 * @param ts 事件时间（传 null 时取当前时刻）
 * @param payload 载荷（按 type 定义；传 null 视为空 Map）
 * @param usage token/费用用量（传 null 视为 {@link Usage#zero()}）
 */
public record StepEvent(
    int v,
    StepEventType type,
    String sessionId,
    String traceId,
    long seq,
    Instant ts,
    Map<String, Object> payload,
    Usage usage) {

  /** 当前协议版本 */
  public static final int V1 = 1;

  public StepEvent {
    if (v <= 0) {
      throw new IllegalArgumentException("v must be > 0");
    }
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(traceId, "traceId must not be null");
    if (seq < 0) {
      throw new IllegalArgumentException("seq must be >= 0");
    }
    ts = ts == null ? Instant.now() : ts;
    payload =
        payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    usage = usage == null ? Usage.zero() : usage;
  }

  /**
   * 便捷工厂：v1 + 当前时刻 + 零用量。
   *
   * @param type 事件类型
   * @param sessionId 会话标识
   * @param traceId 链路标识
   * @param seq 序号
   * @param payload 载荷（可空）
   * @return 事件帧
   */
  public static StepEvent of(
      StepEventType type, String sessionId, String traceId, long seq, Map<String, Object> payload) {
    return new StepEvent(V1, type, sessionId, traceId, seq, Instant.now(), payload, Usage.zero());
  }

  /**
   * 返回替换了用量的事件帧（便于轮末一次性补 usage）。
   *
   * @param usage 用量
   * @return 新帧（其余字段不变）
   */
  public StepEvent withUsage(Usage usage) {
    return new StepEvent(v, type, sessionId, traceId, seq, ts, payload, usage);
  }

  /**
   * token/费用用量（对齐 pi usage 口径：input/output/cacheRead/cacheWrite/totalTokens/cost）。
   *
   * @param input 输入 token
   * @param output 输出 token
   * @param cacheRead 缓存命中读取 token
   * @param cacheWrite 缓存写入 token
   * @param totalTokens 供应方口径总 token
   * @param cost 费用（美元）
   */
  public record Usage(
      long input, long output, long cacheRead, long cacheWrite, long totalTokens, double cost) {

    public Usage {
      if (input < 0 || output < 0 || cacheRead < 0 || cacheWrite < 0 || totalTokens < 0) {
        throw new IllegalArgumentException("usage counters must be >= 0");
      }
      if (cost < 0) {
        throw new IllegalArgumentException("cost must be >= 0");
      }
    }

    /** 零用量。 */
    public static Usage zero() {
      return new Usage(0, 0, 0, 0, 0, 0.0);
    }
  }
}
