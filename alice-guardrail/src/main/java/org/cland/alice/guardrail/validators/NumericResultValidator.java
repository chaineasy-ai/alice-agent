package org.cland.alice.guardrail.validators;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.guardrail.AuditResult;
import org.cland.alice.guardrail.CorrectionSuggestion;
import org.cland.alice.guardrail.PostValidator;

/**
 * 数值结果可信性验证器 (Post-Validator) —— 覆盖「工具成功返回、数值却悄悄错」的静默精度缺陷。
 *
 * <p><b>背景（承 {@code base/cland-crawler#528} / {@code #530}）：</b>curie 的对照实验暴露 lab {@code
 * calculator} 用浮点求值，&gt;2^53 的整数乘法<b>静默返回错误值</b>（无异常）。既有三个校验器 （{@link ToolExistenceValidator} /
 * {@link ToolResultValidator} / {@link HallucinationDetector}） 只判「调用是否发生 / 是否报错 /
 * 类型对不对」，<b>均不判断结果数值本身是否可信</b>。本验证器补齐这一面。
 *
 * <p><b>检查规则（按强度排序）：</b>
 *
 * <ol>
 *   <li><b>独立交叉复算（决定性）：</b>若被调工具的执行参数含算术表达式（键 {@code expression} / {@code expr} / {@code
 *       formula}），或为该工具注册了显式 {@link NumericRecomputer}，则用 {@link ExpressionEvaluator}
 *       精确复算并与观测数值比较；不一致 ⇒ {@code INVALID}（判为静默数值错误）。此判据不依赖工具实现，是「第二把尺子」。
 *   <li><b>整数 2^53 边界（软告警）：</b>无表达式可复算时，若返回整数绝对值超过 {@code 2^53}（IEEE-754
 *       双精度可精确表示整数的上限），提示可能携带浮点精度损失 —— 仅在 {@link AuditResult#allowWithWarning} 级别告警，不误伤雪花 ID /
 *       哈希等合法大整数。
 *   <li><b>量级上限（软告警）：</b>结果绝对值超过配置量级上限（默认 {@code 2^63}）时提示可能溢出。
 * </ol>
 *
 * <p><b>非目标：</b>不做「需工具任务是否真的调了工具」的意图判定 —— 那属于 PreValidator 与任务意图元数据 （产品侧 {@code #516}），见与 {@link
 * RequiredToolCallValidator} 的分工。
 *
 * <p><b>使用方式：</b>
 *
 * <pre>{@code
 * GuardrailService guardrail = new GuardrailService();
 * guardrail.registerPostValidator(new NumericResultValidator());
 * }</pre>
 */
public final class NumericResultValidator implements PostValidator {

  /** 默认量级上限：2^63（超过 long 可表示范围即视为可疑）。 */
  public static final String DEFAULT_MAX_MAGNITUDE = "9223372036854775808";

  /** IEEE-754 双精度可精确表示整数的上限：2^53。 */
  static final BigDecimal DOUBLE_INTEGER_LIMIT = new BigDecimal("9007199254740992");

  /** 相对误差容差（仅用于带小数部分的结果，如除法；整数走精确比较）。 */
  private static final BigDecimal RELATIVE_TOLERANCE = new BigDecimal("1e-9");

  /** 视为「算术表达式」的参数键。 */
  private static final Set<String> EXPRESSION_KEYS = Set.of("expression", "expr", "formula");

  /** 观测 Map 中可能承载数值结果的字段键（仅在 rawData 为 Map 时使用）。 */
  private static final Set<String> RESULT_KEYS =
      Set.of("result", "value", "answer", "computed", "output", "numeric");

  /**
   * 关键数值工具的独立复算器 —— 工具名到「参数 → 精确期望值」的映射。
   *
   * <p>表达式类工具由默认规则覆盖；对于输入不是表达式字符串的核心数值工具，可通过此接口注入专属复算逻辑。
   */
  @FunctionalInterface
  public interface NumericRecomputer {
    /**
     * @param parameters 该步骤的执行参数（可能为空 Map）
     * @return 精确期望值；无法复算时返回 {@code null}
     */
    BigDecimal recompute(Map<String, Object> parameters);
  }

  private final BigDecimal maxMagnitude;
  private final Map<String, NumericRecomputer> recomputers;

  /** 使用默认量级上限（2^63）构造。 */
  public NumericResultValidator() {
    this(new BigDecimal(DEFAULT_MAX_MAGNITUDE), Map.of());
  }

  /**
   * @param maxMagnitude 结果量级上限，不能为 null
   */
  public NumericResultValidator(BigDecimal maxMagnitude) {
    this(maxMagnitude, Map.of());
  }

  /**
   * @param maxMagnitude 结果量级上限，不能为 null
   * @param recomputers 关键数值工具的独立复算器（工具名 → 复算器），不能为 null
   */
  public NumericResultValidator(
      BigDecimal maxMagnitude, Map<String, NumericRecomputer> recomputers) {
    this.maxMagnitude = Objects.requireNonNull(maxMagnitude, "maxMagnitude must not be null");
    this.recomputers =
        Map.copyOf(Objects.requireNonNull(recomputers, "recomputers must not be null"));
  }

