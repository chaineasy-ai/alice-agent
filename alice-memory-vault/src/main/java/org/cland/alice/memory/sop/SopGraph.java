package org.cland.alice.memory.sop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SOP 步骤的有向无环图 (DAG)，基于 JGrapht 实现。
 *
 * <p>SOP（Standard Operating Procedure）是程序性记忆的一种形式，存储在 {@code alice-memory-vault} 中。
 * 相比平铺的步骤列表，{@code SopGraph} 支持：
 *
 * <ul>
 *   <li>条件分支 — 边可携带条件标签（如 "on-success" / "on-failure"）
 *   <li>并行步骤 — 多条出边表示可并行执行的子步骤
 *   <li>拓扑排序 — 自动确定合法执行顺序
 *   <li>GraphML 序列化 — 通过 {@link SopGraphPersistence} 持久化到 {@code ~/.alice/sops/}
 * </ul>
 *
 * <p>SOP DAG 始终在内存中维护（JGrapht 原生图结构），GraphML 仅用于保存/恢复。
 *
 * <p>使用示例：
 *
 * <pre>{@code
 * SopGraph graph = SopGraph.builder("weather-sop", "天气查询流程")
 *     .addNode("start", "LLM_INFERENCE", "parse_query")
 *     .addNode("check", "TOOL_CALL", "get_weather")
 *     .addNode("format", "LLM_INFERENCE", "format_response")
 *     .addNode("end", "FINISH", "FINISH")
 *     .addEdge("start", "check")
 *     .addEdge("check", "format")
 *     .addEdge("format", "end")
 *     .build();
 *
 * // 拓扑排序遍历
 * for (SopNode node : graph.topologicalOrder()) {
 *     System.out.println(node.actionType() + " → " + node.target());
 * }
 * }</pre>
 */
public final class SopGraph {

  private static final Logger logger = LoggerFactory.getLogger(SopGraph.class);

  /** SOP 名称 / ID */
  private final String id;

  /** SOP 描述 */
  private final String description;

  /** 关键词列表（用于语义匹配） */
  private final List<String> keywords;

  /** JGrapht 底层 DAG */
  private final Graph<SopNode, SopEdge> graph;

  /** 节点 ID → SopNode 的快速查找 */
  private final Map<String, SopNode> nodeIndex;

  private SopGraph(Builder builder) {
    this.id = Objects.requireNonNull(builder.id, "id must not be null");
    this.description = builder.description;
    this.keywords = builder.keywords != null ? List.copyOf(builder.keywords) : List.of();
    this.graph = new DefaultDirectedGraph<>(SopEdge.class);
    this.nodeIndex = new LinkedHashMap<>();

    // 添加所有节点
    for (var node : builder.nodes) {
      graph.addVertex(node);
      nodeIndex.put(node.id(), node);
    }

    // 添加所有边
    for (var edgeDef : builder.edges) {
      SopNode source = nodeIndex.get(edgeDef.sourceId);
      SopNode target = nodeIndex.get(edgeDef.targetId);
      if (source == null || target == null) {
        logger.warn(
            "[SopGraph] Edge references unknown node: {} -> {}, skipping",
            edgeDef.sourceId,
            edgeDef.targetId);
        continue;
      }
      SopEdge edge = new SopEdge(edgeDef.label);
      graph.addEdge(source, target, edge);
    }

    // 检查是否有环
    if (!isDag()) {
      logger.warn("[SopGraph] Graph '{}' contains a cycle! This is a DAG violation.", id);
    }
  }

  // ========== Getters ==========

  /** 获取 SOP ID。 */
  public String id() {
    return id;
  }

  /** 获取 SOP 描述。 */
  public String description() {
    return description;
  }

  /** 获取关键词列表。 */
  public List<String> keywords() {
    return keywords;
  }

  /** 获取所有节点。 */
  public Set<SopNode> nodes() {
    return Set.copyOf(graph.vertexSet());
  }

  /** 获取所有边。 */
  public Set<SopEdge> edges() {
    return Set.copyOf(graph.edgeSet());
  }

  /** 根据 ID 查找节点。 */
  public SopNode getNode(String id) {
    return nodeIndex.get(id);
  }

  /** 获取某个节点的出边（后继依赖）。 */
  public Set<SopEdge> outgoingEdgesOf(SopNode node) {
    return graph.outgoingEdgesOf(node);
  }

  /** 获取某个节点的入边（前置依赖）。 */
  public Set<SopEdge> incomingEdgesOf(SopNode node) {
    return graph.incomingEdgesOf(node);
  }

  /** 获取某个节点的后继节点。 */
  public List<SopNode> successorsOf(SopNode node) {
    List<SopNode> result = new ArrayList<>();
    for (SopEdge edge : graph.outgoingEdgesOf(node)) {
      result.add(graph.getEdgeTarget(edge));
    }
    return result;
  }

  /** 获取某个节点的前驱节点。 */
  public List<SopNode> predecessorsOf(SopNode node) {
    List<SopNode> result = new ArrayList<>();
    for (SopEdge edge : graph.incomingEdgesOf(node)) {
      result.add(graph.getEdgeSource(edge));
    }
    return result;
  }

  /** 获取入度为 0 的起始节点。 */
  public List<SopNode> rootNodes() {
    List<SopNode> roots = new ArrayList<>();
    for (SopNode node : graph.vertexSet()) {
      if (graph.incomingEdgesOf(node).isEmpty()) {
        roots.add(node);
      }
    }
    return roots;
  }

