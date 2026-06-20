package org.cland.alice.core.planner.strategy;

import java.util.*;
import java.util.function.Function;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.core.planner.model.PlannerModelSupplier;
import org.cland.alice.core.planner.tree.ThinkingNode;
import org.cland.alice.core.planner.tree.ThinkingTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 慢速路径策略 (System 2)，对应设计文档中的 {@code SlowPathStrategy}。
 *
 * <p>适用于高复杂度任务，通过 MCTS (Monte Carlo Tree Search) 进行 树搜索与模拟，生成精炼规划。
 *
 * <p>内部维护 {@link ThinkingTree}，每个 {@link ThinkingNode} 包含 State/Action/Value，利用 ForkJoinPool 并行模拟。
 */
public final class SlowPathStrategy implements DecisionStrategy {

  private static final Logger logger = LoggerFactory.getLogger(SlowPathStrategy.class);

  /** MCTS 默认迭代次数 */
  private static final int DEFAULT_MCTS_ITERATIONS = 20;

  /** 默认探索常数 */
  private static final double DEFAULT_EXPLORATION_CONSTANT = Math.sqrt(2);

  private final ThinkingTree tree;
  private final PlannerModelSupplier modelSupplier;
  private final int mctsIterations;
  private final double explorationConstant;

  private SlowPathStrategy(Builder builder) {
    this.tree = builder.tree;
    this.modelSupplier = builder.modelSupplier;
    this.mctsIterations = builder.mctsIterations;
    this.explorationConstant = builder.explorationConstant;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public Plan decide(Map<String, Object> context) {
    String prompt = (String) context.getOrDefault("prompt", "");
    String result = context.containsKey("result") ? context.get("result").toString() : null;

    logger.info("[SlowPath] starting MCTS for prompt length={}", prompt.length());

    // 如果有最终结果，直接返回 FINISH
    if (result != null && !result.isEmpty()) {
      return Plan.builder()
          .type(Plan.Type.SLOW_PATH)
          .summary("Task completed via slow path")
          .addStep(Plan.Step.of("FINISH", "FINISH"))
          .metadata(Map.of("path", "slow", "treeNodes", tree.nodeCount()))
          .build();
    }

    // 创建根节点上下文
    Map<String, Object> rootState = new LinkedHashMap<>(context);
    rootState.put("prompt", prompt);

    // 运行 MCTS
    runMcts(rootState);

    // 从最优路径生成 Plan
    List<ThinkingNode> bestPath = tree.bestPath();
    logger.info(
        "[SlowPath] best path length={}, nodes visited={}", bestPath.size(), tree.nodeCount());

    // 将最优路径转换为 Plan 步骤
    Plan.Builder planBuilder =
        Plan.builder()
            .type(Plan.Type.SLOW_PATH)
            .summary("MCTS refined plan")
            .metadata(
                Map.of(
                    "path",
                    "slow",
                    "treeNodes",
                    tree.nodeCount(),
                    "treeDepth",
                    tree.depth(),
                    "mctsIterations",
                    mctsIterations));

    // 跳过根节点（ROOT），从动作节点开始
    for (int i = 1; i < bestPath.size(); i++) {
      ThinkingNode node = bestPath.get(i);
      planBuilder.addStep(
          Plan.Step.of(
              node.actionType(), node.actionTarget(), node.actionParams(), node.thought()));
    }

    // 如果路径中没有步骤，添加默认 LLM 推理
    if (bestPath.size() <= 1) {
      planBuilder.addStep(
          Plan.Step.of(
              "LLM_INFERENCE",
              modelSupplier != null ? "gpt-4o" : "gpt-4o-mini",
              Map.of("prompt", prompt),
              "MCTS selected direct LLM inference"));
    }

    // 最后添加 FINISH
    planBuilder.addStep(Plan.Step.of("FINISH", "FINISH"));

    return planBuilder.build();
  }

  /**
   * 运行 MCTS 树搜索。
   *
   * <p>通过展开生成候选动作子节点，模拟评估各路径，回溯更新奖励。
   */
  private void runMcts(Map<String, Object> rootState) {
    // 展开器：从叶节点生成候选子节点
    Function<ThinkingNode, List<ThinkingNode>> expander =
        leaf -> {
          // 基于状态生成候选动作
          List<ThinkingNode> candidates = new ArrayList<>();

          // 候选 1: LLM_INFERENCE
          candidates.add(
              ThinkingNode.builder()
                  .state(rootState)
                  .actionType("LLM_INFERENCE")
                  .actionTarget(modelSupplier != null ? "gpt-4o" : "gpt-4o-mini")
                  .actionParams(Map.of("prompt", rootState.getOrDefault("prompt", "")))
                  .thought("Generate reasoning via LLM")
                  .reward(0.0)
                  .visits(0)
                  .build());

          // 候选 2: TOOL_CALL (如果上下文中有工具)
          if (rootState.containsKey("availableTools")) {
            @SuppressWarnings("unchecked")
            List<String> tools = (List<String>) rootState.get("availableTools");
            for (String tool : tools) {
              candidates.add(
                  ThinkingNode.builder()
                      .state(rootState)
                      .actionType("TOOL_CALL")
                      .actionTarget(tool)
                      .actionParams(Map.of())
                      .thought("Execute tool: " + tool)
                      .reward(0.0)
                      .visits(0)
                      .build());
            }
          }

          // 候选 3: OBSERVE
          candidates.add(
              ThinkingNode.builder()
                  .state(rootState)
                  .actionType("OBSERVE")
                  .actionTarget("ENVIRONMENT")
                  .actionParams(Map.of())
                  .thought("Observe environment change")
                  .reward(0.0)
                  .visits(0)
                  .build());

          // 候选 4: REVISION (如果上下文有反馈)
          if (rootState.containsKey("lastFeedback")) {
            candidates.add(
                ThinkingNode.builder()
                    .state(rootState)
                    .actionType("REVISION")
                    .actionTarget("REVISION")
                    .actionParams(Map.of("feedback", rootState.get("lastFeedback")))
                    .thought("Revise based on feedback")
                    .reward(0.0)
                    .visits(0)
                    .build());
          }

          return candidates;
        };

    // 模拟器：评估节点状态的奖励
    Function<Map<String, Object>, Double> simulator =
        state -> {
          // 模拟执行并返回分数
          // 未来可替换为真实模型调用或验证器
          String currentPrompt = (String) state.getOrDefault("prompt", "");
          // 基于 prompt 长度的简单启发式奖励
          double baseReward = 1.0;
          if (currentPrompt.length() > 0) {
            baseReward += Math.min(currentPrompt.length() / 100.0, 2.0);
          }
          return baseReward;
        };

    // 执行 MCTS
    tree.mctsIterations(mctsIterations, expander, simulator);
  }

  /** 获取内部思维树（用于序列化/检查）。 */
  public ThinkingTree tree() {
    return tree;
  }

  // ========== Builder ==========

  public static final class Builder {
    private ThinkingTree tree;
    private PlannerModelSupplier modelSupplier;
    private int mctsIterations = DEFAULT_MCTS_ITERATIONS;
    private double explorationConstant = DEFAULT_EXPLORATION_CONSTANT;

    private Builder() {}

    public Builder tree(ThinkingTree tree) {
      this.tree = tree;
      return this;
    }

    public Builder modelSupplier(PlannerModelSupplier modelSupplier) {
      this.modelSupplier = modelSupplier;
      return this;
    }

    public Builder mctsIterations(int mctsIterations) {
      this.mctsIterations = mctsIterations;
      return this;
    }

    public Builder explorationConstant(double c) {
      this.explorationConstant = c;
      return this;
    }

    public SlowPathStrategy build() {
      if (tree == null) {
        throw new IllegalStateException("ThinkingTree must be provided");
      }
      return new SlowPathStrategy(this);
    }
  }
}
