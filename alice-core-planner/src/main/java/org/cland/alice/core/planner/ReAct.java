package org.cland.alice.core.planner;

import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.planner.budget.TokenBudget;
import org.cland.alice.core.planner.model.ModelSupplier;
import org.cland.alice.core.planner.sop.SopRegistry;
import org.cland.alice.core.planner.sop.StaticPlanner;
import org.cland.alice.core.planner.strategy.FastPathStrategy;
import org.cland.alice.core.planner.strategy.SlowPathStrategy;
import org.cland.alice.core.planner.strategy.StrategySelector;
import org.cland.alice.core.planner.tree.ThinkingTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReAct (Reasoning + Acting) 规划器 — 兼容门面类。
 *
 * <p>保留原有 {@code proposeNext(Context)} API 以维持 {@code AgentCore} 的向后兼容性。 内部委托给新的 {@link
 * PlannerService} 双路径决策引擎。
 *
 * <p>新代码建议直接使用 {@link PlannerService}。
 */
public class ReAct {

  private static final Logger logger = LoggerFactory.getLogger(ReAct.class);

  private final PlannerService plannerService;

  /** 默认快速路径策略使用的指令模型 ID */
  private final String instructionModelId;

  /** 默认慢速路径策略使用的推理模型 ID */
  private final String reasoningModelId;

  /** 可选 MCTS 迭代次数（仅当使用默认构建时） */
  private final int mctsIterations;

  // ========== 构造 ==========

  public ReAct() {
    this(builder());
  }

