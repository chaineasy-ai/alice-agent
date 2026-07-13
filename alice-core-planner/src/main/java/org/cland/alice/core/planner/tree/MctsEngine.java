package org.cland.alice.core.planner.tree;

import java.util.List;
import java.util.Objects;
import org.cland.alice.core.planner.budget.TokenBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCTS 引擎 — 可组合的蒙特卡洛树搜索。
 *
 * <p>封装完整的 MCTS 四步循环： Selection → Expansion → Simulation → Backpropagation。 不包含领域逻辑，expand/simulate
 * 由 {@link Expander} 和 {@link Simulator} 注入。
 *
 * <p>使用示例：
 *
 * <pre>{@code
 * ThinkingTree tree = new ThinkingTree(rootState);
 *
 * MctsEngine engine = MctsEngine.builder(tree)
 *     .expander(leaf -> List.of(...))   // 生成候选 Action
 *     .simulator(node -> 42.0)          // 评估节点价值
 *     .iterations(10)
 *     .build();
 *
 * MctsResult result = engine.run();
 * ThinkingNode best = result.bestChild(); // avg_reward 最高的子节点
 * }</pre>
 *
 * @see ThinkingTree
 * @see ThinkingNode
 */
public final class MctsEngine {

  private static final Logger logger = LoggerFactory.getLogger(MctsEngine.class);

  /** MCTS 默认探索常数 C = √2 */
  public static final double DEFAULT_EXPLORATION_CONSTANT = Math.sqrt(2);

  /** 默认迭代次数 */
  public static final int DEFAULT_ITERATIONS = 10;

  /** 展开器：从叶节点生成候选动作 */
  @FunctionalInterface
  public interface Expander {
    /**
     * 展开叶节点，生成候选子节点列表。
     *
     * @param leaf 待展开的叶节点
     * @return 候选子节点列表（空列表表示不可展开）
     */
    List<ThinkingNode> expand(ThinkingNode leaf);
  }

  /** 模拟器：评估节点状态的价值 */
  @FunctionalInterface
  public interface Simulator {
    /**
     * 模拟节点执行后的奖励值。
     *
     * @param node 待评估的节点
     * @return 奖励值（越大越好）
     */
    double simulate(ThinkingNode node);
  }

  private final ThinkingTree tree;
  private final Expander expander;
  private final Simulator simulator;
  private final int iterations;
  private final double explorationConstant;
  private final TokenBudget tokenBudget;

  private MctsEngine(Builder builder) {
    this.tree = Objects.requireNonNull(builder.tree, "tree must not be null");
    this.expander = Objects.requireNonNull(builder.expander, "expander must not be null");
    this.simulator = Objects.requireNonNull(builder.simulator, "simulator must not be null");
    this.iterations = builder.iterations;
    this.explorationConstant = builder.explorationConstant;
    this.tokenBudget = builder.tokenBudget != null ? builder.tokenBudget : TokenBudget.unlimited();
  }

  public static Builder builder(ThinkingTree tree) {
    return new Builder(tree);
  }

  /**
   * 运行 MCTS，返回搜索结果。
   *
   * @return 搜索结果（含 bestChild + 树统计）
   */
  public MctsResult run() {
    logger.info(
        "[MctsEngine] Starting MCTS: iterations={}, explorationConstant={}",
        iterations,
        explorationConstant);

    for (int i = 0; i < iterations; i++) {
      if (tokenBudget.isExhausted()) {
        logger.warn("[MctsEngine] Token budget exhausted at iteration {}", i + 1);
        break;
      }
      iterate(i + 1);
    }

    ThinkingNode bestChild = tree.bestChildByAvgReward();
    double bestAvgReward =
        (bestChild != null && bestChild.visits() > 0)
            ? bestChild.reward() / bestChild.visits()
            : 0.0;

    logger.info(
        "[MctsEngine] Completed {} iterations: bestChild={}, nodes={}, depth={}",
        iterations,
        bestChild != null ? bestChild.actionType() + "->" + bestChild.actionTarget() : "none",
        tree.nodeCount(),
        tree.depth());

    return new MctsResult(
        bestChild, tree.nodeCount(), tree.depth(), Math.round(bestAvgReward * 100.0) / 100.0);
  }

