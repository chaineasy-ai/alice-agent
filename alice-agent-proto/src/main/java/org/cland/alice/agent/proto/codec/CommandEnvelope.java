/*
 * Alice Agent — 命令线格式信封（v1）
 *
 * 协议面不直接序列化 Java 记录类型（避免把内部类名固化进线格式），统一走本信封：
 *   {"v":1,"type":"prompt","sessionId":"…","traceId":"…","ts":"2026-09-19T12:00:00+08:00","payload":{…}}
 */
package org.cland.alice.agent.proto.codec;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 命令线格式信封（协议 v1）。
 *
 * @param v 协议版本（当前 {@link #V1}）
 * @param type 命令类型（prompt/exec/steer/abort/new/resume/clear/context/compact/feedback）
 * @param sessionId 会话标识
 * @param traceId 链路追踪标识
 * @param ts 指令时间（传 null 取当前时刻）
 * @param payload 参数（传 null 视为空 Map）
 */
public record CommandEnvelope(
    int v, String type, String sessionId, String traceId, Instant ts, Map<String, Object> payload) {

  /** 当前协议版本 */
  public static final int V1 = 1;

  public CommandEnvelope {
    if (v <= 0) {
      throw new IllegalArgumentException("v must be > 0");
    }
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(traceId, "traceId must not be null");
    ts = ts == null ? Instant.now() : ts;
    payload =
        payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }
}
