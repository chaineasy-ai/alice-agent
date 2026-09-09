package org.cland.alice.core.agent.kernel.graph;

/** R1 语义决策类型。 */
public enum DecisionKind {
  /** 直接回答。 */
  ANSWER,

  /** 执行工具调用（效果通道）。 */
  ACT,

  /** goal 级判据提交（只结束当前 goal 的战术循环，不终结会话）。 */
  FINISH_GOAL,

  /** 修订（同 goal 重跑 / 或经 route 回规划）。 */
  REVISE,

  /** 放弃（跳过或终止，由策略/HITL 决定）。 */
  ABORT
}
