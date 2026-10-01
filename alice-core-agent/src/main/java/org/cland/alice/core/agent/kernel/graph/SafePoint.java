package org.cland.alice.core.agent.kernel.graph;

import java.util.Map;

/**
 * 图内核安全点快照（§3.2 ⑥）— 解释器在节点边界捕获的可恢复状态。
 *
 * <p>在「一步完成」的边界（循环顶，节点分派前）捕获：当前位置 {@code pos}、已执行步数 {@code steps}、最后一次观测、待执行效果、 效果计数与 {@link
 * LedgerState}。恢复 = 以本快照重置解释器（{@link R0Interpreter#OPTION_RESUME}）从 {@code pos} 续跑； 账本单写点使回放无歧义。
 *
 * @param pos 当前节点 id（恢复后从此节点继续遍历）
 * @param steps 已执行步数（恢复后以此继续编号，保证 trace 与不中断一次跑一致）
 * @param lastObservation 解释器最后一次观测（string 原样；可为空）
 * @param pendingEffect 待执行效果（decision ACT 之后、effect 节点之前；可为 null）
 * @param effectRuns 效果节点执行计数（注解预算依据）
 * @param ledgerState 账本快照
 */
public record SafePoint(
    String pos,
    int steps,
    String lastObservation,
    ToolCallReq pendingEffect,
    Map<String, Integer> effectRuns,
    LedgerState ledgerState) {

  public SafePoint {
    effectRuns = effectRuns != null ? Map.copyOf(effectRuns) : Map.of();
    ledgerState = ledgerState != null ? ledgerState : LedgerState.empty();
  }
}
