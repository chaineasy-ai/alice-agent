package org.cland.alice.guardrail.validators;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.guardrail.AuditResult;
import org.cland.alice.guardrail.CorrectionSuggestion;
import org.cland.alice.guardrail.PreValidator;

/**
 * 必需工具调用验证器 (Pre-Validator) —— 强制「需工具的任务必须出现对应工具调用」。
 *
 * <p><b>背景（承 {@code base/cland-crawler#528} / {@code #530}）：</b>对照实验两批稳定复现
 * ——多角色/多体提示会让模型<b>省掉工具、直接心算</b>，把「省调用」变成错误（工具调用缺失本身不可见）。 本验证器把「哪些工具是必需」变成可判约束：一旦任务声明了必需工具，Plan
 * 里缺少对应 {@code TOOL_CALL} 即拒绝。
 *
 * <p><b>机制与产品口径的分工：</b>本类只实现<b>强制机制</b>——从 {@link Plan#metadata()} 的 {@value #REQUIRED_TOOLS_KEY}
 * 键读取必需工具清单并校验。「何时/由谁判定某任务需要哪些工具」（任务意图元数据） 属产品侧能力边界定义（{@code base/cland-crawler#516}
 * / @compass）。未声明 requiredTools 时本验证器为 no-op， 不引入任何行为变化。
 *
 * <p><b>元数据取值格式：</b>
 *
 * <ul>
 *   <li>{@code Collection<?>}（如 {@code List.of("calculator")}）
 *   <li>逗号/分号/空白分隔的 {@code String}（如 {@code "calculator, text_stats"}）
 * </ul>
 *
 * <p><b>使用方式：</b>
 *
 * <pre>{@code
 * GuardrailService guardrail = new GuardrailService();
 * guardrail.registerPreValidator(new RequiredToolCallValidator());
 * }</pre>
 */
public final class RequiredToolCallValidator implements PreValidator {

  /** Plan 元数据中声明「必需工具」的键。 */
  public static final String REQUIRED_TOOLS_KEY = "requiredTools";

  @Override
  public AuditResult check(Plan plan) {
    if (plan == null || plan.steps() == null) {
      return AuditResult.allow();
    }
    Set<String> requiredTools = normalizeRequiredTools(plan.metadata().get(REQUIRED_TOOLS_KEY));
    if (requiredTools.isEmpty()) {
      return AuditResult.allow();
    }

    Set<String> invokedTools = new LinkedHashSet<>();
    for (Plan.Step step : plan.steps()) {
      if ("TOOL_CALL".equals(step.intent().toActionString()) && step.target() != null) {
        invokedTools.add(step.target());
      }
    }

    List<String> missing =
        requiredTools.stream().filter(tool -> !invokedTools.contains(tool)).sorted().toList();
    if (!missing.isEmpty()) {
      return AuditResult.reject(
          "Required tool(s) missing: "
              + missing
              + " (plan invoked "
              + (invokedTools.isEmpty() ? "(no tool call)" : invokedTools)
              + "). A tools-required task must actually call its tools.",
          CorrectionSuggestion.replan(
              "Add TOOL_CALL step(s) for required tool(s) " + missing + " before finishing."));
    }
    return AuditResult.allow();
  }

  /** 将元数据值归一为工具名集合（容错：支持 Collection / 分隔字符串 / 单值）。 */
  private static Set<String> normalizeRequiredTools(Object raw) {
    if (raw == null) {
      return Set.of();
    }
    if (raw instanceof Collection<?> collection) {
      return collection.stream()
          .filter(Objects::nonNull)
          .map(Object::toString)
          .map(String::trim)
          .filter(name -> !name.isEmpty())
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }
    if (raw instanceof String text) {
      return splitTokens(text);
    }
    return splitTokens(raw.toString());
  }

  private static Set<String> splitTokens(String text) {
    Set<String> tokens = new LinkedHashSet<>();
    for (String token : text.split("[,;\\s]+")) {
      String trimmed = token.trim();
      if (!trimmed.isEmpty()) {
        tokens.add(trimmed);
      }
    }
    return tokens;
  }

  /** 供测试/调用方使用的辅助：构造必需工具元数据（避免拼写漂移）。 */
  public static java.util.Map<String, Object> metadata(Collection<String> requiredTools) {
    return java.util.Map.of(REQUIRED_TOOLS_KEY, new ArrayList<>(requiredTools));
  }
}
