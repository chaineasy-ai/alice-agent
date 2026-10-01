package org.cland.alice.core.agent.kernel.graph;

import java.util.List;
import java.util.Map;

/**
 * 账本快照（§3.2 ⑥）— {@link Ledger} 的不可变状态镜像，用于 Checkpoint 持久化与恢复回放。
 *
 * <p>捕获账本全部槽位：goal 图 / 游标 / route / 产物 / 修订计数 / abort / trace。写点纪律（R4）不变：本记录只做「读取镜像」， 恢复经 {@link
 * Ledger#restore(LedgerState)}（内核初始化入口，非 effect 写）。
 */
public record LedgerState(
    List<GoalRef> goals,
    int cursor,
    Map<String, Object> route,
    Map<String, Object> artifacts,
    Map<String, Integer> revisions,
    Map<String, String> aborted,
    List<String> trace) {

  public LedgerState {
    goals = goals != null ? List.copyOf(goals) : List.of();
    route = route != null ? Map.copyOf(route) : Map.of();
    artifacts = artifacts != null ? Map.copyOf(artifacts) : Map.of();
    revisions = revisions != null ? Map.copyOf(revisions) : Map.of();
    aborted = aborted != null ? Map.copyOf(aborted) : Map.of();
    trace = trace != null ? List.copyOf(trace) : List.of();
  }

  /** 空账本状态（等价 {@code new Ledger()}）。 */
  public static LedgerState empty() {
    return new LedgerState(List.of(), -1, Map.of(), Map.of(), Map.of(), Map.of(), List.of());
  }
}