  /** 单次 MCTS 迭代： Selection → Expansion → Simulation → Backpropagation。 */
  private void iterate(int iteration) {
    // 1. Selection: 从根到最佳叶节点
    ThinkingNode selected = select(tree.root());
    if (selected == null) return;

    // 2. Expansion: 展开叶节点
    List<ThinkingNode> candidates = expander.expand(selected);
    ThinkingNode simulatedNode = selected;
    if (!candidates.isEmpty()) {
      tree.expand(selected, candidates);
      simulatedNode = tree.getChildren(selected).get(0);
    }

    // 3. Simulation
    double reward = simulator.simulate(simulatedNode);

    // 4. Backpropagation
    tree.backpropagate(simulatedNode, reward);

    tokenBudget.consume(simulatedNode);

    logIterationDetail(iteration, selected, simulatedNode);
  }

  /** Selection：从根节点出发，按 UCT 选择最优子节点直到叶节点。 */
  private ThinkingNode select(ThinkingNode node) {
    ThinkingNode current = node;
    while (current.expanded()) {
      List<ThinkingNode> childList = tree.getChildren(current);
      if (childList.isEmpty()) break;
      current = tree.selectBestChild(current, explorationConstant);
    }
    return current;
  }

  // ========== 日志 ==========

  private void logIterationDetail(int iteration, ThinkingNode selected, ThinkingNode simulated) {
    if (!logger.isInfoEnabled()) return;

    StringBuilder pathStr = new StringBuilder();
    pathStr.append("ROOT");
    for (var node : tree.pathFromRoot(selected)) {
      if (node.isRoot()) continue;
      pathStr
          .append(" → ")
          .append(node.actionType())
          .append("(")
          .append(node.actionTarget())
          .append(")");
    }
    pathStr
        .append(" → ")
        .append(simulated.actionType())
        .append("(")
        .append(simulated.actionTarget())
        .append(")");

    logger.info("MCTS Iteration {}/{} | selected: {}", iteration, iterations, pathStr);
  }

  // ========== Result ==========

  /**
   * MCTS 搜索结果。
   *
   * @param bestChild 根节点下 avg_reward 最高的子节点（下一步执行动作）
   * @param totalNodes 搜索树总节点数
   * @param treeDepth 搜索树深度
   * @param bestAvgReward 最佳子节点的平均奖励
   */
  public record MctsResult(
      ThinkingNode bestChild, int totalNodes, int treeDepth, double bestAvgReward) {

    /** 是否有可用结果。 */
    public boolean hasResult() {
      return bestChild != null;
    }

    /** 最佳 Action 类型。 */
    public String actionType() {
      return bestChild != null ? bestChild.actionType() : "none";
    }

    /** 最佳 Action 目标。 */
    public String actionTarget() {
      return bestChild != null ? bestChild.actionTarget() : "none";
    }
  }

  // ========== Builder ==========

  public static final class Builder {
    private final ThinkingTree tree;
    private Expander expander;
    private Simulator simulator;
    private int iterations = DEFAULT_ITERATIONS;
    private double explorationConstant = DEFAULT_EXPLORATION_CONSTANT;
    private TokenBudget tokenBudget;

    private Builder(ThinkingTree tree) {
      this.tree = Objects.requireNonNull(tree, "tree must not be null");
    }

    public Builder expander(Expander expander) {
      this.expander = expander;
      return this;
    }

    public Builder simulator(Simulator simulator) {
      this.simulator = simulator;
      return this;
    }

    public Builder iterations(int iterations) {
      this.iterations = iterations;
      return this;
    }

    public Builder explorationConstant(double c) {
      this.explorationConstant = c;
      return this;
    }

    public Builder tokenBudget(TokenBudget budget) {
      this.tokenBudget = budget;
      return this;
    }

    public MctsEngine build() {
      if (expander == null) {
        throw new IllegalStateException("expander must be provided");
      }
      if (simulator == null) {
        throw new IllegalStateException("simulator must be provided");
      }
      return new MctsEngine(this);
    }
  }
}
