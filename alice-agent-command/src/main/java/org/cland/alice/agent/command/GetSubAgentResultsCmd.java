/*
 * Alice Agent — GetSubAgentResultsCmd（获取子 Agent 结果）
 *
 * /sub-agent results — 获取已完成子 Agent 的结果摘要。
 */
package org.cland.alice.agent.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 获取子 Agent 结果指令 {@code /sub-agent results}。
 *
 * <p>返回指定子 Agent 的完成状态与结果摘要。
 *
 * @param subAgentId 要查询的子 Agent ID
 * @param sessionId 当前会话 ID
 * @param traceId 链路追踪 ID
 * @param timestamp 指令发起时间
 */
public record GetSubAgentResultsCmd(
    String subAgentId, String sessionId, String traceId, Instant timestamp) implements SubAgentCmd {

  public GetSubAgentResultsCmd {
    Objects.requireNonNull(subAgentId, "subAgentId must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(traceId, "traceId must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  public GetSubAgentResultsCmd(String subAgentId, String sessionId, String traceId) {
    this(subAgentId, sessionId, traceId, Instant.now());
  }

  @Override
  public String target() {
    return subAgentId;
  }
}
