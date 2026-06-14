/*
 * Alice Agent — SubAgentResult
 *
 * 子 Agent 完成时返回的结果负载。
 */
package org.cland.alice.agent.subagent;

import java.util.Objects;

/**
 * 子 Agent 执行结果（Java record）。
 *
 * <p>当子 Agent 完成（COMPLETED/FAILED/CANCELED）时返回的结果负载。
 *
 * @param subAgentId 子 Agent 唯一标识符
 * @param status 终端状态（COMPLETED/FAILED/CANCELED）
 * @param summary 人工可读的结果摘要
 * @param messageCount 执行期间交换的消息数量
 * @param durationMs 执行持续时间（毫秒）
 */
public record SubAgentResult(
    String subAgentId, SubAgentStatus status, String summary, int messageCount, long durationMs) {

  public SubAgentResult {
    Objects.requireNonNull(subAgentId, "subAgentId must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(summary, "summary must not be null");
  }
}
