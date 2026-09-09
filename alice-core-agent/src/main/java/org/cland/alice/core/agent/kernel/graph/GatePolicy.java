package org.cland.alice.core.agent.kernel.graph;

/**
 * 门策略（gate 内容）—— 校验规则/目标判据由 Agent 注入，内核只负责"门在哪些图边被询问、拦截后流向哪里"。
 *
 * <p>gate 节点出边约定：port "pass" = 放行边；其余命名 port = guard 边（block 时由 verdict 选定）。
 */
public interface GatePolicy {

  /** 门判定。 */
  GateResult verify(GraphNode gate, Ledger ledger, String lastObservation);
}
