package org.cland.alice.core.agent.kernel.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话图 G = (V, E)（§5.2.1）—— 执行结构的声明式载体。
 *
 * <p>顶图层与复合节点内部图均为本类型；节点 id 须在整棵装配树内唯一（含嵌套）， 由装配方保证，解释器在 prepare 阶段校验。
 *
 * <p>顶层 TERMINAL 的出口由 {@code terminalExits} 声明（target 为节点 id 或 {@link #SESSION_END}）。
 */
public final class SessionGraph {

  /** 会话级终止标记：terminal 出口指向它时，解释器结束会话。 */
  public static final String SESSION_END = "!session-end";

  private final String id;
  private final String startId;
  private final Map<String, GraphNode> nodes;
  private final Map<String, List<GraphEdge>> outgoing;
  private final Map<String, String> terminalExits;

  private SessionGraph(Builder builder) {
    this.id = Objects.requireNonNull(builder.id, "graph id must not be null");
    this.startId = Objects.requireNonNull(builder.startId, "startId must not be null");
    this.nodes = Map.copyOf(builder.nodes);
    this.terminalExits = Map.copyOf(builder.terminalExits);

    Map<String, List<GraphEdge>> out = new LinkedHashMap<>();
    for (GraphEdge e : builder.edges) {
      out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
    }
    Map<String, List<GraphEdge>> frozen = new LinkedHashMap<>();
    out.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
    this.outgoing = Map.copyOf(frozen);

    // 轻量校验：起点与边引用存在
    if (!nodes.containsKey(startId)) {
      throw new IllegalArgumentException(
          "start node [" + startId + "] not found in graph [" + id + "]");
    }
    for (GraphEdge e : builder.edges) {
      if (!nodes.containsKey(e.from())) {
        throw new IllegalArgumentException(
            "edge from unknown node [" + e.from() + "] in graph [" + id + "]");
      }
      if (!nodes.containsKey(e.to())) {
        throw new IllegalArgumentException(
            "edge to unknown node [" + e.to() + "] in graph [" + id + "]");
      }
    }
  }

  public String id() {
    return id;
  }

  public String startId() {
    return startId;
  }

  public GraphNode start() {
    return nodes.get(startId);
  }

  public GraphNode node(String id) {
    GraphNode n = nodes.get(id);
    if (n == null) {
      throw new IllegalArgumentException("unknown node [" + id + "] in graph [" + this.id + "]");
    }
    return n;
  }

  public Map<String, GraphNode> nodes() {
    return nodes;
  }

  /** 节点出边（不可变）；无出边返回空列表。 */
  public List<GraphEdge> outgoing(String nodeId) {
    return outgoing.getOrDefault(nodeId, List.of());
  }

  /** 顶层 terminal 出口表（terminal id → 目标节点 id 或 SESSION_END）。 */
  public Map<String, String> terminalExits() {
    return terminalExits;
  }

  public static Builder builder(String id) {
    return new Builder(id);
  }

  /** 会话图构建器。 */
  public static final class Builder {
    private final String id;
    private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Map<String, String> terminalExits = new LinkedHashMap<>();
    private String startId;

    private Builder(String id) {
      this.id = id;
    }

    public Builder node(GraphNode node) {
      if (nodes.containsKey(node.id())) {
        throw new IllegalArgumentException(
            "duplicate node id [" + node.id() + "] in graph [" + id + "]");
      }
      nodes.put(node.id(), node);
      return this;
    }

    public Builder start(String nodeId) {
      this.startId = nodeId;
      return this;
    }

    public Builder edge(GraphEdge edge) {
      edges.add(edge);
      return this;
    }

    public Builder edge(String from, String to) {
      return edge(GraphEdge.of(from, to));
    }

    public Builder edge(String from, String to, String port) {
      return edge(GraphEdge.port(from, to, port));
    }

    /** 声明顶层 TERMINAL 的出口。 */
    public Builder terminalExit(String terminalId, String target) {
      terminalExits.put(terminalId, target);
      return this;
    }

    public SessionGraph build() {
      return new SessionGraph(this);
    }
  }
}
