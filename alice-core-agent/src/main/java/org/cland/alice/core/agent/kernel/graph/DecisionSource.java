package org.cland.alice.core.agent.kernel.graph;

/**
 * 决策源（Decision 源：LLM/人/规则）—— 唯一能产出语义决策的力量。
 *
 * <p>"哪些执行点必须产生决策、决策如何推进图与账本"由内核定（R0）；"本轮决策的内容"委托本接口。 实现由 L2 Agent 装配注入（LLM 触点经 {@code
 * kernel.Inferencer}、人经 HITL、规则为确定性实现）。
 */
public interface DecisionSource {

  /**
   * 在当前决策节点产出一次语义决策。
   *
   * @param node 当前 DECISION 节点（含注解）
   * @param ledger 账本（可在写点 ① 落账：bindGoals/recordArbitration 由解释器在 hasStateWrite 时执行）
   * @param lastObservation 最近一次效果观察（observe 重建上下文的素材）
   * @return 语义决策（route 必须匹配当前节点的出边端口）
   */
  Decision decide(GraphNode node, Ledger ledger, String lastObservation);
}
