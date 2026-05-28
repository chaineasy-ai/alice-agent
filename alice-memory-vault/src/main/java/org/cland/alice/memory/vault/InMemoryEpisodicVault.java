package org.cland.alice.memory.vault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.cland.alice.memory.core.Step;

/**
 * 情景记忆（Episodic Memory）Vault 的内存实现。
 *
 * <p>负责存储 Agent 与环境的交互历史（原始 Traces）， 支持按 sessionId 检索、遗忘策略（FIFO + 重要度评分）。
 *
 * <p>对应设计文档：EpisodicVault / TraceStore 角色。 物理载体建议：SQLite / PostgreSQL（当前提供 InMemory 实现）。
 *
 * <p>核心特性：
 *
 * <ul>
 *   <li>单 session 内遗忘：超过 maxStepsPerSession 时， 保留重要度最高的步骤（按重要度升序淘汰）
 *   <li>全局遗忘：超过 maxSessions 时， 淘汰最久未访问（Last Access Time）的 session
 *   <li>Step 重要度惩罚：支持降低特定 Step 的重要度（用于纠正错误推理）
 * </ul>
 */
public final class InMemoryEpisodicVault implements EpisodicVault {

  private static final int DEFAULT_MAX_STEPS_PER_SESSION = 50;
  private static final int DEFAULT_MAX_SESSIONS = 10;

  private final int maxStepsPerSession;
  private final int maxSessions;

  /** sessionId → ordered steps */
  private final Map<String, CopyOnWriteArrayList<Step>> traces = new ConcurrentHashMap<>();

  /** sessionId → last access timestamp */
  private final Map<String, Long> lastAccessTime = new ConcurrentHashMap<>();

  public InMemoryEpisodicVault() {
    this(DEFAULT_MAX_STEPS_PER_SESSION, DEFAULT_MAX_SESSIONS);
  }

  public InMemoryEpisodicVault(int maxStepsPerSession, int maxSessions) {
    this.maxStepsPerSession = maxStepsPerSession;
    this.maxSessions = maxSessions;
  }

  // ---------------------------------------------------------------
  // 写操作
  // ---------------------------------------------------------------

  /**
   * 向指定会话追加一条交互步骤。
   *
   * @param sessionId 会话 ID
   * @param step 交互步骤
   */
  public void appendStep(String sessionId, Step step) {
    traces.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(step);
    lastAccessTime.put(sessionId, System.currentTimeMillis());
    enforceSessionLimit(sessionId);
    enforceGlobalLimit();
  }

  // ---------------------------------------------------------------
  // 读操作
  // ---------------------------------------------------------------

  /**
   * 获取指定会话的完整 Trace。
   *
   * @param sessionId 会话 ID
   * @return 会话的步骤列表（按添加顺序）
   */
  public List<Step> getTrace(String sessionId) {
    List<Step> steps = traces.get(sessionId);
    lastAccessTime.put(sessionId, System.currentTimeMillis());
    return steps != null ? List.copyOf(steps) : List.of();
  }

  /**
   * 获取指定会话最近的 N 个步骤。
   *
   * @param sessionId 会话 ID
   * @param n 需要的步骤数
   * @return 最近的 N 个步骤（按时间正序）
   */
  public List<Step> getRecentSteps(String sessionId, int n) {
    List<Step> steps = traces.get(sessionId);
    lastAccessTime.put(sessionId, System.currentTimeMillis());
    if (steps == null || steps.isEmpty()) return List.of();
    int start = Math.max(0, steps.size() - n);
    return List.copyOf(steps.subList(start, steps.size()));
  }

  /**
   * 获取指定会话中重要度超过阈值的步骤。
   *
   * @param sessionId 会话 ID
   * @param minImportance 最低重要度阈值
   * @return 重要度 >= minImportance 的步骤列表
   */
  public List<Step> getImportantSteps(String sessionId, double minImportance) {
    List<Step> steps = traces.get(sessionId);
    if (steps == null) return List.of();
    return steps.stream()
        .filter(s -> s.importance() >= minImportance)
        .collect(Collectors.toUnmodifiableList());
  }

  /** 获取指定会话的步骤数量。 */
  public int stepCount(String sessionId) {
    List<Step> steps = traces.get(sessionId);
    return steps != null ? steps.size() : 0;
  }

  /** 获取当前活跃的 session 数量。 */
  public int sessionCount() {
    return traces.size();
  }

  /** 获取所有活跃 session ID 的列表。 */
  public List<String> getActiveSessionIds() {
    return List.copyOf(traces.keySet());
  }

  // ---------------------------------------------------------------
  // 删除
  // ---------------------------------------------------------------

  /** 清除指定会话的所有步骤。 */
  public void clearSession(String sessionId) {
    traces.remove(sessionId);
    lastAccessTime.remove(sessionId);
  }

  /** 清除所有 Trace。 */
  public void clearAll() {
    traces.clear();
    lastAccessTime.clear();
  }

  // ---------------------------------------------------------------
  // 遗忘策略（FIFO + 重要度评分）
  // ---------------------------------------------------------------

  /** 对单个 session 执行遗忘策略： 如果 Step 数量超过上限，删除重要度最低的 Step（直到满足上限）。 */
  private void enforceSessionLimit(String sessionId) {
    List<Step> steps = traces.get(sessionId);
    if (steps == null || steps.size() <= maxStepsPerSession) return;

    // 按重要度升序排序，移除低重要度的部分
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

  /** 全局遗忘策略：如果 session 数量超过上限， 淘汰最久未访问的 session。 */
  private void enforceGlobalLimit() {
    if (traces.size() <= maxSessions) return;

    // 按最后访问时间升序排序，淘汰最早的那些
    List<String> sorted = new ArrayList<>(lastAccessTime.keySet());
    sorted.sort(Comparator.comparingLong(lastAccessTime::get));

    int toRemove = sorted.size() - maxSessions + 1; // +1 保有余量
    for (int i = 0; i < toRemove && i < sorted.size(); i++) {
      String sessionId = sorted.get(i);
      traces.remove(sessionId);
      lastAccessTime.remove(sessionId);
    }
  }

  /** 手动触发遗忘：降低指定 session 中某 Step 的重要度， 用于"如果某一步推理被后续证明是错误的，其权重应降低"。 */
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

  @Override
  public String toString() {
    return "InMemoryEpisodicVault{sessions=%d, totalSteps=%d}"
        .formatted(traces.size(), traces.values().stream().mapToInt(List::size).sum());
  }
}
