package org.cland.alice.core.planner.strategy;

import java.util.*;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.core.planner.model.PlannerModelSupplier;
import org.cland.alice.core.planner.tree.MctsEngine;
import org.cland.alice.core.planner.tree.MctsEngine.Expander;
import org.cland.alice.core.planner.tree.MctsEngine.Simulator;
import org.cland.alice.core.planner.tree.ThinkingNode;
import org.cland.alice.core.planner.tree.ThinkingTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 慢速路径策略 (System 2)，基于 {@link MctsEngine} 的 Macro 层规划。
 *
 * <p>通过 MCTS 搜索选择下一步战略 Action：LLM_INFERENCE / TOOL_CALL / OBSERVE / REVISION。 expander 和 simulator
 * 可通过 builder 注入，默认使用 Macro 层启发式实现。
 */
public final class SlowPathStrategy implements DecisionStrategy {

  private static final Logger logger = LoggerFactory.getLogger(SlowPathStrategy.class);

  private final MctsEngine engine;
  private final PlannerModelSupplier modelSupplier;

  private SlowPathStrategy(Builder builder) {
    this.modelSupplier = builder.modelSupplier;
    this.engine = buildEngine(builder);
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public Plan decide(Map<String, Object> context) {
    String prompt = (String) context.getOrDefault("prompt", "");
    String result = context.containsKey("result") ? context.get("result").toString() : null;

    logger.info("[SlowPath] starting MCTS for prompt length={}", prompt.length());

    if (result != null && !result.isEmpty()) {
      return Plan.builder()
          .type(Plan.Type.SLOW_PATH)
          .summary("Task completed via slow path")
          .addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH"))
          .build();
    }

    // 运行 MCTS
    MctsEngine.MctsResult mctsResult = engine.run();

    // 构建 Plan
    Plan.Builder planBuilder =
        Plan.builder()
            .type(Plan.Type.SLOW_PATH)
            .summary("MCTS selected next action")
            .metadata(
                Map.of(
                    "path",
                    "slow",
                    "treeNodes",
                    mctsResult.totalNodes(),
                    "treeDepth",
                    mctsResult.treeDepth(),
                    "mctsIterations",
                    10,
                    "bestAction",
                    mctsResult.actionType() + "->" + mctsResult.actionTarget(),
                    "bestAvgReward",
                    mctsResult.bestAvgReward()));

    if (mctsResult.hasResult()) {
      ThinkingNode best = mctsResult.bestChild();
      planBuilder.addStep(
          Plan.Step.of(
              toIntent(best.actionType()),
              best.actionTarget(),
              best.actionParams(),
              best.thought()));
    } else {
      planBuilder.addStep(fallbackStep(prompt));
    }

    planBuilder.addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH"));
    return planBuilder.build();
  }

  /** 无有效 MCTS 结果时的回退。 */
  private Plan.Step fallbackStep(String prompt) {
    String fallbackModelId =
        modelSupplier != null && modelSupplier.getReasoningModel() != null
            ? modelSupplier.getReasoningModel().modelId()
            : "gpt-4o-mini";
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("prompt", prompt);
    if (modelSupplier != null && modelSupplier.getReasoningModel() != null) {
      modelSupplier
          .getReasoningModel()
          .parameters()
          .forEach(
              (k, v) -> {
                if ("enable_thinking".equals(k) || "reasoning_effort".equals(k)) params.put(k, v);
              });
    }
    return Plan.Step.of(Plan.Intent.ANALYZE, fallbackModelId, params, "MCTS fallback: direct LLM");
  }

  /** 构建 MctsEngine。优先使用注入的组件，否则使用默认 Macro 层实现。 */
  private MctsEngine buildEngine(Builder builder) {
    ThinkingTree tree =
        builder.tree != null ? builder.tree : new ThinkingTree(Map.of("state", "init"));
    Expander expander = builder.expander != null ? builder.expander : defaultMacroExpander();
    Simulator simulator =
        builder.simulator != null ? builder.simulator : defaultHeuristicSimulator();

    return MctsEngine.builder(tree)
        .expander(expander)
        .simulator(simulator)
        .iterations(builder.mctsIterations)
        .explorationConstant(builder.explorationConstant)
        .build();
  }

  /** Macro 层默认展开器：生成 4 种战略候选动作。 */
  private Expander defaultMacroExpander() {
    return leaf -> {
      List<ThinkingNode> candidates = new ArrayList<>();
      Map<String, Object> state = leaf.state();

      // LLM_INFERENCE
      String modelId =
          modelSupplier != null && modelSupplier.getReasoningModel() != null
              ? modelSupplier.getReasoningModel().modelId()
              : "gpt-4o-mini";
      candidates.add(
          ThinkingNode.builder()
              .state(state)
              .actionType("LLM_INFERENCE")
              .actionTarget(modelId)
              .actionParams(Map.of("prompt", state.getOrDefault("prompt", "")))
              .thought("Generate reasoning via LLM")
              .build());

      // TOOL_CALL (if availableTools in context)
      if (state.containsKey("availableTools")) {
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) state.get("availableTools");
        for (String tool : tools) {
          candidates.add(
              ThinkingNode.builder()
                  .state(state)
                  .actionType("TOOL_CALL")
                  .actionTarget(tool)
                  .thought("Execute tool: " + tool)
                  .build());
        }
      }

      // OBSERVE
      candidates.add(
          ThinkingNode.builder()
              .state(state)
              .actionType("OBSERVE")
              .actionTarget("ENVIRONMENT")
              .thought("Observe environment change")
              .build());

      // REVISION
      if (state.containsKey("lastFeedback")) {
        candidates.add(
            ThinkingNode.builder()
                .state(state)
                .actionType("REVISION")
                .actionTarget("REVISION")
                .actionParams(Map.of("feedback", state.get("lastFeedback")))
                .thought("Revise based on feedback")
                .build());
      }

      return candidates;
    };
  }

