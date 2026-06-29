package org.cland.alice.core.planner.tree;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.cland.alice.core.planner.budget.TokenBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 思维树 (MCTS Tree)，对应设计文档中的 {@code ThinkingTree}。
 *
 * <p>在 Java 内存中维护，每个 {@link ThinkingNode} 包含 State/Action/Value。 利用 {@link ForkJoinPool}
 * 并行评估多个推理分支，加快 MCTS 的模拟（Simulation）阶段。
 *
 * <p>支持序列化到 {@code alice-memory-vault}，实现"思维断点"恢复。
 */
public final class ThinkingTree {

  private static final Logger logger = LoggerFactory.getLogger(ThinkingTree.class);
  private static final double DEFAULT_EXPLORATION_CONSTANT = Math.sqrt(2);

  /** 高访问阈值：超过此值视为成熟分支 */
  private static final int HIGH_VISIT_THRESHOLD = 5;

  /** 根节点 */
  private final ThinkingNode root;

  /** 所有节点的视图（便于序列化） */
  private final List<ThinkingNode> allNodes;

  /** 父节点 -> 子节点列表的映射 */
  private final Map<Integer, List<ThinkingNode>> children;

  /** 并行池 — 用于并行模拟 */
  private final ForkJoinPool forkJoinPool;

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
    this.forkJoinPool = ForkJoinPool.commonPool();
    this.tokenBudget = TokenBudget.unlimited();
    this.nodeCount = new AtomicInteger(1);
    this.depth = 0;

