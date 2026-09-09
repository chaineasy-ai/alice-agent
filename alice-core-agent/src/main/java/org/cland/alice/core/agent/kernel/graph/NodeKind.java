package org.cland.alice.core.agent.kernel.graph;

/**
 * 图节点类型（§5.2.1 结构元模型）。
 *
 * <p>类型决定节点在执行层的唯一语义，属性决定策略挂点；解释器只识别类型，不识别任何业务。
 */
public enum NodeKind {
  /** 调一次 Decision 源（LLM/人/规则）→ 得语义决策 d；d 携带结构写时在写点原子落账。 */
  DECISION,

  /** 经权限门执行效果 → observation 入账 trace。 */
  EFFECT,

  /** 询问一次策略（verifyPre/verifyPost/HITL/预算）→ pass 或 block(理由/guard 边)。 */
  GATE,

  /** 汇总 trace/记忆 → 重建该决策点的上下文。 */
  OBSERVE,

  /** 按层级退出：goal 级经 exit port 回外层；会话级终止 Loop。 */
  TERMINAL,

  /** 展开帧入栈：取内部图 (entry)，sub-terminal 经 exit port 绑定回外层出口。 */
  COMPOSITE
}
