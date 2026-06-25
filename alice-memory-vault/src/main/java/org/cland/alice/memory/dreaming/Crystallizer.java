package org.cland.alice.memory.dreaming;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.cland.alice.core.agent.wal.RawMessage;
import org.cland.alice.memory.core.SOP;
import org.cland.alice.memory.vault.ProceduralVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 模式结晶器 — 分析 WAL 消息中重复的工具调用序列， 将 3+ 次出现的相同序列结晶化为 SOP 条目存入 ProceduralVault。 */
public final class Crystallizer {

  private static final Logger log = LoggerFactory.getLogger(Crystallizer.class);

  /** 结晶化阈值：同一序列出现至少此次数才生成 SOP */
  static final int CRYSTALLIZATION_THRESHOLD = 3;

  /** 滑动窗口最小大小 */
  static final int MIN_WINDOW_SIZE = 2;

  /** 滑动窗口最大大小 */
  static final int MAX_WINDOW_SIZE = 5;

  private final ProceduralVault proceduralVault;

  public Crystallizer(ProceduralVault proceduralVault) {
    this.proceduralVault =
        Objects.requireNonNull(proceduralVault, "proceduralVault must not be null");
  }

  /**
   * 分析会话的消息，结晶化重复的工具调用模式。
   *
   * @param messages 会话的原始 WAL 消息
   * @param sessionId 源会话 ID
   * @return 创建的 SOP 数量
   */
  public int crystallize(List<RawMessage> messages, String sessionId) {
    Objects.requireNonNull(messages, "messages must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");

    // 1. 提取有工具调用的 assistant 消息中的工具名序列
    List<String> toolCallSequence = extractToolCallSequence(messages);
    if (toolCallSequence.size() < MIN_WINDOW_SIZE) {
      log.debug(
          "[Crystallizer] Session={} too few tool calls ({}) for crystallization",
          sessionId,
          toolCallSequence.size());
      return 0;
    }

    // 2. 滑动窗口扫描，统计模式出现次数
    Map<List<String>, Integer> patternCounts = countPatterns(toolCallSequence);

    // 3. 过滤达到阈值的模式并生成 SOP
    int sopsCreated = 0;
    for (var entry : patternCounts.entrySet()) {
      List<String> sequence = entry.getKey();
      int count = entry.getValue();
      if (count >= CRYSTALLIZATION_THRESHOLD) {
        SOP sop = buildSop(sequence, sessionId, count);
        proceduralVault.register(sop);
        sopsCreated++;
        log.info(
            "[Crystallizer] Created SOP '{}' for session={}, sequence={}, count={}",
            sop.sopId(),
            sessionId,
            sequence,
            count);
      }
    }

    log.debug(
        "[Crystallizer] Session={}: {} patterns found, {} SOPs created",
        sessionId,
        patternCounts.size(),
        sopsCreated);
    return sopsCreated;
  }

  // ============================================================
  // Tool Call Sequence Extraction
  // ============================================================

  /** 从 WAL 消息中提取有序的工具名序列。 遍历所有 assistant 消息，提取其 toolCalls 中的函数名。 */
  static List<String> extractToolCallSequence(List<RawMessage> messages) {
    List<String> toolNames = new ArrayList<>();
    for (RawMessage msg : messages) {
      if ("assistant".equals(msg.role()) && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
        for (var tc : msg.toolCalls()) {
          toolNames.add(tc.function().name());
        }
      }
    }
    return List.copyOf(toolNames);
  }

  // ============================================================
  // Pattern Counting via Sliding Window
  // ============================================================

  /** 使用滑动窗口扫描工具名序列，统计所有窗口大小下的模式出现次数。 */
  static Map<List<String>, Integer> countPatterns(List<String> toolCallSequence) {
    Map<List<String>, Integer> counts = new HashMap<>();

    for (int windowSize = MIN_WINDOW_SIZE;
        windowSize <= Math.min(MAX_WINDOW_SIZE, toolCallSequence.size());
        windowSize++) {
      for (int i = 0; i <= toolCallSequence.size() - windowSize; i++) {
        List<String> window = toolCallSequence.subList(i, i + windowSize);
        List<String> key = List.copyOf(window); // ensure immutability
        counts.merge(key, 1, Integer::sum);
      }
    }
    return counts;
  }

  // ============================================================
  // SOP Building
  // ============================================================

  /** 根据检测到的模式构建 SOP 条目并注册到 ProceduralVault。 */
  static SOP buildSop(List<String> sequence, String sessionId, int count) {
    String sequenceHash = UUID.randomUUID().toString().substring(0, 8);
    String sopId = "dreaming-" + sessionId + "-" + sequenceHash;
    String name = String.join(" → ", sequence);
    String pattern = String.join(",", sequence);
    String procedure =
        "Detected pattern '" + name + "' appearing " + count + " times in session " + sessionId;

    return SOP.builder()
        .sopId(sopId)
        .name(name)
        .pattern(pattern)
        .procedure(procedure)
        .toolName(sequence.get(0))
        .version("0.1.0")
        .build();
  }
}