  @Override
  public AuditResult check(Map<String, Object> observationMap, Plan originalPlan) {
    if (observationMap == null || originalPlan == null) {
      return AuditResult.allow();
    }
    // 只审计「成功」的观测；失败/超时由 GuardrailService 与其它验证器处理。
    if (extractObsStatus(observationMap) != ObsStatus.SUCCESS) {
      return AuditResult.allow();
    }

    Plan.Step toolStep = lastToolStep(originalPlan);
    if (toolStep == null) {
      return AuditResult.allow();
    }

    List<BigDecimal> results = extractNumbers(observationMap.get("rawData"));
    if (results.isEmpty()) {
      return AuditResult.allow();
    }

    String toolName = toolStep.target();
    BigDecimal expected = crossValidate(toolName, toolStep.parameters());
    if (expected != null) {
      for (BigDecimal actual : results) {
        if (!numericallyEqual(expected, actual)) {
          return AuditResult.invalid(
              "Numeric result mismatch from tool '"
                  + toolName
                  + "': independent recomputation = "
                  + expected.toPlainString()
                  + " but observation returned "
                  + actual.toPlainString()
                  + ". Silent numeric error (possible float precision loss).",
              CorrectionSuggestion.changeTarget(
                  "Recompute '"
                      + toolName
                      + "' with exact arithmetic or fix the tool; do not trust the returned value."));
        }
      }
      // 独立复算一致 ⇒ 该数值可信，直接放行。
      return AuditResult.allow();
    }

    // 无可复算的表达式/复算器 ⇒ 软告警（不误伤合法大整数）。
    String warning = sanityWarning(results);
    return warning == null ? AuditResult.allow() : AuditResult.allowWithWarning(warning);
  }

  // ========================================================================
  // 交叉复算
  // ========================================================================

  /** 尝试独立复算期望值；无法复算返回 {@code null}。 */
  private BigDecimal crossValidate(String toolName, Map<String, Object> parameters) {
    Map<String, Object> params = parameters != null ? parameters : Map.of();
    if (toolName != null) {
      NumericRecomputer recomputer = recomputers.get(toolName);
      if (recomputer != null) {
        try {
          return recomputer.recompute(params);
        } catch (RuntimeException e) {
          return null;
        }
      }
    }
    for (String key : EXPRESSION_KEYS) {
      Object value = params.get(key);
      if (value instanceof String expression && !expression.isBlank()) {
        try {
          return ExpressionEvaluator.evaluate(expression);
        } catch (RuntimeException e) {
          // 表达式不受支持 ⇒ 视为「无法交叉校验」，不判错。
          return null;
        }
      }
    }
    return null;
  }

  // ========================================================================
  // 软告警
  // ========================================================================

  private String sanityWarning(List<BigDecimal> results) {
    for (BigDecimal value : results) {
      if (value.abs().compareTo(maxMagnitude) > 0) {
        return "Numeric result magnitude "
            + value.toPlainString()
            + " exceeds configured limit "
            + maxMagnitude.toPlainString()
            + "; verify the tool did not overflow.";
      }
      if (isInteger(value) && value.abs().compareTo(DOUBLE_INTEGER_LIMIT) > 0) {
        return "Integer result "
            + value.toPlainString()
            + " exceeds 2^53 ("
            + DOUBLE_INTEGER_LIMIT.toPlainString()
            + "); may carry silent float precision loss. Provide an 'expression' parameter for"
            + " exact cross-validation.";
      }
    }
    return null;
  }

  // ========================================================================
  // 数值提取与比较
  // ========================================================================

  private List<BigDecimal> extractNumbers(Object rawData) {
    if (rawData == null) {
      return List.of();
    }
    if (rawData instanceof BigDecimal value) {
      return List.of(value);
    }
    if (rawData instanceof BigInteger value) {
      return List.of(new BigDecimal(value));
    }
    if (rawData instanceof Number value) {
      return List.of(new BigDecimal(value.toString()));
    }
    if (rawData instanceof String text) {
      BigDecimal value = tryParse(text.trim());
      return value == null ? List.of() : List.of(value);
    }
    if (rawData instanceof Map<?, ?> map) {
      List<BigDecimal> collected = new ArrayList<>();
      for (String key : RESULT_KEYS) {
        if (map.containsKey(key)) {
          collected.addAll(extractNumbers(map.get(key)));
        }
      }
      return collected;
    }
    if (rawData instanceof Iterable<?> iterable) {
      List<BigDecimal> collected = new ArrayList<>();
      for (Object item : iterable) {
        collected.addAll(extractNumbers(item));
      }
      return collected;
    }
    return List.of();
  }

  private static BigDecimal tryParse(String text) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean isInteger(BigDecimal value) {
    return value.stripTrailingZeros().scale() <= 0;
  }

  /** 数值等价判定：整数走精确比较，带小数部分才用相对容差。 */
  private static boolean numericallyEqual(BigDecimal expected, BigDecimal actual) {
    if (expected.compareTo(actual) == 0) {
      return true;
    }
    if (isInteger(expected) && isInteger(actual)) {
      return false;
    }
    BigDecimal diff = expected.subtract(actual).abs();
    BigDecimal tolerance =
        expected.abs().max(BigDecimal.ONE).multiply(RELATIVE_TOLERANCE).max(RELATIVE_TOLERANCE);
    return diff.compareTo(tolerance) <= 0;
  }

  // ========================================================================
  // Plan / 观测辅助
  // ========================================================================

  private static Plan.Step lastToolStep(Plan plan) {
    if (plan.steps() == null || plan.steps().isEmpty()) {
      return null;
    }
    for (int i = plan.steps().size() - 1; i >= 0; i--) {
      Plan.Step step = plan.steps().get(i);
      if ("TOOL_CALL".equals(step.intent().toActionString())) {
        return step;
      }
    }
    return null;
  }

  private static ObsStatus extractObsStatus(Map<String, Object> map) {
    Object statusObj = map.get("status");
    if (statusObj instanceof ObsStatus status) {
      return status;
    }
    if (statusObj instanceof String text) {
      try {
        return ObsStatus.valueOf(text.toUpperCase());
      } catch (IllegalArgumentException e) {
        return ObsStatus.FAILURE;
      }
    }
    return ObsStatus.SUCCESS;
  }
}
