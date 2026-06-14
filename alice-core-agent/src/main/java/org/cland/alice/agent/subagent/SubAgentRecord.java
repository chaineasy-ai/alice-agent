/*
 * Alice Agent — SubAgentRecord
 *
 * 表示父会话注册表中管理的子 Agent 记录（spawned Alice agent 或 connected ACP agent）。
 */
package org.cland.alice.agent.subagent;

import java.util.Objects;

/**
 * 子 Agent 记录实体（Java record）。
 *
 * <p>包含子 Agent 的完整状态信息，由 {@link SubAgentRegistry} 管理。
 *
 * @param id 唯一子 Agent 标识符（UUID）
 * @param type 子 Agent 类型（{@link SubAgentType#ALICE} 或 {@link SubAgentType#ACP}）
 * @param status 当前生命周期状态
 * @param goal 子 Agent 的目标/用途描述（最大 500 字符）
 * @param sessionId WAL 会话 ID（仅 type=ALICE 时存在）
 * @param endpoint ACP 端点 URL（仅 type=ACP 时存在）
 * @param createdAt 创建时间戳（epoch millis）
 * @param completedAt 完成/失败/取消时间戳（null 表示仍在运行）
 * @param resultSummary 完成后的结果摘要（最大 2000 字符，null 表示仍在运行）
 */
public record SubAgentRecord(
    String id,
    SubAgentType type,
    SubAgentStatus status,
    String goal,
    String sessionId,
    String endpoint,
    long createdAt,
    Long completedAt,
    String resultSummary) {

  /** 目标描述最大长度 */
  public static final int MAX_GOAL_LENGTH = 500;

  /** 结果摘要最大长度 */
  public static final int MAX_RESULT_SUMMARY_LENGTH = 2000;

  /**
   * 紧凑构造器 — 参数验证。
   *
   * @throws NullPointerException 如果 {@code id}、{@code type}、{@code status} 或 {@code goal} 为 null
   * @throws IllegalArgumentException 如果 {@code goal} 为空或超过 {@value MAX_GOAL_LENGTH} 字符
   * @throws IllegalArgumentException 如果 {@code resultSummary} 超过 {@value MAX_RESULT_SUMMARY_LENGTH}
   *     字符
   * @throws IllegalArgumentException 如果 {@code type == ALICE} 且 {@code sessionId} 为 null
   * @throws IllegalArgumentException 如果 {@code type == ACP} 且 {@code endpoint} 为 null
   */
  public SubAgentRecord {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(goal, "goal must not be null");

    if (goal.isBlank()) {
      throw new IllegalArgumentException("goal must not be blank");
    }
    if (goal.length() > MAX_GOAL_LENGTH) {
      throw new IllegalArgumentException(
          "goal length " + goal.length() + " exceeds max " + MAX_GOAL_LENGTH);
    }
    if (resultSummary != null && resultSummary.length() > MAX_RESULT_SUMMARY_LENGTH) {
      throw new IllegalArgumentException(
          "resultSummary length "
              + resultSummary.length()
              + " exceeds max "
              + MAX_RESULT_SUMMARY_LENGTH);
    }
    if (type == SubAgentType.ALICE && sessionId == null) {
      throw new IllegalArgumentException("sessionId must be present for ALICE type");
    }
    if (type == SubAgentType.ACP && endpoint == null) {
      throw new IllegalArgumentException("endpoint must be present for ACP type");
    }
  }

  /**
   * 创建 ALICE 类型子 Agent 记录的便利方法。
   *
   * @param id 子 Agent ID
   * @param goal 目标描述
   * @param sessionId WAL 会话 ID
   * @return 状态为 RUNNING 的 ALICE 类型记录
   */
  public static SubAgentRecord createAlice(String id, String goal, String sessionId) {
    return new SubAgentRecord(
        id,
        SubAgentType.ALICE,
        SubAgentStatus.RUNNING,
        goal,
        sessionId,
        null,
        System.currentTimeMillis(),
        null,
        null);
  }

  /**
   * 创建 ACP 类型子 Agent 记录的便利方法。
   *
   * @param id 子 Agent ID
   * @param name 连接名称
   * @param endpoint ACP 端点 URL
   * @return 状态为 CONNECTED 的 ACP 类型记录
   */
  public static SubAgentRecord createAcp(String id, String name, String endpoint) {
    return new SubAgentRecord(
        id,
        SubAgentType.ACP,
        SubAgentStatus.CONNECTED,
        name,
        null,
        endpoint,
        System.currentTimeMillis(),
        null,
        null);
  }

  /**
   * 返回带有更新状态的新记录（不可变 — 创建副本）。
   *
   * @param newStatus 新状态
   * @return 更新状态后的新 SubAgentRecord 实例
   */
  public SubAgentRecord withStatus(SubAgentStatus newStatus) {
    Long completed = isTerminal(newStatus) ? System.currentTimeMillis() : completedAt;
    return new SubAgentRecord(
        id, type, newStatus, goal, sessionId, endpoint, createdAt, completed, resultSummary);
  }

  /**
   * 返回带有更新结果摘要的新记录。
   *
   * @param summary 结果摘要
   * @return 更新结果后的新 SubAgentRecord 实例
   */
  public SubAgentRecord withResult(String summary) {
    return new SubAgentRecord(
        id, type, status, goal, sessionId, endpoint, createdAt, completedAt, summary);
  }

  /**
   * 检查状态是否为终端状态。
   *
   * @param status 要检查的状态
   * @return 如果状态为 COMPLETED、FAILED 或 CANCELED 则返回 true
   */
  public static boolean isTerminal(SubAgentStatus status) {
    return status == SubAgentStatus.COMPLETED
        || status == SubAgentStatus.FAILED
        || status == SubAgentStatus.CANCELED;
  }

  /**
   * 计算子 Agent 执行持续时间（毫秒）。
   *
   * @return 持续时间毫秒数，如果仍在运行则返回自创建以来的毫秒数
   */
  public long durationMs() {
    long end = completedAt != null ? completedAt : System.currentTimeMillis();
    return end - createdAt;
  }
}