  /** 获取出度为 0 的终止节点。 */
  public List<SopNode> leafNodes() {
    List<SopNode> leaves = new ArrayList<>();
    for (SopNode node : graph.vertexSet()) {
      if (graph.outgoingEdgesOf(node).isEmpty()) {
        leaves.add(node);
      }
    }
    return leaves;
  }

  /** 检查是否包含入度为 0 的节点（无前驱）。 */
  public boolean hasRoots() {
    return !rootNodes().isEmpty();
  }

  /** 获取底层 JGrapht {@link Graph}，供序列化/遍历使用。 */
  public Graph<SopNode, SopEdge> delegate() {
    return graph;
  }

  // ========== 遍历 ==========

  /**
   * 拓扑排序遍历。
   *
   * <p>返回一个按拓扑序排列的节点列表，保证每个节点出现在所有前驱节点之后。
   */
  public List<SopNode> topologicalOrder() {
    List<SopNode> ordered = new ArrayList<>();
    TopologicalOrderIterator<SopNode, SopEdge> iterator = new TopologicalOrderIterator<>(graph);
    iterator.forEachRemaining(ordered::add);
    return ordered;
  }

  // ========== 内部检测 ==========

  /** 检测图是否为 DAG（有向无环图）。 */
  private boolean isDag() {
    try {
      new TopologicalOrderIterator<>(graph).forEachRemaining(n -> {});
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  // ========== Builder ==========

  public static Builder builder(String id, String description) {
    return new Builder(id, description);
  }

  /** 构建器。 */
  public static final class Builder {
    private final String id;
    private final String description;
    private List<String> keywords;
    private final List<SopNode> nodes;
    private final List<EdgeDef> edges;

    private Builder(String id, String description) {
      this.id = id;
      this.description = description;
      this.keywords = new ArrayList<>();
      this.nodes = new ArrayList<>();
      this.edges = new ArrayList<>();
    }

    public Builder keywords(List<String> keywords) {
      this.keywords = keywords != null ? new ArrayList<>(keywords) : new ArrayList<>();
      return this;
    }

    public Builder addKeyword(String keyword) {
      this.keywords.add(keyword);
      return this;
    }

    public Builder addNode(String id, String actionType, String target) {
      nodes.add(new SopNode(id, actionType, target, Map.of(), null));
      return this;
    }

    public Builder addNode(
        String id, String actionType, String target, Map<String, Object> parameters) {
      nodes.add(new SopNode(id, actionType, target, parameters, null));
      return this;
    }

    public Builder addNode(
        String id,
        String actionType,
        String target,
        Map<String, Object> parameters,
        String thought) {
      nodes.add(new SopNode(id, actionType, target, parameters, thought));
      return this;
    }

    public Builder addEdge(String sourceId, String targetId, String label) {
      edges.add(new EdgeDef(sourceId, targetId, label));
      return this;
    }

    public Builder addEdge(String sourceId, String targetId) {
      return addEdge(sourceId, targetId, "on-success");
    }

    public SopGraph build() {
      return new SopGraph(this);
    }
  }

  /** 构建时的边定义。 */
  private record EdgeDef(String sourceId, String targetId, String label) {}

  // ========== SopNode ==========

  /**
   * SOP 图中的节点，对应一个步骤定义。
   *
   * <p>节点包含步骤操作信息：操作类型、目标、参数和推理过程。
   */
  public static final class SopNode {

    private final String id;
    private final String actionType;
    private final String target;
    private final Map<String, Object> parameters;
    private final String thought;

    public SopNode(
        String id,
        String actionType,
        String target,
        Map<String, Object> parameters,
        String thought) {
      this.id = Objects.requireNonNull(id, "id must not be null");
      this.actionType = Objects.requireNonNull(actionType, "actionType must not be null");
      this.target = target;
      this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
      this.thought = thought;
    }

    public String id() {
      return id;
    }

    public String actionType() {
      return actionType;
    }

    public String target() {
      return target;
    }

    public Map<String, Object> parameters() {
      return parameters;
    }

    public String thought() {
      return thought;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof SopNode sopNode)) return false;
      return id.equals(sopNode.id);
    }

    @Override
    public int hashCode() {
      return id.hashCode();
    }

    @Override
    public String toString() {
      return "SopNode{" + "id='" + id + '\'' + ", action=" + actionType + ":" + target + '}';
    }
  }

  // ========== SopEdge ==========

  /**
   * SOP 图中的有向边，连接两个步骤节点。
   *
   * <p>边可携带标签，表示流转条件：
   *
   * <ul>
   *   <li>{@code "on-success"} — 步骤执行成功后流转
   *   <li>{@code "on-failure"} — 步骤执行失败后流转
   *   <li>{@code "condition:<expr>"} — 条件表达式满足时流转
   * </ul>
   */
  public static final class SopEdge extends DefaultEdge {

    private String label;

    public SopEdge() {
      this.label = "";
    }

    public SopEdge(String label) {
      this.label = label != null ? label : "";
    }

    public String label() {
      return label;
    }

    /** 设置边标签（导入时由 GraphML 反序列化调用）。 */
    public void setLabel(String label) {
      this.label = label != null ? label : "";
    }

    @Override
    public String toString() {
      return "SopEdge{" + "label='" + label + "'}";
    }
  }
}
