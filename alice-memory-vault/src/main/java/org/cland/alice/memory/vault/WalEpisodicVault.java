/*
 * WalEpisodicVault — 基于 WAL 的情景记忆实现
 *
 * 将 EpisodicVault 重构为 WalSession 的查询视图。
 * getTrace() 通过 WAL 全量回放实现，getRecentSteps() 通过 WAL 差量读取实现。
 * 无需额外存储——WAL 即单一事实来源。
 */
package org.cland.alice.memory.vault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.cland.alice.core.agent.wal.RawMessage;
import org.cland.alice.core.agent.wal.WalSession;
import org.cland.alice.memory.core.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 WAL 的情景记忆实现。
 *
 * <p>通过 {@link WalSession} 作为底层存储，将每条 WAL 消息映射为 {@link Step}。 无需独立的数据存储——WAL 本身就是情景记忆的持久化载体。
 *
 * <p>支持按 sessionId 检索、重要度遗忘策略（与 InMemoryEpisodicVault 一致的行为）。 额外维护一个轻量内存索引用于加速 getRecentSteps() 和
 * getImportantSteps()。
 */
public final class WalEpisodicVault implements EpisodicVault {

  private static final Logger log = LoggerFactory.getLogger(WalEpisodicVault.class);

  /** 默认每个 session 最大步骤数 */
  private static final int DEFAULT_MAX_STEPS_PER_SESSION = 50;

  /** 默认最大 session 数 */
  private static final int DEFAULT_MAX_SESSIONS = 10;

  private final WalSession walSession;
  private final int maxStepsPerSession;
  private final int maxSessions;

  /** sessionId → ordered steps (内存索引，用于加速查询) */
  private final Map<String, CopyOnWriteArrayList<Step>> traces = new ConcurrentHashMap<>();

  /** sessionId → last access timestamp */
  private final Map<String, Long> lastAccessTime = new ConcurrentHashMap<>();

  public WalEpisodicVault(WalSession walSession) {
    this(walSession, DEFAULT_MAX_STEPS_PER_SESSION, DEFAULT_MAX_SESSIONS);
  }

  public WalEpisodicVault(WalSession walSession, int maxStepsPerSession, int maxSessions) {
    this.walSession = Objects.requireNonNull(walSession, "walSession must not be null");
    this.maxStepsPerSession = maxStepsPerSession;
    this.maxSessions = maxSessions;
  }

  // ========== 写操作 ==========

  @Override
  public void appendStep(String sessionId, Step step) {
    // Step 已经通过 AgentExecutor 写入 WAL（via WalSession.user/assistant/toolResult）
    // 此处仅为内存索引同步
    traces.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(step);
    lastAccessTime.put(sessionId, System.currentTimeMillis());
    enforceSessionLimit(sessionId);
    enforceGlobalLimit();
  }

  // ========== 读操作 ==========

  @Override
  public List<Step> getTrace(String sessionId) {
    // 从 WAL 全量回放重建 Step 列表
    List<RawMessage> messages = walSession.getAllMessages(sessionId);
    List<Step> steps = rebuildSteps(messages);

    // 更新内存索引（全量替换）
    traces.put(sessionId, new CopyOnWriteArrayList<>(steps));
    lastAccessTime.put(sessionId, System.currentTimeMillis());

    return List.copyOf(steps);
  }

  @Override
  public List<Step> getRecentSteps(String sessionId, int n) {
    // 优先从内存索引返回
    List<Step> cached = traces.get(sessionId);
    if (cached != null && !cached.isEmpty()) {
      lastAccessTime.put(sessionId, System.currentTimeMillis());
      int start = Math.max(0, cached.size() - n);
      return List.copyOf(cached.subList(start, cached.size()));
    }

    // 回退到 WAL 重建
    return getTrace(sessionId).stream()
        .skip(Math.max(0, walSession.messageCount(sessionId) - (long) n))
        .collect(Collectors.toUnmodifiableList());
  }

  @Override
  public List<Step> getImportantSteps(String sessionId, double minImportance) {
    List<Step> cached = traces.get(sessionId);
    if (cached == null) return List.of();
    lastAccessTime.put(sessionId, System.currentTimeMillis());
    return cached.stream()
        .filter(s -> s.importance() >= minImportance)
        .collect(Collectors.toUnmodifiableList());
  }

