package org.cland.alice.core.planner.strategy;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.cland.alice.core.planner.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 策略选择器，对应设计文档中的 {@code StrategySelector}。
 *
 * <p>负责在接收输入后进行 <b>复杂度评估 (Complexity Assessment)</b>，决定走：
 *
 * <ul>
 *   <li><b>Fast Path (System 1)</b> — 低复杂度任务，直接 LLM 或模板
 *   <li><b>Slow Path (System 2)</b> — 高复杂度任务，MCTS 树搜索
 * </ul>
 *
 * <p>默认使用 prompt 长度 + 关键词启发式判断复杂度。 可注入自定义 {@code complexityFunction} 实现更精确的判定（如调用 Router 模型）。
 */
public final class StrategySelector {

  private static final Logger logger = LoggerFactory.getLogger(StrategySelector.class);

  /** 默认复杂判定阈值 — prompt 超过此长度视为复杂 */
  private static final int DEFAULT_COMPLEX_THRESHOLD = 200;

  /** 复杂关键词列表 */
  private static final String[] COMPLEX_KEYWORDS = {
    "analyze",
    "compare",
    "contrast",
    "evaluate",
    "synthesize",
    "plan",
    "strategy",
    "multi-step",
    "complex",
    "detailed",
    "分析",
    "比较",
    "评估",
    "计划",
    "策略",
    "综合"
  };

  private final DecisionStrategy fastPath;
  private final DecisionStrategy slowPath;
  private final Function<Map<String, Object>, Boolean> complexityFunction;

  private StrategySelector(Builder builder) {
    this.fastPath = Objects.requireNonNull(builder.fastPath, "fastPath must not be null");
    this.slowPath = Objects.requireNonNull(builder.slowPath, "slowPath must not be null");
    this.complexityFunction =
        builder.complexityFunction != null
            ? builder.complexityFunction
            : this::defaultComplexityCheck;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * 评估上下文复杂度，选择合适的策略执行规划。
   *
   * @param context 规划器上下文的只读快照
   * @return 规划结果 Plan
   */
  public Plan select(Map<String, Object> context) {
    boolean isComplex = complexityFunction.apply(context);

    if (isComplex) {
      logger.info("[StrategySelector] Routing to SLOW path");
      Plan plan = slowPath.decide(context);
      logger.info("[StrategySelector] Slow path completed: {}", plan);
      return plan;
    } else {
      logger.info("[StrategySelector] Routing to FAST path");
      Plan plan = fastPath.decide(context);
      logger.info("[StrategySelector] Fast path completed: {}", plan);
      return plan;
    }
  }

  /** 获取 FastPathStrategy 引用（用于测试 / 注入）。 */
  public DecisionStrategy fastPath() {
    return fastPath;
  }

  /** 获取 SlowPathStrategy 引用（用于测试 / 注入）。 */
  public DecisionStrategy slowPath() {
    return slowPath;
  }

  // ========== 默认复杂度检测 ==========

  /**
   * 默认复杂度判定逻辑：基于 prompt 长度 + 关键词。
   *
   * <p>对应设计文档中轻量化模型（如 Qwen-1.8B）做复杂度判定的替代实现。
   *
   * @param context 上下文快照
   * @return true 表示复杂任务，走 Slow Path
   */
  private boolean defaultComplexityCheck(Map<String, Object> context) {
    String prompt = (String) context.getOrDefault("prompt", "");

    // 1. 长度检查
    if (prompt.length() > DEFAULT_COMPLEX_THRESHOLD) {
      logger.debug(
          "[Complexity] Prompt length {} > threshold {}, routing to SLOW",
          prompt.length(),
          DEFAULT_COMPLEX_THRESHOLD);
      return true;
    }

    // 2. 关键词检查
    String lowerPrompt = prompt.toLowerCase();
    for (String keyword : COMPLEX_KEYWORDS) {
      if (lowerPrompt.contains(keyword.toLowerCase())) {
        logger.debug("[Complexity] Found keyword '{}', routing to SLOW", keyword);
        return true;
      }
    }

    // 3. 有反馈信息 -> 复杂
    if (context.containsKey("lastFeedback") && context.get("lastFeedback") != null) {
      logger.debug("[Complexity] Has feedback, routing to SLOW");
      return true;
    }

    // 4. 有错误 -> 复杂
    if (context.containsKey("error") && context.get("error") != null) {
      logger.debug("[Complexity] Has error, routing to SLOW");
      return true;
    }

    // 默认走 Fast Path
    logger.debug("[Complexity] Simple task, routing to FAST");
    return false;
  }

  // ========== Builder ==========

  public static final class Builder {
    private DecisionStrategy fastPath;
    private DecisionStrategy slowPath;
    private Function<Map<String, Object>, Boolean> complexityFunction;

    private Builder() {}

    public Builder fastPath(DecisionStrategy fastPath) {
      this.fastPath = fastPath;
      return this;
    }

    public Builder slowPath(DecisionStrategy slowPath) {
      this.slowPath = slowPath;
      return this;
    }

    /** 注入自定义复杂度判定函数。 接收上下文快照，返回 true 表示复杂（走 Slow Path）。 */
    public Builder complexityFunction(Function<Map<String, Object>, Boolean> fn) {
      this.complexityFunction = fn;
      return this;
    }

    public StrategySelector build() {
      return new StrategySelector(this);
    }
  }
}
