/*
 * Alice Agent — ConnectSubAgentCmd（连接外部 ACP Agent）
 *
 * /sub-agent connect — 注册并连接到一个外部的 ACP 协议兼容 Agent。
 */
package org.cland.alice.agent.command;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * 连接外部 ACP Agent 指令 {@code /sub-agent connect}。
 *
 * <p>注册一个外部的 ACP 协议兼容 Agent，通过其端点 URL 进行通信。
 *
 * @param name 外部 Agent 的名称/别名
 * @param acpEndpoint ACP 协议端点 URL（如 http://localhost:9000/acp）
 * @param sessionId 当前会话 ID
 * @param traceId 链路追踪 ID
 * @param timestamp 指令发起时间
 */
public record ConnectSubAgentCmd(
    String name, URI acpEndpoint, String sessionId, String traceId, Instant timestamp)
    implements SubAgentCmd {

  public ConnectSubAgentCmd {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(acpEndpoint, "acpEndpoint must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(traceId, "traceId must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  public ConnectSubAgentCmd(String name, URI acpEndpoint, String sessionId, String traceId) {
    this(name, acpEndpoint, sessionId, traceId, Instant.now());
  }

  @Override
  public String target() {
    return name;
  }
}
