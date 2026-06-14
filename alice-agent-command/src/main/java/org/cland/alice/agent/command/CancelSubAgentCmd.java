/*
 * Alice Agent — CancelSubAgentCmd（取消子 Agent）
 *
 * /sub-agent cancel — 取消一个正在运行或已连接的子 Agent。
 */
package org.cland.alice.agent.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 取消子 Agent 指令 {@code /sub-agent cancel}。
 *
 * <p>终止指定子 Agent 的执行。对于 Alice 子 Agent，终止其 ReAct 循环并清理会话；对于外部 ACP Agent，标记为已断开。
 *
 * @param subAgentId 要取消的子 Agent ID
 * @param sessionId 当前会话 ID
 * @param traceId 链路追踪 ID
 * @param timestamp 指令发起时间
 */
public record CancelSubAgentCmd(
    String subAgentId, String sessionId, String traceId, Instant timestamp) implements SubAgentCmd {

  public CancelSubAgentCmd {
    Objects.requireNonNull(subAgentId, "subAgentId must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(traceId, "traceId must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  public CancelSubAgentCmd(String subAgentId, String sessionId, String traceId) {
    this(subAgentId, sessionId, traceId, Instant.now());
  }

  @Override
  public String target() {
    return subAgentId;
  }
}
