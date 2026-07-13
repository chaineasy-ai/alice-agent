package org.cland.alice.core.planner.tree;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.cland.alice.core.planner.budget.TokenBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 思维树 — MCTS 搜索树的数据结构。
 *
 * <p>在 Java 内存中维护，每个 {@link ThinkingNode} 包含 State/Action/Value。 只负责树结构的维护（节点、边、遍历），MCTS 算法逻辑由
 * {@link MctsEngine} 驱动。
 *
 * <p>支持序列化到 {@code alice-memory-vault}，实现"思维断点"恢复。
 *
 * @see MctsEngine
 * @see ThinkingNode
 */
public final class ThinkingTree {

  private static final Logger logger = LoggerFactory.getLogger(ThinkingTree.class);

  /** 根节点 */
  private final ThinkingNode root;

  /** 所有节点的视图（便于序列化） */
  private final List<ThinkingNode> allNodes;

  /** 父节点 -> 子节点列表的映射 */
  private final Map<Integer, List<ThinkingNode>> children;

  /** Token 预算控制 */
  private volatile TokenBudget tokenBudget;

  /** 树的总节点数 */
  private final AtomicInteger nodeCount;

  /** 树深度 */
  private volatile int depth;

  // ========== 构造 ==========

  public ThinkingTree(Map<String, Object> rootState) {
    this.root =
        ThinkingNode.builder()
            .state(rootState)
            .actionType("ROOT")
            .actionTarget("ROOT")
            .thought("Root node")
            .build();
    this.allNodes = new CopyOnWriteArrayList<>();
    this.children = new HashMap<>();
    this.tokenBudget = TokenBudget.unlimited();
    this.nodeCount = new AtomicInteger(1);
    this.depth = 0;

    allNodes.add(root);
    children.put(root.nodeId(), new CopyOnWriteArrayList<>());
  }

  // ========== Getters ==========

  /** 根节点。 */
  public ThinkingNode root() {
    return root;
  }

  /** 树的总节点数。 */
  public int nodeCount() {
    return nodeCount.get();
  }

  /** 树的最大深度。 */
  public int depth() {
    return depth;
  }

  /** Token 预算。 */
  public TokenBudget tokenBudget() {
    return tokenBudget;
  }

  public void setTokenBudget(TokenBudget tokenBudget) {
    this.tokenBudget = Objects.requireNonNull(tokenBudget, "tokenBudget must not be null");
  }

  /** 获取所有节点的不可变快照。 */
  public List<ThinkingNode> allNodes() {
    return List.copyOf(allNodes);
  }

  /** 获取指定节点的子节点列表。 */
  public List<ThinkingNode> getChildren(ThinkingNode node) {
    return children.getOrDefault(node.nodeId(), List.of());
  }

  // ========== 树操作 ==========

  /**
   * 展开父节点，挂载子节点列表。
   *
   * @param parent 待展开的父节点
   * @param newChildren 子节点列表（会被设置 parent 引用）
   */
  public void expand(ThinkingNode parent, List<ThinkingNode> newChildren) {
    if (newChildren == null || newChildren.isEmpty()) return;

    if (parent.expanded()) {
      logger.warn("Node {} already expanded, skipping", parent.nodeId());
      return;
    }

    for (ThinkingNode child : newChildren) {
      child.setParent(parent);
      allNodes.add(child);
    }

    children
        .computeIfAbsent(parent.nodeId(), k -> new CopyOnWriteArrayList<>())
        .addAll(newChildren);
    parent.markExpanded();
    nodeCount.addAndGet(newChildren.size());
    updateDepth();
  }

  /**
   * 回溯：从指定节点向上更新所有祖先的奖励和访问计数。
   *
   * @param node 起点节点
   * @param rewardDelta 奖励增量
   */
  public void backpropagate(ThinkingNode node, double rewardDelta) {
    ThinkingNode current = node;
    while (current != null) {
      current.addReward(rewardDelta);
      current.incrementVisits();
      current = current.parent();
    }
  }

  /**
   * 选择最优子节点（基于 UCT）。
   *
   * @param parent 父节点
   * @param explorationConstant 探索常数 C（通常 √2）
   * @return UCT 值最高的子节点
   */
  public ThinkingNode selectBestChild(ThinkingNode parent, double explorationConstant) {
    List<ThinkingNode> childList = getChildren(parent);
    if (childList.isEmpty()) return null;

    final int totalVisits = Math.max(parent.visits(), 1);

    return childList.stream()
        .max(Comparator.comparingDouble(n -> n.uct(totalVisits, explorationConstant)))
        .orElse(null);
  }

  /**
   * 获取根节点下 avg_reward 最高的子节点。
   *
   * @return avg_reward 最高的子节点，若无则返回 null
   */
  public ThinkingNode bestChildByAvgReward() {
    List<ThinkingNode> rootChildren = getChildren(root);
    if (rootChildren.isEmpty()) return null;

    return rootChildren.stream()
        .filter(n -> n.visits() > 0)
        .max(Comparator.comparingDouble(n -> n.reward() / n.visits()))
        .orElse(null);
  }

  /**
   * 获取从根到指定节点的路径。
   *
   * @param node 目标节点
   * @return 从根到 node 的节点列表
   */
  public List<ThinkingNode> pathFromRoot(ThinkingNode node) {
    List<ThinkingNode> path = new ArrayList<>();
    ThinkingNode current = node;
    while (current != null) {
      path.add(0, current);
      current = current.parent();
    }
    return path;
  }

  /** 遍历所有节点。 */
  public void forEach(Consumer<ThinkingNode> visitor) {
    traverse(root, visitor);
  }

  // ========== 序列化 ==========

  /** 将树序列化为扁平结构。 每条记录：{nodeId, parentId, actionType, actionTarget, reward, visits, expanded} */
  public List<Map<String, Object>> serialize() {
    List<Map<String, Object>> records = new ArrayList<>();
    forEach(
        node -> {
          var record = new LinkedHashMap<String, Object>();
          record.put("nodeId", node.nodeId());
          record.put("parentId", node.parent() != null ? node.parent().nodeId() : -1);
          record.put("actionType", node.actionType());
          record.put("actionTarget", node.actionTarget());
          record.put("reward", node.reward());
          record.put("visits", node.visits());
          record.put("expanded", node.expanded());
          record.put("observation", node.observation());
          records.add(record);
        });
    return records;
  }

  /** 重置树（清空所有子节点，保留根节点）。 */
  public void reset() {
    children.clear();
    allNodes.clear();
    allNodes.add(root);
    children.put(root.nodeId(), new CopyOnWriteArrayList<>());
    nodeCount.set(1);
    depth = 0;
  }

  // ========== 内部 ==========

  /** BFS 遍历。 */
  private void traverse(ThinkingNode node, Consumer<ThinkingNode> visitor) {
    visitor.accept(node);
    for (ThinkingNode child : getChildren(node)) {
      traverse(child, visitor);
    }
  }

  /** 更新树深度：遍历所有叶节点，计算到根的最大深度。 */
  private void updateDepth() {
    int maxDepth = 0;
    for (ThinkingNode node : allNodes) {
      if (!node.expanded() || getChildren(node).isEmpty()) {
        int d = 0;
        ThinkingNode current = node;
        while (current != null) {
          d++;
          current = current.parent();
        }
        if (d > maxDepth) maxDepth = d;
      }
    }
    if (maxDepth > depth) depth = maxDepth;
  }
}
