package org.cland.alice.core.agent.kernel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次会话闭环的最终结果（内核数据）。
 *
 * <p>L2 Agent 负责将本结果翻译回业务形态（如 legacy 的 AgentContext / UI 事件）。 子 agent（003）以同契约递归：
 * 本结果作为普通消息在父子会话间传递。
 *
 * @param status 会话终态
 * @param sessionId 会话 ID
 * @param answer 最终答案文本（终态为 FAILED 时可能为空，错误见 metadata）
 * @param iteration 实际迭代步数
 * @param metadata 元数据快照（如 phase/status/error/plannerIntent；不可为 null，规范化后不可变）
 */
public record SessionResult(
    SessionStatus status,
    String sessionId,
    String answer,
    int iteration,
    Map<String, Object> metadata) {

  /** 规范化：metadata 为空或不可变副本。 */
  public SessionResult {
    metadata = normalize(metadata);
  }

  private static Map<String, Object> normalize(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return Map.of();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
  }
}
