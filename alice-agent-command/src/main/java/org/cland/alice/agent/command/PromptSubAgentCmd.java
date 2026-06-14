/*
 * Alice Agent — PromptSubAgentCmd（向外部 ACP Agent 发送提示）
 *
 * /sub-agent prompt — 向已连接的外部 ACP 协议 Agent 发送提示并获取响应。
 */
package org.cland.alice.agent.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 向外部 ACP Agent 发送提示指令 {@code /sub-agent prompt}。
 *
 * <p>向已连接的外部 ACP 协议兼容 Agent 发送提示文本，并返回其响应结果。
 *
 * @param subAgentId 目标外部 Agent ID（通过 connect 注册的 ACP Agent）
 * @param prompt 要发送的提示文本
 * @param sessionId 当前会话 ID
 * @param traceId 链路追踪 ID
 * @param timestamp 指令发起时间
 */
public record PromptSubAgentCmd(
    String subAgentId, String prompt, String sessionId, String traceId, Instant timestamp)
    implements SubAgentCmd {

  public PromptSubAgentCmd {
    Objects.requireNonNull(subAgentId, "subAgentId must not be null");
    Objects.requireNonNull(prompt, "prompt must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(traceId, "traceId must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  public PromptSubAgentCmd(String subAgentId, String prompt, String sessionId, String traceId) {
    this(subAgentId, prompt, sessionId, traceId, Instant.now());
  }

  @Override
  public String target() {
    return subAgentId;
  }
}
