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

  /** MCTS 默认迭代次数（符合 MCTS 输出规范：10 轮后停止搜索） */
  private static final int DEFAULT_MCTS_ITERATIONS = 10;

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

    // ═══════════════════════════════════════════════════════════════
    //  Select best child by avg_reward (MCTS 输出规范)
    //
    //  After 10 iterations, stop searching.
    //  Select the root's child with the highest avg_reward
    //  as the NEXT execution action.
    //
    //  Output: Plan[1 action step + FINISH] + MCTS tree summary
    // ═══════════════════════════════════════════════════════════════

    ThinkingNode bestChild = tree.bestChildByAvgReward();
    logger.info(
        "[SlowPath] best child by avg_reward: {} -> {}, treeNodes={}",
        bestChild != null ? bestChild.actionType() : "none",
        bestChild != null ? bestChild.actionTarget() : "none",
        tree.nodeCount());

    int rootChildCount = tree.getChildren(tree.root()).size();
    double bestAvgReward =
        (bestChild != null && bestChild.visits() > 0)
            ? bestChild.reward() / bestChild.visits()
            : 0.0;

    // Build metadata with MCTS search tree summary
    java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
    meta.put("path", "slow");
    meta.put("treeNodes", tree.nodeCount());
    meta.put("treeDepth", tree.depth());
    meta.put("mctsIterations", mctsIterations);
    meta.put("rootChildren", rootChildCount);
    meta.put(
        "bestAction",
        bestChild != null ? bestChild.actionType() + "->" + bestChild.actionTarget() : "none");
    meta.put("bestAvgReward", Math.round(bestAvgReward * 100.0) / 100.0);

    Plan.Builder planBuilder =
        Plan.builder()
            .type(Plan.Type.SLOW_PATH)
            .summary("MCTS selected next action")
            .metadata(meta);

    if (bestChild != null) {
      // 单步输出：根节点下 avg_reward 最高的子步骤作为下一步执行动作
      planBuilder.addStep(
          Plan.Step.of(
              bestChild.actionType(),
              bestChild.actionTarget(),
              bestChild.actionParams(),
              bestChild.thought()));
    } else {
      // Fallback: 无有效子节点，使用 LLM 推理
      String fallbackModelId =
          modelSupplier != null && modelSupplier.getReasoningModel() != null
              ? modelSupplier.getReasoningModel().modelId()
              : "gpt-4o-mini";
      java.util.Map<String, Object> fallbackParams = new java.util.LinkedHashMap<>();
      fallbackParams.put("prompt", prompt);
      if (modelSupplier != null && modelSupplier.getReasoningModel() != null) {
        modelSupplier
            .getReasoningModel()
            .parameters()
            .forEach(
                (k, v) -> {
                  if ("enable_thinking".equals(k) || "reasoning_effort".equals(k)) {
                    fallbackParams.put(k, v);
                  }
                });
      }
      planBuilder.addStep(
          Plan.Step.of(
              "LLM_INFERENCE",
              fallbackModelId,
              fallbackParams,
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
          String reasoningModelId =
              modelSupplier != null && modelSupplier.getReasoningModel() != null
                  ? modelSupplier.getReasoningModel().modelId()
                  : "gpt-4o-mini";
          java.util.Map<String, Object> llmParams = new java.util.LinkedHashMap<>();
          llmParams.put("prompt", rootState.getOrDefault("prompt", ""));
          if (modelSupplier != null && modelSupplier.getReasoningModel() != null) {
            modelSupplier
                .getReasoningModel()
                .parameters()
                .forEach(
                    (k, v) -> {
                      if ("enable_thinking".equals(k) || "reasoning_effort".equals(k)) {
                        llmParams.put(k, v);
                      }
                    });
          }
          candidates.add(
              ThinkingNode.builder()
                  .state(rootState)
                  .actionType("LLM_INFERENCE")
                  .actionTarget(reasoningModelId)
                  .actionParams(llmParams)
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

    // 模拟器：评估节点状态的奖励（0~100 分，符合 MCTS 输出规范）
    Function<Map<String, Object>, Double> simulator =
        state -> {
          // 模拟执行并返回分数
          // 未来可替换为真实模型调用或验证器
          String currentPrompt = (String) state.getOrDefault("prompt", "");
          // 基于 prompt 长度的启发式评分：0~100
          // 空 prompt = 50 分（中立）；长 prompt 最高 100 分
          double score = 50.0;
          if (currentPrompt.length() > 0) {
            score += Math.min(currentPrompt.length() * 10.0, 50.0);
          }
          return score;
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
