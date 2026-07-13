package org.cland.alice.core.planner;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.cland.alice.core.planner.strategy.StrategySelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 规划器服务 — 双路径决策引擎的入口。
 *
 * <p>对应设计文档中的 {@code PlannerService}，作为"状态化的推理机"的核心入口。 完整流程：
 *
 * <pre>
 *   1. 接收 AgentContext
 *   2. 尝试 Static Planning (SOP 模板匹配，注入的函数)
 *   3. 若未命中，通过 StrategySelector 做复杂度评估
 *   4. 选择 Fast Path 或 Slow Path
 *   5. 返回 Plan
 * </pre>
 *
 * <p>SOP 模板匹配逻辑由 {@code staticPlannerFn} 提供（通常来自 {@code alice-memory-vault} 的 {@code
 * StaticPlanner}）。 使用示例：
 *
 * <pre>
 *   PlannerService planner = PlannerService.builder()
 *       .strategySelector(selector)
 *       .staticPlannerFn(ctx -> staticPlanner.plan(ctx))
 *       .build();
 *   Plan plan = planner.plan(contextMap);
 * </pre>
 */
public final class PlannerService {

  private static final Logger logger = LoggerFactory.getLogger(PlannerService.class);

  private final StrategySelector strategySelector;
  private final Function<Map<String, Object>, Plan> staticPlannerFn;

  private PlannerService(Builder builder) {
    this.strategySelector =
        Objects.requireNonNull(builder.strategySelector, "strategySelector must not be null");
    this.staticPlannerFn = builder.staticPlannerFn; // 可选
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * 规划主入口。
   *
   * <p>接收 AgentContext 的 Map 快照，执行双路径决策逻辑：
   *
   * <ol>
   *   <li>尝试静态规划（SOP 模板，通过注入的函数）
   *   <li>若未命中，通过 StrategySelector 执行复杂度评估并路由
   * </ol>
   *
   * @param context AgentContext 的只读快照（通过 {@code asMap()} 获取）
   * @return 行动方案 Plan
   */
  public Plan plan(Map<String, Object> context) {
    Objects.requireNonNull(context, "context must not be null");

    logger.info(
        "[PlannerService] Planning with context keys={}", String.join(", ", context.keySet()));

    // 1. 如果有最终结果，直接返回 FINISH
    if (context.containsKey("result") && context.get("result") != null) {
      String result = context.get("result").toString();
      if (!result.isEmpty()) {
        logger.info("[PlannerService] Result already present, finishing");
        return Plan.builder()
            .type(Plan.Type.FAST_PATH)
            .summary("Task completed")
            .addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH"))
            .build();
      }
    }

    // 2. 尝试静态规划（SOP 模板匹配，注入的函数）
    if (staticPlannerFn != null) {
      Plan staticPlan = staticPlannerFn.apply(context);
      if (staticPlan != null) {
        logger.info("[PlannerService] Static plan selected");
        return staticPlan;
      }
    }

    // 3. 双路径决策：StrategySelector 评估复杂度并路由
    Plan plan = strategySelector.select(context);
    logger.info(
        "[PlannerService] Plan completed: type={}, steps={}", plan.type(), plan.steps().size());

    return plan;
  }

  /**
   * 简化版本：接收 prompt 直接规划。
   *
   * @param prompt 用户输入的提示词
   * @param modelId 目标模型 ID（可选）
   * @return 行动方案 Plan
   */
  public Plan plan(String prompt, String modelId) {
    return plan(
        Map.of(
            "prompt", prompt != null ? prompt : "",
            "model", modelId != null ? modelId : "gpt-4o-mini"));
  }

  /** 简化版本：仅接收 prompt。 */
  public Plan plan(String prompt) {
    return plan(prompt, null);
  }

  /** 获取 {@link StrategySelector}（用于重建 PlannerService 时复用）。 */
  public StrategySelector strategySelector() {
    return strategySelector;
  }

  // ========== Builder ==========

  public static final class Builder {
    private StrategySelector strategySelector;
    private Function<Map<String, Object>, Plan> staticPlannerFn;

    private Builder() {}

    public Builder strategySelector(StrategySelector strategySelector) {
      this.strategySelector = strategySelector;
      return this;
    }

    public Builder staticPlannerFn(Function<Map<String, Object>, Plan> staticPlannerFn) {
      this.staticPlannerFn = staticPlannerFn;
      return this;
    }

    public PlannerService build() {
      return new PlannerService(this);
    }
  }
}
