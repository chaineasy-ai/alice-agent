package org.cland.alice.core.agent.kernel.graph;

/**
 * 内核执行 trace 监听（可观测埋点：thought/action/observe 语义的事件形态）。
 *
 * <p>由解释器在对应执行点触发；监听器不得影响执行（异常被解释器吞掉并记日志）。 L2 Agent 借此把内核事件翻译为 UI 事件（§3.3 事件翻译是 Agent 层职责）。
 */
public interface KernelTraceListener {

  /** DECISION 节点产出决策后触发。 */
  default void onDecision(GraphNode node, Decision decision) {}

  /** EFFECT 节点执行完成后触发。 */
  default void onEffect(GraphNode node, ToolCallReq call, EffectOutcome outcome) {}

  /** OBSERVE 节点推进时触发（上下文重建点）。 */
  default void onObserve(GraphNode node, String lastObservation) {}

  /** GATE 节点判定后触发。 */
  default void onGate(GraphNode node, GateResult verdict) {}
}