  private ReAct(Builder builder) {
    this.instructionModelId = builder.instructionModelId;
    this.reasoningModelId = builder.reasoningModelId;
    this.mctsIterations = builder.mctsIterations;

    // 构建默认模型供应者
    ModelSupplier modelSupplier =
        builder.modelSupplier != null
            ? builder.modelSupplier
            : new DefaultModelSupplier(instructionModelId, reasoningModelId);

    // 构建策略
    FastPathStrategy fastPath = new FastPathStrategy(modelSupplier);

    ThinkingTree tree = builder.tree != null ? builder.tree : new ThinkingTree(Map.of());

    if (builder.tokenBudget != null) {
      tree.setTokenBudget(builder.tokenBudget);
    }

    SlowPathStrategy slowPath =
        SlowPathStrategy.builder()
            .tree(tree)
            .modelSupplier(modelSupplier)
            .mctsIterations(mctsIterations)
            .build();

    // 构建选择器
    StrategySelector.Builder selectorBuilder =
        StrategySelector.builder().fastPath(fastPath).slowPath(slowPath);

    if (builder.complexityFunction != null) {
      selectorBuilder.complexityFunction(builder.complexityFunction);
    }

    StrategySelector selector = selectorBuilder.build();

    // 构建静态规划器（可选）
    StaticPlanner staticPlanner = null;
    if (builder.sopRegistry != null) {
      staticPlanner = new StaticPlanner(builder.sopRegistry);
    }

    // 构建 PlannerService
    this.plannerService =
        PlannerService.builder().strategySelector(selector).staticPlanner(staticPlanner).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  // ========== 原 API (向后兼容) ==========

  /**
   * 基于当前上下文，提议下一步的执行意图（向后兼容）。
   *
   * <p>返回一个描述意图的 Map，包含：
   *
   * <ul>
   *   <li>{@code "type"} — 意图类型: "LLM_INFERENCE" | "TOOL_CALL" | "FINISH" | "REVISION"
   *   <li>{@code "target"} — 目标（模型 ID / 工具名）
   *   <li>{@code "prompt"} — 推理用的 prompt（可选）
   * </ul>
   *
   * @param context 当前 Agent 上下文的只读快照
   * @return 描述意图的 Map
   */
  public Map<String, Object> proposeNext(Map<String, Object> context) {
    Objects.requireNonNull(context, "context must not be null");

    logger.debug("[ReAct] proposeNext called");

    // 使用新引擎规划
    Plan plan = plannerService.plan(context);

    // 将 Plan 转为向后兼容的 Map 格式
    return planToMap(plan, context);
  }

  /** 获取内部的 PlannerService（用于高级集成）。 */
  public PlannerService plannerService() {
    return plannerService;
  }

  // ========== 辅助方法 ==========

  /** 将 Plan 转换为兼容的 Map 意图格式。 如果 Plan 有多个步骤，只返回第一个步骤的意图。 */
  private Map<String, Object> planToMap(Plan plan, Map<String, Object> context) {
    // 检查是否有完成结果
    if (context.containsKey("result") && context.get("result") != null) {
      String result = context.get("result").toString();
      if (!result.isEmpty()) {
        return Map.of("type", "FINISH", "target", "FINISH");
      }
    }

    // 获取 Plan 的第一步
    if (plan.steps().isEmpty()) {
      // 兜底：返回 LLM_INFERENCE
      return Map.of(
          "type",
          "LLM_INFERENCE",
          "target",
          instructionModelId,
          "prompt",
          context.getOrDefault("prompt", "Hello!"));
    }

    Plan.Step firstStep = plan.steps().get(0);
    String actionType = firstStep.actionType();

    return switch (actionType) {
      case "FINISH" -> Map.of("type", "FINISH", "target", "FINISH");
      case "TOOL_CALL" -> {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("type", "TOOL_CALL");
        result.put("target", firstStep.target());
        if (!firstStep.parameters().isEmpty()) {
          result.put("parameters", firstStep.parameters());
        }
        if (firstStep.thought() != null) {
          result.put("thought", firstStep.thought());
        }
        yield Map.copyOf(result);
      }
      case "REVISION" -> {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("type", "REVISION");
        result.put("target", "REVISION");
        result.put(
            "feedback", firstStep.parameters().getOrDefault("feedback", "Revision requested"));
        yield Map.copyOf(result);
      }
      default -> { // LLM_INFERENCE 及其他
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("type", "LLM_INFERENCE");
        result.put("target", firstStep.target() != null ? firstStep.target() : instructionModelId);
        result.put(
            "prompt",
            firstStep
                .parameters()
                .getOrDefault("prompt", context.getOrDefault("prompt", "Hello!")));
        if (firstStep.thought() != null) {
          result.put("thought", firstStep.thought());
        }
        yield Map.copyOf(result);
      }
    };
  }

  // ========== 默认模型供应者 ==========

  /** 默认的模型供应者实现，使用硬编码的 modelId。 生产环境应替换为通过 alice-model 的 {@code ModelProvider} 获取。 */
  private static final class DefaultModelSupplier implements ModelSupplier {

    private final String instructionModelId;
    private final String reasoningModelId;

    DefaultModelSupplier(String instructionModelId, String reasoningModelId) {
      this.instructionModelId = instructionModelId;
      this.reasoningModelId = reasoningModelId;
    }

    @Override
    public org.cland.alice.core.planner.model.ModelSession getReasoningModel() {
      return org.cland.alice.core.planner.model.ModelSession.of(
          reasoningModelId, "Reasoning model session");
    }

    @Override
    public org.cland.alice.core.planner.model.ModelSession getInstructionModel() {
      return org.cland.alice.core.planner.model.ModelSession.of(
          instructionModelId, "Instruction model session");
    }
  }

  // ========== Builder ==========

  public static final class Builder {
    private String instructionModelId = "gpt-4o-mini";
    private String reasoningModelId = "gpt-4o";
    private int mctsIterations = 20;
    private ModelSupplier modelSupplier;
    private ThinkingTree tree;
    private TokenBudget tokenBudget;
    private SopRegistry sopRegistry;
    private java.util.function.Function<Map<String, Object>, Boolean> complexityFunction;

    private Builder() {}

    public Builder instructionModelId(String id) {
      this.instructionModelId = id;
      return this;
    }

    public Builder reasoningModelId(String id) {
      this.reasoningModelId = id;
      return this;
    }

    public Builder mctsIterations(int iterations) {
      this.mctsIterations = iterations;
      return this;
    }

    public Builder modelSupplier(ModelSupplier supplier) {
      this.modelSupplier = supplier;
      return this;
    }

    public Builder tree(ThinkingTree tree) {
      this.tree = tree;
      return this;
    }

    public Builder tokenBudget(TokenBudget budget) {
      this.tokenBudget = budget;
      return this;
    }

    public Builder sopRegistry(SopRegistry sopRegistry) {
      this.sopRegistry = sopRegistry;
      return this;
    }

    public Builder complexityFunction(
        java.util.function.Function<Map<String, Object>, Boolean> fn) {
      this.complexityFunction = fn;
      return this;
    }

    public ReAct build() {
      return new ReAct(this);
    }
  }
}
