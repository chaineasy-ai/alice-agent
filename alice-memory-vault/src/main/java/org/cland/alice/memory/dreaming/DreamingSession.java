package org.cland.alice.memory.dreaming;

import java.util.Objects;

/**
 * 单个 Dreaming 周期执行记录 — 追踪一次离线处理的结果和统计数据。
 *
 * @param sessionId 被处理的 WalSession ID
 * @param startTime Epoch 毫秒 — 处理开始时间
 * @param endTime Epoch 毫秒 — 处理结束时间（null 表示进行中或失败）
 * @param durationMs 处理耗时（毫秒），null 表示进行中或失败
 * @param episodicSummaryId 写入 EpisodicVault 的摘要 ID（null 表示未生成）
 * @param conflictCount 冲突解决过程中检测到的冲突数量
 * @param patternsCrystallized 结晶化的 SOP 模式数量
 * @param outcome 处理结果状态
 */
public record DreamingSession(
    String sessionId,
    long startTime,
    Long endTime,
    Long durationMs,
    String episodicSummaryId,
    int conflictCount,
    int patternsCrystallized,
    DreamingOutcome outcome) {

  public DreamingSession {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    if (sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
  }

  /** Dreaming 周期处理结果。 */
  public enum DreamingOutcome {
    /** 管道执行成功 */
    SUCCESS,
    /** 管道执行失败 */
    FAILURE,
    /** 会话被跳过（已在 DREAMING 或 ARCHIVED 状态） */
    SKIPPED
  }
}
