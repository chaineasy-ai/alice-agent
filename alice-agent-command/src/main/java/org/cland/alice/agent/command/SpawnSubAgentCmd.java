/*
 * Alice Agent — SpawnSubAgentCmd（创建子 Agent）
 *
 * /sub-agent spawn — 在相同 JVM 进程中创建一个独立的 Alice Agent 子会话。
 */
package org.cland.alice.agent.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 创建子 Agent 指令 {@code /sub-agent spawn}。
 *
 * <p>在相同 JVM 进程中创建一个新的 Alice Agent 实例，具有独立的 ReAct 循环、WAL 和记忆上下文。
 *
 * @param goal 子 Agent 要执行的目标/任务描述
 * @param model 可选的模型覆盖（如 "gpt-4o", "claude-3.5"），null 表示使用父会话模型
 * @param sessionId 当前会话 ID
 * @param traceId 链路追踪 ID
 * @param timestamp 指令发起时间
 */
public record SpawnSubAgentCmd(
    String goal, String model, String sessionId, String traceId, Instant timestamp)
    implements SubAgentCmd {

  public SpawnSubAgentCmd {
    Objects.requireNonNull(goal, "goal must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(traceId, "traceId must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
  }

  public SpawnSubAgentCmd(String goal, String model, String sessionId, String traceId) {
    this(goal, model, sessionId, traceId, Instant.now());
  }

  public SpawnSubAgentCmd(String goal, String sessionId, String traceId) {
    this(goal, null, sessionId, traceId, Instant.now());
  }

  @Override
  public String target() {
    return goal;
  }
}
