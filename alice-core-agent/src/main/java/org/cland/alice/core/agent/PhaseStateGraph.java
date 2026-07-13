package org.cland.alice.core.agent;

import java.util.Set;

/**
 * PPAO 状态机图接口 — 定义阶段转换的查询契约。
 *
 * <p>将 {@link AgentContext.Phase} 之间的合法转换建模为一个有向图，当前默认实现为基于 JGrapht 的 {@link
 * AgentStateGraph}，但可以通过此接口替换为其他实现（如纯内存 Set 查找、持久化图等）。
 *
 * <p>接口包含三类操作：
 *
 * <ul>
 *   <li><b>查询</b> — {@link #canTransition(Phase, Phase)}, {@link #nextPhases(Phase)}, {@link
 *       #isTerminal(Phase)}
 *   <li><b>校验执行</b> — {@link #transition(Phase, Phase)}
 *   <li><b>自省</b> — {@link #vertexCount()}, {@link #edgeCount()}
 * </ul>
 *
 * @see AgentStateGraph
 * @see AgentContext#transitionTo(AgentContext.Phase)
 */
public interface PhaseStateGraph {

  /**
   * 判断 {@code from → to} 是否为合法转换。
   *
   * @param from 当前阶段
   * @param to 目标阶段
   * @return true 如果转换合法
   */
  boolean canTransition(AgentContext.Phase from, AgentContext.Phase to);

  /**
   * 获取从 {@code from} 阶段所有合法的下一阶段集合。
   *
   * @param from 当前阶段
   * @return 合法下一阶段的不可变集合
   */
  Set<AgentContext.Phase> nextPhases(AgentContext.Phase from);

  /**
   * 判断 {@code phase} 是否为终态（无合法出边）。
   *
   * <p>默认实现委派给 {@link AgentContext.Phase#isTerminal()}，因为 FINISH 是固有终态。 子类可重写以支持自定义终态（如动态图中增加终态节点）。
   *
   * @param phase 待检查的阶段
   * @return true 如果是终态
   */
  default boolean isTerminal(AgentContext.Phase phase) {
    return phase.isTerminal();
  }

  /**
   * 安全转换：校验 {@code from → to} 的合法性，通过则返回 {@code to}，否则抛异常。
   *
   * @param from 当前阶段
   * @param to 目标阶段
   * @return 目标阶段（通过校验后返回）
   * @throws IllegalStateException 如果转换非法
   */
  AgentContext.Phase transition(AgentContext.Phase from, AgentContext.Phase to);

  /**
   * 图中节点的数量。
   *
   * @return 节点数
   */
  int vertexCount();

  /**
   * 图中边的数量。
   *
   * @return 边数
   */
  int edgeCount();
}
