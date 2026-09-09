package org.cland.alice.core.agent.kernel.graph;

/**
 * 有向图边（§5.2.1）。
 *
 * <p>边语义：DECISION/GATE 节点的出边按 port 命名（决策输出 d.route / gate 的 pass/guard 端口选择目标边）；
 * 其余类型节点最多一条无条件出边（port 为 null）。
 *
 * @param from 源节点 id
 * @param to 目标节点 id（可为 {@link SessionGraph#SESSION_END} 以外的任意节点）
 * @param port 端口名（可为 null）
 */
public record GraphEdge(String from, String to, String port) {

  public static GraphEdge of(String from, String to) {
    return new GraphEdge(from, to, null);
  }

  public static GraphEdge port(String from, String to, String port) {
    return new GraphEdge(from, to, port);
  }
}