    allNodes.add(root);
    children.put(root.nodeId(), new CopyOnWriteArrayList<>());
  }

  // ========== Getters ==========

  public ThinkingNode root() {
    return root;
  }

  public int nodeCount() {
    return nodeCount.get();
  }

  public int depth() {
    return depth;
  }

  public TokenBudget tokenBudget() {
    return tokenBudget;
  }

  public void setTokenBudget(TokenBudget tokenBudget) {
    this.tokenBudget = Objects.requireNonNull(tokenBudget, "tokenBudget must not be null");
  }

  /** 获取所有节点的不可变快照 */
  public List<ThinkingNode> allNodes() {
    return List.copyOf(allNodes);
  }

  /** 获取指定节点的子节点列表 */
  public List<ThinkingNode> getChildren(ThinkingNode node) {
    return children.getOrDefault(node.nodeId(), List.of());
  }

  // ========== 树操作 ==========

  /**
   * 展开 (Expand) 一个节点：生成若干子节点。
   *
   * <p>对应设计文档中的 expand(ThinkingNode parent)。
   *
   * @param parent 待展开的父节点
   * @param childGenerators 子节点生成器列表，每个生成器接收父节点并返回子节点
   */
  public void expand(
      ThinkingNode parent, List<Function<ThinkingNode, ThinkingNode>> childGenerators) {
    if (parent.expanded()) {
      logger.warn("Node {} already expanded", parent.nodeId());
      return;
    }

    List<ThinkingNode> newChildren = new ArrayList<>();
    for (var generator : childGenerators) {
      ThinkingNode child = generator.apply(parent);
      child.setParent(parent);
      newChildren.add(child);
      allNodes.add(child);
    }

    children
        .computeIfAbsent(parent.nodeId(), k -> new CopyOnWriteArrayList<>())
        .addAll(newChildren);
    parent.markExpanded();
    nodeCount.addAndGet(newChildren.size());

    // 更新深度
    updateDepth(parent);
  }

  /**
   * 评估 (Evaluate) 一个节点：对节点及其子树打分。
   *
   * <p>对应设计文档中的 evaluate(ThinkingNode node)。
   *
   * @param node 待评估节点
   * @param evaluator 评估函数，接收节点状态返回奖励值
   */
  public void evaluate(ThinkingNode node, Function<Map<String, Object>, Double> evaluator) {
    double reward = evaluator.apply(node.state());
    node.setReward(reward);
    logger.debug("[Tree] evaluate node {} reward={}", node.nodeId(), reward);
  }

  /**
   * 并行评估多个节点。
   *
   * @param nodes 待评估节点列表
   * @param evaluator 评估函数
   */
  public void evaluateParallel(
      List<ThinkingNode> nodes, Function<Map<String, Object>, Double> evaluator) {
    forkJoinPool.invoke(new EvaluateTask(nodes, evaluator));
  }

  /**
   * 回溯 (Backpropagate)：从节点向上更新所有祖先节点的奖励和访问计数。
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
   * 获取根节点平均分最高的子节点（按 avg_reward = total_reward / visit_count 排序）。 对应 MCTS 输出规范：选择根节点 avg_reward
   * 最高的子步骤作为下一步执行动作。
   *
   * @return avg_reward 最高的子节点，若无子节点则返回 null
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

  /**
   * 选择最优子节点（基于 UCT）。
   *
   * @param parent 父节点
   * @return UCT 值最高的子节点
   */
  public ThinkingNode selectBestChild(ThinkingNode parent) {
    List<ThinkingNode> childList = getChildren(parent);
    if (childList.isEmpty()) return null;

    final int totalVisits = parent.visits() > 0 ? parent.visits() : 1; // 避免除零

    return childList.stream()
        .max(Comparator.comparingDouble(n -> n.uct(totalVisits, DEFAULT_EXPLORATION_CONSTANT)))
        .orElse(null);
  }

  /**
   * 获取当前最优路径（从根到最佳叶节点）。
   *
   * @return 从根到最优叶节点的节点列表
   */
  public List<ThinkingNode> bestPath() {
    List<ThinkingNode> path = new ArrayList<>();
    ThinkingNode current = root;

    path.add(current);
    while (current.expanded()) {
      ThinkingNode best = selectBestChild(current);
      if (best == null) break;
      path.add(best);
      current = best;
    }

    return path;
  }

  /** 获取最佳叶节点（最优路径的末端）。 */
  public ThinkingNode bestLeaf() {
    List<ThinkingNode> path = bestPath();
    return path.isEmpty() ? root : path.get(path.size() - 1);
  }

  /** 遍历所有节点。 */
  public void forEach(Consumer<ThinkingNode> visitor) {
    traverse(root, visitor);
  }

  // ========== MCTS 完整迭代 ==========

  /**
   * 执行一次完整的 MCTS 迭代： Selection -> Expansion -> Simulation -> Backpropagation。
   *
   * @param iteration 当前迭代次数（1-indexed）
   * @param maxIterations 总迭代次数
   * @param expander 展开函数：接收叶节点，返回候选子节点列表
   * @param simulator 模拟函数：接收节点状态，返回模拟奖励
   */
  public void mctsIteration(
      int iteration,
      int maxIterations,
      Function<ThinkingNode, List<ThinkingNode>> expander,
      Function<Map<String, Object>, Double> simulator) {

    if (tokenBudget.isExhausted()) {
      logger.warn("[MCTS] Token budget exhausted, stopping iteration");
      return;
    }

    // 1. Selection: 从根节点选择到最佳叶节点
    ThinkingNode selected = select(root);
    if (selected == null) return;

    // 记录选中路径（用于日志）
    List<ThinkingNode> selectedPath = pathFromRoot(selected);

    // 2. Expansion: 展开叶节点
    List<ThinkingNode> candidates = expander.apply(selected);
    if (!candidates.isEmpty()) {
      expand(selected, c -> candidates);
      // 选择第一个子节点继续模拟
      selected = getChildren(selected).get(0);
    }

    // 3. Simulation: 模拟评估
    double reward = simulator.apply(selected.state());

    // 4. Backpropagation: 回溯更新
    backpropagate(selected, reward);

    tokenBudget.consume(selected);

    // 5. 详细日志（per-iteration）
    logIterationDetail(iteration, maxIterations, selectedPath, selected);

    logger.debug("[MCTS] iteration complete, nodes={}, depth={}", nodeCount.get(), depth);
  }

  /** 执行多次 MCTS 迭代。 */
  public void mctsIterations(
      int iterations,
      Function<ThinkingNode, List<ThinkingNode>> expander,
      Function<Map<String, Object>, Double> simulator) {
    for (int i = 0; i < iterations; i++) {
      if (tokenBudget.isExhausted()) break;
      mctsIteration(i + 1, iterations, expander, simulator);
    }
    logger.info(
        "[MCTS] {} iterations done, nodes={}, depth={}", iterations, nodeCount.get(), depth);
  }

  // ========== 序列化支持 ==========

  /**
   * 将树序列化为可存储的扁平结构。 每条记录为：{nodeId, parentId, actionType, actionTarget, reward, visits, expanded}
   */
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

  // ========== 日志方法 ==========

  /**
   * 打印单次迭代的详细日志（符合 MCTS 输出规范）。
   *
   * <p>格式：
   *
   * <pre>
   * Iteration {N}/{MAX} | selected: ROOT → action1 → action2
   *   ROOT: visits=8, avg_reward=1.5
   *   ├─ LLM_INFERENCE: visits=5, avg=2.10, UCB=2.71
   *   ├─ OBSERVE: visits=3, avg=1.20, UCB=2.89 ← HIGH UCB (low visits)
   *   └─ TOOL_CALL: visits=0, UCB=MAX ← unexplored
   * </pre>
   */
  private void logIterationDetail(
      int iteration,
      int maxIterations,
      List<ThinkingNode> selectedPath,
      ThinkingNode simulatedChild) {
    if (!logger.isInfoEnabled()) return;

    // 构建选中路径字符串
    StringBuilder pathStr = new StringBuilder();
    for (int i = 0; i < selectedPath.size(); i++) {
      if (i > 0) pathStr.append(" → ");
      ThinkingNode n = selectedPath.get(i);
      pathStr.append(n.actionType());
      if (!"ROOT".equals(n.actionType())) {
        pathStr.append("(").append(n.actionTarget()).append(")");
      }
    }
    pathStr
        .append(" → ")
        .append(simulatedChild.actionType())
        .append("(")
        .append(simulatedChild.actionTarget())
        .append(")");

    logger.info("Iteration {}/{} | selected: {}", iteration, maxIterations, pathStr);

    // 打印路径上每个节点的子节点详情
    for (ThinkingNode pathNode : selectedPath) {
      List<ThinkingNode> siblings = getChildren(pathNode);
      if (siblings.isEmpty()) continue;

      logNodeChildren(pathNode, siblings);
    }

    // 打印模拟节点的同层信息
    if (simulatedChild.parent() != null) {
      ThinkingNode simParent = simulatedChild.parent();
      if (simParent != null && !selectedPath.contains(simParent)) {
        logNodeChildren(simParent, getChildren(simParent));
      }
    }
  }

  /** 打印某个父节点的所有子节点详情。 */
  private void logNodeChildren(ThinkingNode parent, List<ThinkingNode> siblings) {
    StringBuilder childLog = new StringBuilder();
    String indent = "  ";
    childLog.append(indent).append(parent.actionType()).append(" → ");
    if (parent.isRoot()) {
      childLog
          .append("ROOT: visits=")
          .append(parent.visits())
          .append(
              String.format(
                  ", avg_reward=%.2f",
                  parent.visits() > 0 ? parent.reward() / parent.visits() : 0.0));
    }
    childLog.append("\n");

    int parentVisits = parent.visits() > 0 ? parent.visits() : 1;

    for (int i = 0; i < siblings.size(); i++) {
      ThinkingNode sibling = siblings.get(i);
      String prefix = (i == siblings.size() - 1) ? "  └─ " : "  ├─ ";

      double avgReward = sibling.visits() > 0 ? sibling.reward() / sibling.visits() : 0.0;
      double ucb =
          sibling.visits() > 0
              ? sibling.uct(parentVisits, DEFAULT_EXPLORATION_CONSTANT)
              : Double.MAX_VALUE;

      childLog
          .append(indent)
          .append(prefix)
          .append(sibling.actionType())
          .append("(")
          .append(sibling.actionTarget())
          .append("): visits=")
          .append(sibling.visits())
          .append(String.format(", avg=%.2f", avgReward))
          .append(
              String.format(
                  ", UCB=%s", ucb == Double.MAX_VALUE ? "MAX" : String.format("%.2f", ucb)));

      // 标记
      if (sibling.visits() == 0) {
        childLog.append(" ← unexplored");
      } else if (sibling.visits() < HIGH_VISIT_THRESHOLD && ucb > 0) {
        childLog.append(" ← HIGH UCB (low visits)");
      } else if (sibling.visits() >= HIGH_VISIT_THRESHOLD && avgReward > 1.5) {
        childLog.append(" ← mature");
      }

      childLog.append("\n");
    }

    logger.info(childLog.toString().stripTrailing());
  }

  // ========== 内部方法 ==========

  /** 选择（Selection）：从根到最佳叶节点 */
  private ThinkingNode select(ThinkingNode node) {
    ThinkingNode current = node;
    while (current.expanded()) {
      List<ThinkingNode> childList = getChildren(current);
      if (childList.isEmpty()) break;
      current = selectBestChild(current);
    }
    return current;
  }

  /** BFS 遍历树 */
  private void traverse(ThinkingNode node, Consumer<ThinkingNode> visitor) {
    visitor.accept(node);
    for (ThinkingNode child : getChildren(node)) {
      traverse(child, visitor);
    }
  }

  /** 更新树深度 — 计算从任意节点到根的最长路径 */
  private void updateDepth(ThinkingNode start) {
    // 从新添加的每个子节点向上计算深度
    // 此方法在 expand 后应传入父节点，但实际深度需要在子节点上计算
    // 改为遍历所有叶节点计算最大深度
    int maxDepthFromLeaves = 0;
    for (ThinkingNode node : allNodes) {
      if (!node.expanded() || getChildren(node).isEmpty()) {
        // 叶节点：计算到根的深度
        int d = 0;
        ThinkingNode current = node;
        while (current != null) {
          d++;
          current = current.parent();
        }
        if (d > maxDepthFromLeaves) {
          maxDepthFromLeaves = d;
        }
      }
    }
    if (maxDepthFromLeaves > depth) {
      depth = maxDepthFromLeaves;
    }
  }

  // ========== 展开辅助 ==========

  /**
   * 适配展开接口：将 Function<ThinkingNode, List<ThinkingNode>> 转为 List<Function<ThinkingNode,
   * ThinkingNode>>
   */
  private void expand(ThinkingNode parent, Function<ThinkingNode, List<ThinkingNode>> generator) {
    List<ThinkingNode> newNodes = generator.apply(parent);
    if (newNodes.isEmpty()) return;

    for (ThinkingNode child : newNodes) {
      child.setParent(parent);
      allNodes.add(child);
    }
    children.computeIfAbsent(parent.nodeId(), k -> new CopyOnWriteArrayList<>()).addAll(newNodes);
    parent.markExpanded();
    nodeCount.addAndGet(newNodes.size());
    updateDepth(parent);
  }

  // ========== 并行评估任务 ==========

  /** ForkJoinTask 用于并行评估多个节点。 */
  private final class EvaluateTask extends RecursiveAction {
    private static final int THRESHOLD = 4;
    private final List<ThinkingNode> nodes;
    private final Function<Map<String, Object>, Double> evaluator;

    EvaluateTask(List<ThinkingNode> nodes, Function<Map<String, Object>, Double> evaluator) {
      this.nodes = nodes;
      this.evaluator = evaluator;
    }

    @Override
    protected void compute() {
      if (nodes.size() <= THRESHOLD) {
        nodes.forEach(node -> evaluate(node, evaluator));
      } else {
        int mid = nodes.size() / 2;
        var left = new EvaluateTask(nodes.subList(0, mid), evaluator);
        var right = new EvaluateTask(nodes.subList(mid, nodes.size()), evaluator);
        invokeAll(left, right);
      }
    }
  }
}
