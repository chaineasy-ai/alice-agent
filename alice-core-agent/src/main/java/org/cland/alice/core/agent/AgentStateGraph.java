package org.cland.alice.core.agent;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.builder.GraphTypeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PPAO 状态机图 — 基于 JGrapht 的有向图。
 *
 * <p>将 {@link AgentContext.Phase} 之间的合法转换建模为有向图，替代 {@code AgentContext.canTransitionTo()} 的 switch
 * 表达式。每个 Phase 是一个节点，每条合法转换是一条有向边。
 *
 * <p>与 {@code SopGraph}（SOP DAG）使用相同的 JGrapht 底层，但 {@code AgentStateGraph} 是
 * <b>静态的、不可变的</b>：编译时即确定所有合法转换，运行时只查询不修改。
 *
 * <p>节点（Phase）：
 *
 * <pre>
 *   START, PERCEIVING, PLANNING, VERIFYING_PRE, ACTING,
 *   OBSERVING, VERIFYING_POST, REFLECTING, REVISION, FINISH
 * </pre>
 *
 * <p>唯一自循环：ACTING → ACTING（Micro-ReAct 自循环）。
 *
 * <p>终态：FINISH（出度为 0）。
 *
 * <p>使用示例：
 *
 * <pre>{@code
 * PhaseStateGraph fsm = new AgentStateGraph();
 *
 * // 查询合法转换
 * fsm.canTransition(Phase.PLANNING, Phase.VERIFYING_PRE); // true
 * fsm.canTransition(Phase.PLANNING, Phase.FINISH);        // false
 *
 * // 获取下一阶段集合
 * Set<Phase> next = fsm.nextPhases(Phase.ACTING); // {ACTING, OBSERVING, REVISION, FINISH}
 *
 * // 判断终态
 * fsm.isTerminal(Phase.FINISH); // true
 *
 * // 安全转换（校验失败抛异常）
 * fsm.transition(Phase.START, Phase.PERCEIVING); // OK
 * }</pre>
 *
 * @see AgentContext.Phase
 * @see AgentContext#transitionTo(AgentContext.Phase)
 */
public final class AgentStateGraph implements PhaseStateGraph {

  private static final Logger logger = LoggerFactory.getLogger(AgentStateGraph.class);

  private final Graph<AgentContext.Phase, DefaultEdge> graph;

  // ========================================================================
  // 构造：构建有向图并注册所有合法转换边
  // ========================================================================

  /** 构建标准 PPAO 状态机图。每个 agent 实例应持有自己的副本。 */
  public AgentStateGraph() {
    this.graph =
        GraphTypeBuilder.<AgentContext.Phase, DefaultEdge>directed()
            .allowingSelfLoops(true) // ACTING → ACTING 需要自循环
            .allowingMultipleEdges(false)
            .edgeClass(DefaultEdge.class)
            .buildGraph();

    // ── 添加所有节点 ──────────────────────────────────
    for (var phase : AgentContext.Phase.values()) {
      graph.addVertex(phase);
    }

    // ── 添加合法转换边 ────────────────────────────────
    // 每个 addEdge(from, to) 对应 PPAO 状态机中允许的一次转换

    // START → PERCEIVING：初始入口
    addEdge(AgentContext.Phase.START, AgentContext.Phase.PERCEIVING);

    // PERCEIVING → PLANNING：感知完成，进入规划
    addEdge(AgentContext.Phase.PERCEIVING, AgentContext.Phase.PLANNING);

    // PLANNING → VERIFYING_PRE | REVISION：规划完成后预检，或被拦截进入修订
    addEdge(AgentContext.Phase.PLANNING, AgentContext.Phase.VERIFYING_PRE);
    addEdge(AgentContext.Phase.PLANNING, AgentContext.Phase.REVISION);

    // VERIFYING_PRE → ACTING | REVISION：预检通过后执行，或被拦截
    addEdge(AgentContext.Phase.VERIFYING_PRE, AgentContext.Phase.ACTING);
    addEdge(AgentContext.Phase.VERIFYING_PRE, AgentContext.Phase.REVISION);

    // ACTING 自循环 → ACTING：Micro-ReAct 战术循环
    addEdge(AgentContext.Phase.ACTING, AgentContext.Phase.ACTING);
    // ACTING → OBSERVING | REVISION | FINISH：退出微循环
    addEdge(AgentContext.Phase.ACTING, AgentContext.Phase.OBSERVING);
    addEdge(AgentContext.Phase.ACTING, AgentContext.Phase.REVISION);
    addEdge(AgentContext.Phase.ACTING, AgentContext.Phase.FINISH);

    // OBSERVING → VERIFYING_POST | REVISION | FINISH：观察后审计、修订或结束
    addEdge(AgentContext.Phase.OBSERVING, AgentContext.Phase.VERIFYING_POST);
    addEdge(AgentContext.Phase.OBSERVING, AgentContext.Phase.REVISION);
    addEdge(AgentContext.Phase.OBSERVING, AgentContext.Phase.FINISH);

    // VERIFYING_POST → REFLECTING | FINISH | REVISION：审计后复盘、结束或修订
    addEdge(AgentContext.Phase.VERIFYING_POST, AgentContext.Phase.REFLECTING);
    addEdge(AgentContext.Phase.VERIFYING_POST, AgentContext.Phase.FINISH);
    addEdge(AgentContext.Phase.VERIFYING_POST, AgentContext.Phase.REVISION);

    // REFLECTING → PLANNING | REVISION | FINISH：战略复盘后规划、修订或结束
    addEdge(AgentContext.Phase.REFLECTING, AgentContext.Phase.PLANNING);
    addEdge(AgentContext.Phase.REFLECTING, AgentContext.Phase.REVISION);
    addEdge(AgentContext.Phase.REFLECTING, AgentContext.Phase.FINISH);

    // REVISION → PLANNING：修订完成后重新规划
    addEdge(AgentContext.Phase.REVISION, AgentContext.Phase.PLANNING);

    // 任意阶段 → FINISH：允许从任何非终态优雅退出（错误恢复、取消等）
    addEdge(AgentContext.Phase.START, AgentContext.Phase.FINISH);
    addEdge(AgentContext.Phase.PERCEIVING, AgentContext.Phase.FINISH);
    addEdge(AgentContext.Phase.PLANNING, AgentContext.Phase.FINISH);
    addEdge(AgentContext.Phase.VERIFYING_PRE, AgentContext.Phase.FINISH);
    addEdge(AgentContext.Phase.REVISION, AgentContext.Phase.FINISH);

    // FINISH — 终态，无出边

    logger.info(
        "[AgentStateGraph] Initialized with {} nodes and {} edges",
        graph.vertexSet().size(),
        graph.edgeSet().size());
  }

