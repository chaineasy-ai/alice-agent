package org.cland.alice.core.agent.kernel.graph;

/**
 * 图节点（§5.2.1）。
 *
 * @param id 全局唯一节点 ID（含嵌套子图，由装配方保证）
 * @param kind 节点类型 —— 决定解释器行为
 * @param attrs 属性/注解（预算、guard 名等），解释器按约定键读取
 * @param composite 类型为 COMPOSITE 时的展开规格（内部图 + exit port 绑定表）
 */
public record GraphNode(
    String id, NodeKind kind, java.util.Map<String, Object> attrs, CompositeSpec composite) {

  /** 预算注解键：effect 节点可执行效果次数上限。 */
  public static final String ATTR_MAX_EFFECTS = "budgetEffects";

  public GraphNode {
    attrs = attrs != null ? java.util.Map.copyOf(attrs) : java.util.Map.of();
    if (kind == NodeKind.COMPOSITE) {
      if (composite == null) {
        throw new IllegalArgumentException("COMPOSITE node [" + id + "] requires a CompositeSpec");
      }
    } else if (composite != null) {
      throw new IllegalArgumentException(
          "node [" + id + "] kind " + kind + " cannot carry a CompositeSpec");
    }
  }

  /** 便捷工厂：无注解节点。 */
  public static GraphNode of(String id, NodeKind kind) {
    return new GraphNode(id, kind, java.util.Map.of(), null);
  }

  /** 便捷工厂：带注解节点。 */
  public static GraphNode of(String id, NodeKind kind, java.util.Map<String, Object> attrs) {
    return new GraphNode(id, kind, attrs, null);
  }

  /** 便捷工厂：复合节点。 */
  public static GraphNode composite(String id, CompositeSpec composite) {
    return new GraphNode(id, NodeKind.COMPOSITE, java.util.Map.of(), composite);
  }

  /** 读取属性（无则返回默认值）。 */
  public Object attr(String key, Object defaultValue) {
    return attrs.containsKey(key) ? attrs.get(key) : defaultValue;
  }

  /** 是否复合节点。 */
  public boolean isComposite() {
    return kind == NodeKind.COMPOSITE;
  }
}
