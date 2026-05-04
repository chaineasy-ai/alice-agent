package org.cland.alice.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 情节记忆（Episodic Memory）Vault。
 * <p>
 * 负责按 {@code sessionId} 存储和检索原始交互 Trace。
 * 实现"遗忘策略"——基于 LRU + 重要度评分进行自动清理。
 * <p>
 * 对应设计文档：EpisodicVault / TraceLogger 角色。
 * 物理载体建议：Redis / PostgreSQL（当前提供内存实现）。
 */
public final class EpisodicVault {

    /** 每个 session 的最大 step 数量，超出后触发遗忘策略 */
    private static final int DEFAULT_MAX_STEPS_PER_SESSION = 200;

    /** 全局最大 session 数量 */
    private static final int DEFAULT_MAX_SESSIONS = 100;

    private final Map<String, List<Step>> traces = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAccessTime = new ConcurrentHashMap<>();
    private final int maxStepsPerSession;
    private final int maxSessions;

    public EpisodicVault() {
        this(DEFAULT_MAX_STEPS_PER_SESSION, DEFAULT_MAX_SESSIONS);
    }

    public EpisodicVault(int maxStepsPerSession, int maxSessions) {
        this.maxStepsPerSession = maxStepsPerSession;
        this.maxSessions = maxSessions;
    }

    // ---------------------------------------------------------------
    // 写操作
    // ---------------------------------------------------------------

    /**
     * 记录一个 Step 到指定 session 的 Trace 中。
     * 写入后自动触发遗忘策略检查。
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
     * 获取指定 session 的完整 Trace（按时间正序）。
     */
    public List<Step> getTrace(String sessionId) {
        List<Step> steps = traces.get(sessionId);
        if (steps == null) return List.of();
        lastAccessTime.put(sessionId, System.currentTimeMillis());
        return List.copyOf(steps);
    }

    /**
     * 获取指定 session 最近的 N 个 Step。
     */
    public List<Step> getRecentSteps(String sessionId, int n) {
        List<Step> steps = traces.get(sessionId);
        if (steps == null || steps.isEmpty()) return List.of();
        lastAccessTime.put(sessionId, System.currentTimeMillis());
        int from = Math.max(0, steps.size() - n);
        return List.copyOf(steps.subList(from, steps.size()));
    }

    /**
     * 获取指定 session 中重要度高于阈值的 Step。
     */
    public List<Step> getImportantSteps(String sessionId, double minImportance) {
        List<Step> steps = traces.get(sessionId);
        if (steps == null) return List.of();
        return steps.stream()
                .filter(s -> s.importance() >= minImportance)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 获取所有活跃的 session ID。
     */
    public List<String> getActiveSessionIds() {
        return List.copyOf(traces.keySet());
    }

    /**
     * 获取 session 的 Step 数量。
     */
    public int stepCount(String sessionId) {
        List<Step> steps = traces.get(sessionId);
        return steps != null ? steps.size() : 0;
    }

    /**
     * 获取 vault 中的 session 数量。
     */
    public int sessionCount() {
        return traces.size();
    }

    // ---------------------------------------------------------------
    // 删除 / 遗忘
    // ---------------------------------------------------------------

    /**
     * 清除指定 session 的所有 Trace。
     */
    public void clearSession(String sessionId) {
        traces.remove(sessionId);
        lastAccessTime.remove(sessionId);
    }

    /**
     * 清除所有 Trace。
     */
    public void clearAll() {
        traces.clear();
        lastAccessTime.clear();
    }

    // ---------------------------------------------------------------
    // 遗忘策略（FIFO + 重要度评分）
    // ---------------------------------------------------------------

    /**
     * 对单个 session 执行遗忘策略：
     * 如果 Step 数量超过上限，删除重要度最低的 Step（直到满足上限）。
     */
    private void enforceSessionLimit(String sessionId) {
        List<Step> steps = traces.get(sessionId);
        if (steps == null || steps.size() <= maxStepsPerSession) return;

        // 按重要度升序排序，移除低重要度的部分
        List<Step> sorted = new ArrayList<>(steps);
        sorted.sort(Comparator.comparingDouble(Step::importance));
        int toRemove = sorted.size() - maxStepsPerSession;

        if (toRemove <= 0) return;

        var lowImportance = sorted.subList(0, toRemove)
                .stream()
                .map(Step::stepId)
                .collect(Collectors.toSet());

        List<Step> remaining = steps.stream()
                .filter(s -> !lowImportance.contains(s.stepId()))
                .collect(Collectors.toList());

        traces.put(sessionId, new CopyOnWriteArrayList<>(remaining));
    }

    /**
     * 全局遗忘策略：如果 session 数量超过上限，
     * 淘汰最久未访问的 session。
     */
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

    /**
     * 手动触发遗忘：降低指定 session 中某 Step 的重要度，
     * 用于"如果某一步推理被后续证明是错误的，其权重应降低"。
     */
    public void penalizeStep(String sessionId, String stepId, double penalty) {
        List<Step> steps = traces.get(sessionId);
        if (steps == null) return;
        List<Step> updated = steps.stream()
                .map(s -> s.stepId().equals(stepId)
                        ? Step.builder(s)
                                .importance(Math.max(0, s.importance() - penalty))
                                .build()
                        : s)
                .collect(Collectors.toList());
        traces.put(sessionId, new CopyOnWriteArrayList<>(updated));
    }

    @Override
    public String toString() {
        return "EpisodicVault{sessions=%d, totalSteps=%d}"
                .formatted(traces.size(),
                        traces.values().stream().mapToInt(List::size).sum());
    }
}