  // ========================================================================
  // 公共 API
  // ========================================================================

  /** 获取全局单例。 */
  /**
   * 判断 {@code from → to} 是否为合法转换。
   *
   * @param from 当前阶段
   * @param to 目标阶段
   * @return true 如果转换合法
   */
  public boolean canTransition(AgentContext.Phase from, AgentContext.Phase to) {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    return graph.containsEdge(from, to);
  }

  /**
   * 获取从 {@code from} 阶段所有合法的下一阶段集合。
   *
   * @param from 当前阶段
   * @return 合法下一阶段的不可变集合
   */
  public Set<AgentContext.Phase> nextPhases(AgentContext.Phase from) {
    Objects.requireNonNull(from, "from must not be null");
    Set<AgentContext.Phase> result = new LinkedHashSet<>();
    for (var edge : graph.outgoingEdgesOf(from)) {
      result.add(graph.getEdgeTarget(edge));
    }
    return Set.copyOf(result);
  }

  /**
   * 判断 {@code phase} 是否为终态（无出边）。
   *
   * <p>目前仅 {@code FINISH} 是终态。
   *
   * @param phase 待检查的阶段
   * @return true 如果是终态
   */
  // isTerminal() 继承默认实现（委派给 Phase.isTerminal()）

  /**
   * 安全转换：校验 {@code from → to} 的合法性，通过则返回 {@code to}，否则抛异常。
   *
   * <p>替代 {@link AgentContext#transitionTo(AgentContext.Phase)} 中的 {@code canTransitionTo()} switch
   * 调用。
   *
   * @param from 当前阶段
   * @param to 目标阶段
   * @return 目标阶段
   * @throws IllegalStateException 如果转换非法
   */
  public AgentContext.Phase transition(AgentContext.Phase from, AgentContext.Phase to) {
    if (!canTransition(from, to)) {
      throw new IllegalStateException(
          "Invalid phase transition: "
              + from
              + " -> "
              + to
              + ". Allowed from "
              + from
              + ": "
              + nextPhases(from));
    }
    return to;
  }

  @Override
  public int vertexCount() {
    return graph.vertexSet().size();
  }

  @Override
  public int edgeCount() {
    return graph.edgeSet().size();
  }

  /** 获取底层 JGrapht {@link Graph}（用于遍历/序列化）。 */
  public Graph<AgentContext.Phase, DefaultEdge> delegate() {
    return graph;
  }

  // ========================================================================
  // 内部
  // ========================================================================

  /** 辅助方法：添加一条有向边。 */
  private void addEdge(AgentContext.Phase from, AgentContext.Phase to) {
    graph.addEdge(from, to);
  }

  @Override
  public String toString() {
    return "AgentStateGraph{nodes="
        + graph.vertexSet().size()
        + ", edges="
        + graph.edgeSet().size()
        + '}';
  }
}
