/*
 * Alice Agent — ListSubAgentsCmd（列出子 Agent）
 *
 * /sub-agent list — 列出当前会话的所有子 Agent 及其状态。
 */
package org.cland.alice.agent.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 列出子 Agent 指令 {@code /sub-agent list}。
 *
 * <p>返回当前父会话中所有已注册的子 Agent（活跃/已完成/已取消），包括其 ID、类型、状态、目标和时长。
 *
 * @param sessionId 当前会话 ID
 * @param traceId 链路追踪 ID
 * @param timestamp 指令发起时间
 */
public record ListSubAgentsCmd(String sessionId, String traceId, Instant timestamp)
    implements SubAgentCmd {

  public ListSubAgentsCmd {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(traceId, "traceId must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  public ListSubAgentsCmd(String sessionId, String traceId) {
    this(sessionId, traceId, Instant.now());
  }

  @Override
  public String target() {
    return "list";
  }
}
