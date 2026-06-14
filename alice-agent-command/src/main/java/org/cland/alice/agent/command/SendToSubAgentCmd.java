/*
 * Alice Agent — SendToSubAgentCmd（向子 Agent 发送消息）
 *
 * /sub-agent send — 向正在运行的子 Agent 发送结构化消息。
 */
package org.cland.alice.agent.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 向子 Agent 发送消息指令 {@code /sub-agent send}。
 *
 * <p>向指定子 Agent 发送结构化消息/指令。子 Agent 接收到消息后可在其上下文中处理。
 *
 * @param subAgentId 目标子 Agent ID
 * @param message 要发送的消息内容
 * @param sessionId 当前会话 ID
 * @param traceId 链路追踪 ID
 * @param timestamp 指令发起时间
 */
public record SendToSubAgentCmd(
    String subAgentId, String message, String sessionId, String traceId, Instant timestamp)
    implements SubAgentCmd {

  public SendToSubAgentCmd {
    Objects.requireNonNull(subAgentId, "subAgentId must not be null");
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(traceId, "traceId must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  public SendToSubAgentCmd(String subAgentId, String message, String sessionId, String traceId) {
    this(subAgentId, message, sessionId, traceId, Instant.now());
  }

  @Override
  public String target() {
    return subAgentId;
  }
}