  /** Macro 层默认启发式模拟器（基于 prompt 长度评分，0~100）。 */
  private Simulator defaultHeuristicSimulator() {
    return node -> {
      String prompt = (String) node.state().getOrDefault("prompt", "");
      double score = 50.0;
      if (prompt.length() > 0) {
        score += Math.min(prompt.length() * 10.0, 50.0);
      }
      return score;
    };
  }

  /** 将 MCTS ThinkingNode 的 string actionType 映射为 Macro 层 Intent。 */
  private static Plan.Intent toIntent(String actionType) {
    return switch (actionType) {
      case "LLM_INFERENCE" -> Plan.Intent.ANALYZE;
      case "TOOL_CALL" -> Plan.Intent.SEARCH;
      case "OBSERVE" -> Plan.Intent.ANALYZE;
      case "REVISION" -> Plan.Intent.REVISION;
      default -> Plan.Intent.ANALYZE;
    };
  }

  // ========== Builder ==========

  public static final class Builder {
    private ThinkingTree tree;
    private PlannerModelSupplier modelSupplier;
    private Expander expander;
    private Simulator simulator;
    private int mctsIterations = MctsEngine.DEFAULT_ITERATIONS;
    private double explorationConstant = MctsEngine.DEFAULT_EXPLORATION_CONSTANT;

    private Builder() {}

    public Builder tree(ThinkingTree tree) {
      this.tree = tree;
      return this;
    }

    public Builder modelSupplier(PlannerModelSupplier s) {
      this.modelSupplier = s;
      return this;
    }

    public Builder expander(Expander e) {
      this.expander = e;
      return this;
    }

    public Builder simulator(Simulator s) {
      this.simulator = s;
      return this;
    }

    public Builder mctsIterations(int n) {
      this.mctsIterations = n;
      return this;
    }

    public Builder explorationConstant(double c) {
      this.explorationConstant = c;
      return this;
    }

    public SlowPathStrategy build() {
      return new SlowPathStrategy(this);
    }
  }
}
