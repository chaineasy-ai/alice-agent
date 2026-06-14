package org.cland.alice.memory.dreaming;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.cland.alice.memory.wal.RawMessage;
import org.cland.alice.memory.wal.WalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 离线 PromptMelter（梦境熔炼） — 在 Dreaming 流程中将原始 WAL 日志浓缩为结构化情景摘要。
 *
 * <p>与 {@code org.cland.alice.memory.wal.PromptMelter}（在线上下文组装）不同， 本组件仅用于 {@link DreamingEngine}
 * 的离线处理管道，专注于日志降噪、去重和时间归一化。
 */
public final class PromptMelter {

  private static final Logger log = LoggerFactory.getLogger(PromptMelter.class);

  private final WalStore walStore;

  public PromptMelter(WalStore walStore) {
    this.walStore = Objects.requireNonNull(walStore, "walStore must not be null");
  }

  /**
   * 熔炼指定会话的 WAL 日志，生成结构化情景摘要。
   *
   * @param sessionId 会话 ID
   * @return 熔炼后的情景摘要
   */
  public EpisodicSummary melt(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");

    List<RawMessage> messages = walStore.getAllMessages(sessionId);
    if (messages.isEmpty()) {
      log.warn(
          "[PromptMelter] No messages found for session={}, returning empty summary", sessionId);
      return new EpisodicSummary(
          generateSummaryId(sessionId),
          sessionId,
          0,
          List.of(),
          "empty",
          "No messages to summarize.",
          System.currentTimeMillis());
    }

    // 1. 噪音去除: 剥离 tool_call_id 元信息，去重连续相同状态消息
    List<RawMessage> cleaned = denoise(messages);

    // 2. 提取关键动作
    List<String> keyActions = extractKeyActions(cleaned);

    // 3. 确定会话结果
    String outcome = determineOutcome(cleaned);

    // 4. 生成摘要文本
    String summaryText = buildSummary(sessionId, keyActions, outcome);

    EpisodicSummary summary =
        new EpisodicSummary(
            generateSummaryId(sessionId),
            sessionId,
            cleaned.size(),
            keyActions,
            outcome,
            summaryText,
            System.currentTimeMillis());

    log.info(
        "[PromptMelter] Session={} melted: {} steps, {} actions, outcome={}",
        sessionId,
        cleaned.size(),
        keyActions.size(),
        outcome);
    return summary;
  }

  // ============================================================
  // Noise Reduction
  // ============================================================

  /** 去噪处理: 去除 tool_call_id 元信息、去重连续相同的状态消息、归一化时间戳。 */
  static List<RawMessage> denoise(List<RawMessage> messages) {
    if (messages.isEmpty()) return List.of();

    List<RawMessage> result = new ArrayList<>();
    RawMessage previous = null;

    for (RawMessage msg : messages) {
      // 跳过 content 完全相同的连续 tool 消息（重复状态报告）
      if (previous != null && isDuplicateStatus(previous, msg)) {
        log.trace("[PromptMelter] Skipping duplicate status message id={}", msg.messageId());
        continue;
      }
      result.add(msg);
      previous = msg;
    }
    return List.copyOf(result);
  }

  /** 判断两条消息是否是重复的状态报告（相同 role + 相同 content）。 */
  static boolean isDuplicateStatus(RawMessage a, RawMessage b) {
    if (a == null || b == null) return false;
    if (!"tool".equals(a.role()) || !"tool".equals(b.role())) return false;
    return Objects.equals(a.content(), b.content());
  }

  // ============================================================
  // Key Action Extraction
  // ============================================================

  /** 从干净的消息列表中提取关键动作描述。 */
  static List<String> extractKeyActions(List<RawMessage> messages) {
    List<String> actions = new ArrayList<>();
    for (RawMessage msg : messages) {
      switch (msg.role()) {
        case "system" -> actions.add("System: " + truncate(msg.content(), 80));
        case "user" -> actions.add("User input: " + truncate(msg.content(), 80));
        case "assistant" -> {
          if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
            var names = msg.toolCalls().stream().map(tc -> tc.function().name()).toList();
            actions.add("Tool call: " + String.join(", ", names));
          } else if (msg.content() != null && !msg.content().isBlank()) {
            actions.add("Assistant: " + truncate(msg.content(), 80));
          }
        }
        case "tool" -> actions.add("Tool result: " + truncate(msg.content(), 80));
        default -> {}
      }
    }
    return List.copyOf(actions);
  }

  // ============================================================
  // Outcome Determination
  // ============================================================

  /** 根据消息列表判断会话结果。 */
  static String determineOutcome(List<RawMessage> messages) {
    // 如果最后一条消息是 tool 且包含 "error" → 失败
    if (!messages.isEmpty()) {
      RawMessage last = messages.get(messages.size() - 1);
      if ("tool".equals(last.role())
          && last.content() != null
          && last.content().toLowerCase().contains("error")) {
        return "failed_with_error";
      }
    }
    return "completed";
  }

  // ============================================================
  // Summary Text Builder
  // ============================================================

  /** 生成人类可读的摘要文本。 */
  static String buildSummary(String sessionId, List<String> keyActions, String outcome) {
    var sb = new StringBuilder();
    sb.append("Session ").append(sessionId).append(": ").append(outcome).append("\n");
    sb.append("Key actions (").append(keyActions.size()).append("):\n");
    for (int i = 0; i < keyActions.size(); i++) {
      sb.append("  ").append(i + 1).append(". ").append(keyActions.get(i)).append("\n");
    }
    return sb.toString();
  }

  // ============================================================
  // Helpers
  // ============================================================

  private static String generateSummaryId(String sessionId) {
    return "dreaming-summary-" + sessionId + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static String truncate(String s, int maxLen) {
    if (s == null) return "";
    if (s.length() <= maxLen) return s;
    return s.substring(0, maxLen) + "...";
  }

  // ============================================================
  // EpisodicSummary — 熔炼输出的摘要记录
  // ============================================================

  /**
   * 单个会话的结构化情景摘要 —— {@link PromptMelter} 的输出产物。
   *
   * @param summaryId 唯一 ID
   * @param sessionId 来源 WalSession
   * @param stepCount 熔炼后的步骤数
   * @param keyActions 关键动作列表
   * @param outcome 会话结果描述
   * @param summaryText 人类可读的浓缩摘要
   * @param createdAt Epoch 毫秒
   */
  public record EpisodicSummary(
      String summaryId,
      String sessionId,
      int stepCount,
      List<String> keyActions,
      String outcome,
      String summaryText,
      long createdAt) {

    public EpisodicSummary {
      Objects.requireNonNull(summaryId, "summaryId must not be null");
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(keyActions, "keyActions must not be null");
      Objects.requireNonNull(outcome, "outcome must not be null");
      Objects.requireNonNull(summaryText, "summaryText must not be null");
    }
  }
}
