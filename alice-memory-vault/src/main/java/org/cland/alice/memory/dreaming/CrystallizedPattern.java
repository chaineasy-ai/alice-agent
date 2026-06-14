package org.cland.alice.memory.dreaming;

import java.util.List;
import java.util.Objects;

/**
 * 检测到的工具调用序列模式 — 即将转换为 SOP 条目的候选。
 *
 * @param patternId 唯一 ID
 * @param toolSequence 有序工具名列表（如 ["list_files", "read_file", "write_file"]）
 * @param occurrenceCount 该序列出现的次数
 * @param sourceSessionId 模式被检测到的源会话
 * @param firstSeen 第一次出现的 epoch 毫秒
 * @param successRate 该序列的执行成功率（0.0–1.0）
 */
public record CrystallizedPattern(
    String patternId,
    List<String> toolSequence,
    int occurrenceCount,
    String sourceSessionId,
    long firstSeen,
    double successRate) {

  public CrystallizedPattern {
    Objects.requireNonNull(patternId, "patternId must not be null");
    Objects.requireNonNull(toolSequence, "toolSequence must not be null");
    Objects.requireNonNull(sourceSessionId, "sourceSessionId must not be null");
    if (patternId.isBlank()) throw new IllegalArgumentException("patternId must not be blank");
    if (toolSequence.isEmpty())
      throw new IllegalArgumentException("toolSequence must not be empty");
    if (sourceSessionId.isBlank())
      throw new IllegalArgumentException("sourceSessionId must not be blank");
    if (occurrenceCount < 1)
      throw new IllegalArgumentException("occurrenceCount must be >= 1, got: " + occurrenceCount);
    if (successRate < 0.0 || successRate > 1.0) {
      throw new IllegalArgumentException("successRate must be in [0.0, 1.0], got: " + successRate);
    }
  }
}
