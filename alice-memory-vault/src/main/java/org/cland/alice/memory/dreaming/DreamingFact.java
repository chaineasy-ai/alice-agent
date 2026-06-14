package org.cland.alice.memory.dreaming;

import java.util.Objects;

/**
 * 从 WAL 日志中提取的单个事实陈述 — PromptMelter 输出与 ConflictResolver 输入之间的中间数据。
 *
 * @param factId 唯一 ID
 * @param content 事实陈述内容
 * @param sourceSessionId 来源 WalSession
 * @param sourceMessageId 来源 WAL 消息 ID（用于排序）
 * @param timestamp 事实被观察到的时间戳（epoch 毫秒）
 * @param confidence 可信度 0.0–1.0（从 system=0.9, assistant=0.7, user=0.5）
 */
public record DreamingFact(
    String factId,
    String content,
    String sourceSessionId,
    long sourceMessageId,
    long timestamp,
    double confidence) {

  public DreamingFact {
    Objects.requireNonNull(factId, "factId must not be null");
    Objects.requireNonNull(content, "content must not be null");
    Objects.requireNonNull(sourceSessionId, "sourceSessionId must not be null");
    if (factId.isBlank()) throw new IllegalArgumentException("factId must not be blank");
    if (content.isBlank()) throw new IllegalArgumentException("content must not be blank");
    if (sourceSessionId.isBlank())
      throw new IllegalArgumentException("sourceSessionId must not be blank");
    if (confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got: " + confidence);
    }
    if (timestamp <= 0) {
      timestamp = System.currentTimeMillis();
    }
  }
}