  @Override
  public int stepCount(String sessionId) {
    return walSession.messageCount(sessionId);
  }

  @Override
  public int sessionCount() {
    // 从 WalStore 查询活跃 session 数
    // 如果 WalStore 不提供此方法，回退到内存索引
    return traces.size();
  }

  @Override
  public List<String> getActiveSessionIds() {
    return List.copyOf(traces.keySet());
  }

  @Override
  public void clearSession(String sessionId) {
    walSession.clearSession(sessionId);
    traces.remove(sessionId);
    lastAccessTime.remove(sessionId);
  }

  @Override
  public void clearAll() {
    walSession.clearAll();
    traces.clear();
    lastAccessTime.clear();
  }

  @Override
  public void penalizeStep(String sessionId, String stepId, double penalty) {
    List<Step> steps = traces.get(sessionId);
    if (steps == null) return;
    List<Step> updated =
        steps.stream()
            .map(
                s ->
                    s.stepId().equals(stepId)
                        ? Step.builder(s).importance(Math.max(0, s.importance() - penalty)).build()
                        : s)
            .collect(Collectors.toList());
    traces.put(sessionId, new CopyOnWriteArrayList<>(updated));
  }

  // ========== 辅助方法 ==========

  /** 将 WAL 中的 RawMessage 列表重建为 Step 列表。 */
  static List<Step> rebuildSteps(List<RawMessage> messages) {
    if (messages == null || messages.isEmpty()) return List.of();
    List<Step> steps = new ArrayList<>(messages.size());
    for (int i = 0; i < messages.size(); i++) {
      RawMessage msg = messages.get(i);
      Step step = rawMessageToStep(msg, i);
      if (step != null) {
        steps.add(step);
      }
    }
    return List.copyOf(steps);
  }

  /** 将 RawMessage 映射为 Step。 */
  static Step rawMessageToStep(RawMessage msg, int index) {
    if (msg == null) return null;

    String stepId = "wal-" + msg.messageId();
    String action =
        switch (msg.role()) {
          case "system" -> "system";
          case "user" -> "user";
          case "assistant" ->
              msg.toolCalls() != null && !msg.toolCalls().isEmpty()
                  ? "assistant_tool_calls"
                  : "assistant";
          case "tool" -> "tool_result";
          case "compact" -> "compact_summary";
          default -> msg.role();
        };

    String content =
        msg.content() != null
            ? msg.content().substring(0, Math.min(msg.content().length(), 200))
            : (msg.toolCalls() != null ? "[" + msg.toolCalls().size() + " tool calls]" : "");

    double importance = "tool_result".equals(action) ? 0.8 : 0.5;

    return Step.builder()
        .stepId(stepId)
        .action(action)
        .input(content)
        .success(true)
        .importance(importance)
        .timestamp(msg.timestamp())
        .build();
  }

  // ========== 遗忘策略 ==========

  private void enforceSessionLimit(String sessionId) {
    List<Step> steps = traces.get(sessionId);
    if (steps == null || steps.size() <= maxStepsPerSession) return;

    List<Step> sorted = new ArrayList<>(steps);
    sorted.sort(Comparator.comparingDouble(Step::importance));
    int toRemove = sorted.size() - maxStepsPerSession;
    if (toRemove <= 0) return;

    var lowImportance =
        sorted.subList(0, toRemove).stream().map(Step::stepId).collect(Collectors.toSet());
    List<Step> remaining =
        steps.stream()
            .filter(s -> !lowImportance.contains(s.stepId()))
            .collect(Collectors.toList());
    traces.put(sessionId, new CopyOnWriteArrayList<>(remaining));
  }

  private void enforceGlobalLimit() {
    if (traces.size() <= maxSessions) return;
    List<String> sorted = new ArrayList<>(lastAccessTime.keySet());
    sorted.sort(Comparator.comparingLong(lastAccessTime::get));
    int toRemove = sorted.size() - maxSessions + 1;
    for (int i = 0; i < toRemove && i < sorted.size(); i++) {
      String sid = sorted.get(i);
      traces.remove(sid);
      lastAccessTime.remove(sid);
    }
  }

  @Override
  public String toString() {
    return "WalEpisodicVault{sessions=%d, walStore=%s}".formatted(traces.size(), walSession);
  }
}
