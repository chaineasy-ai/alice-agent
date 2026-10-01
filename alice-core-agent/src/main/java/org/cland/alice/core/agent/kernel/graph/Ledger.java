package org.cland.alice.core.agent.kernel.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态账本（§3.2 / §5.2.2）— 执行状态的唯一真相：goal 图 / 游标 / route / 修订计数 / trace。
 *
 * <p><b>写点纪律（R4）</b>：槽位变更只允许发生在两处 ——
 *
 * <ol>
 *   <li>decision 结果落账（STRATEGIZE 绑定 goal 图、ARBITRATE 结论、route/判据/预算写入）： {@link #bindGoals} / {@link
 *       #recordArbitration}
 *   <li>gate/仲裁落账（修订扣减、预算判定、abort 记录）：{@link #bumpRevision} / {@link #markAborted}
 * </ol>
 *
 * <p>本类不提供任何"通用写"入口：效果（工具）无写权，只能产出待决建议 observation， 经决策/仲裁写点才成为账本事实。
 *
 * <p>trace 由解释器在遍历时追加（内核写入，不可被绕过）。
 */
public final class Ledger {

  private final List<GoalRef> goals = new ArrayList<>();
  private int cursor = -1;
  private final Map<String, Object> route = new LinkedHashMap<>();
  private final Map<String, Object> artifacts = new LinkedHashMap<>();
  private final Map<String, Integer> revisions = new LinkedHashMap<>();
  private final Map<String, String> aborted = new LinkedHashMap<>();
  private final List<String> trace = new ArrayList<>();

  // ========== 写点 ①：decision 结果落账 ==========

  /** STRATEGIZE 绑定 goal 图：清空旧槽位并以新列表重置游标到 0（无 goal 时游标为 -1）。 */
  public synchronized void bindGoals(List<GoalRef> newGoals) {
    goals.clear();
    if (newGoals != null) {
      goals.addAll(newGoals);
    }
    cursor = goals.isEmpty() ? -1 : 0;
    trace.add("ledger: bindGoals(" + goals.size() + ")");
  }

  /** 仲裁结论落账：route/判据/预算重分配写入 route 槽位。 */
  public synchronized void recordArbitration(Map<String, Object> arbitration) {
    if (arbitration != null) {
      route.putAll(arbitration);
    }
    trace.add("ledger: arbitration");
  }

  /**
   * 会话产物落账（decision 写点 ① 携带的候选产物，如最终回答 answer）。
   *
   * <p>过渡落账点：等效 legacy 的 ctx.result 语义；D11 权限体系（LedgerScopeValidator 扩展）落地后，
   * 产物写将由效果边界守卫统一接管，本方法收敛为内核 decision/仲裁点专用。
   */
  public synchronized void recordArtifact(String key, Object value) {
    if (key != null && value != null) {
      artifacts.put(key, value);
      trace.add("ledger: artifact[" + key + "]");
    }
  }

  // ========== 写点 ②：gate/仲裁落账 ==========

  /** 修订扣减：goal 修订计数 +1（每 goal 修订预算的判定依据）。 */
  public synchronized int bumpRevision(String goalId) {
    int next = revisions.getOrDefault(goalId, 0) + 1;
    revisions.put(goalId, next);
    trace.add("ledger: revision[" + goalId + "]=" + next);
    return next;
  }

  /** abort 记录。 */
  public synchronized void markAborted(String goalId, String reason) {
    aborted.put(goalId, reason != null ? reason : "");
    trace.add("ledger: abort[" + goalId + "]");
  }

  // ========== 只读查询 ==========

  public synchronized List<GoalRef> goals() {
    return List.copyOf(goals);
  }

  /** 当前 goal 游标（无绑定 goal 时为 -1）。 */
  public synchronized int cursor() {
    return cursor;
  }

  public synchronized boolean hasGoals() {
    return cursor >= 0;
  }

  public synchronized GoalRef currentGoal() {
    return cursor >= 0 && cursor < goals.size() ? goals.get(cursor) : null;
  }

  /** 是否还有下一个 goal（"下一个 goal"是图事实 —— 游标）。 */
  public synchronized boolean hasNextGoal() {
    return cursor >= 0 && cursor + 1 < goals.size();
  }

  /** 游标 +1（PASS 仲裁落账；调用前应以 {@link #hasNextGoal()} 判定）。 */
  public synchronized void advanceGoal() {
    if (cursor >= 0 && cursor + 1 < goals.size()) {
      cursor++;
      trace.add("ledger: advance -> " + currentGoal().id());
    }
  }

  public synchronized int revisionsOf(String goalId) {
    return revisions.getOrDefault(goalId, 0);
  }

  public synchronized boolean isAborted(String goalId) {
    return aborted.containsKey(goalId);
  }

  /** route/判据/预算槽位（不可变视图）。 */
  public synchronized Map<String, Object> route() {
    return java.util.Map.copyOf(route);
  }

  /** 会话产物槽位（不可变视图；如 "answer"）。 */
  public synchronized Map<String, Object> artifacts() {
    return java.util.Map.copyOf(artifacts);
  }

  /** trace（不可变视图）。 */
  public synchronized List<String> trace() {
    return List.copyOf(trace);
  }

  /** 解释器专用：追加遍历/效果痕迹。 */
  public synchronized void appendTrace(String entry) {
    trace.add(entry);
  }

  // ========== 检查点快照 / 恢复（§3.2 ⑥） ==========

  /** 账本快照（内核检查点用；只读镜像，不改变账本）。 */
  public synchronized LedgerState snapshot() {
    return new LedgerState(goals, cursor, route, artifacts, revisions, aborted, trace);
  }

  /**
   * 从快照恢复账本（内核初始化入口；非 effect 写）。
   *
   * <p>恢复 = 回放 + 重路由（§3.2 ⑥）：单写点纪律使状态回滚无歧义；本方法只重置槽位，不追加额外 trace，保证「续跑 trace == 不中断一次跑」。
   */
  public synchronized void restore(LedgerState state) {
    LedgerState s = state != null ? state : LedgerState.empty();
    goals.clear();
    goals.addAll(s.goals());
    cursor = s.cursor();
    route.clear();
    route.putAll(s.route());
    artifacts.clear();
    artifacts.putAll(s.artifacts());
    revisions.clear();
    revisions.putAll(s.revisions());
    aborted.clear();
    aborted.putAll(s.aborted());
    trace.clear();
    trace.addAll(s.trace());
  }
}
