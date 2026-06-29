package org.cland.alice.guardrail.validators;

import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.guardrail.AuditResult;
import org.cland.alice.guardrail.CorrectionSuggestion;
import org.cland.alice.guardrail.PostValidator;
import org.cland.alice.tool.gateway.ToolRegistry;
import org.cland.alice.tool.gateway.metadata.ToolMetadata;

/**
 * 工具结果验证器 (Post-Validator)。
 *
 * <p>在 TOOL_CALL 执行完成后，根据已注册工具的 {@link ToolMetadata} 验证返回结果的合理性。 弥补 {@link HallucinationDetector}
 * 仅做通用关键字匹配、不感知工具具体元数据的不足。
 *
 * <p><b>检查规则：</b>
 *
 * <ul>
 *   <li><b>工具存在性回溯：</b>确认 Plan 中最后执行的 TOOL_CALL 对应的工具确实在注册表中
 *   <li><b>风险等级标记：</b>如果执行了高风险工具但观测结果异常，提升告警级别
 *   <li><b>返回类型一致性：</b>检查 rawData 的类型与工具声明的 returnType 是否大致匹配
 * </ul>
 *
 * <p><b>使用方式：</b>
 *
 * <pre>{@code
 * ToolRegistry registry = ...;
 * GuardrailService guardrail = new GuardrailService();
 * guardrail.registerPostValidator(new ToolResultValidator(registry));
 * }</pre>
 */
public final class ToolResultValidator implements PostValidator {

  private final ToolRegistry toolRegistry;

  /**
   * @param toolRegistry 已填充工具注册信息的 {@link ToolRegistry} 实例，不能为 null
   */
  public ToolResultValidator(ToolRegistry toolRegistry) {
    this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
  }

  @Override
  public AuditResult check(Map<String, Object> observationMap, Plan originalPlan) {
    if (originalPlan.steps() == null || originalPlan.steps().isEmpty()) {
      return AuditResult.allow();
    }

    // 找到 Plan 中最后一个 TOOL_CALL 步骤
    Plan.Step lastToolStep = null;
    for (int i = originalPlan.steps().size() - 1; i >= 0; i--) {
      if ("TOOL_CALL".equals(originalPlan.steps().get(i).actionType())) {
        lastToolStep = originalPlan.steps().get(i);
        break;
      }
    }

    if (lastToolStep == null) {
      // 没有 TOOL_CALL 步骤，跳过工具相关的后验证
      return AuditResult.allow();
    }

    String toolName = lastToolStep.target();
    if (toolName == null || toolName.isBlank()) {
      return AuditResult.allow();
    }

    // 检查工具是否在注册表中
    if (!toolRegistry.hasTool(toolName)) {
      return AuditResult.invalid(
          "Post-execution: tool '" + toolName + "' is not registered in ToolRegistry",
          CorrectionSuggestion.replan(
              "Tool '" + toolName + "' is unavailable. Use a registered tool."));
    }

    ToolMetadata metadata = toolRegistry.lookup(toolName);

    // 1. 风险等级标记 — 高风险工具的结果需要格外关注
    org.cland.alice.tool.gateway.annotation.RiskLevel toolRisk = metadata.riskLevel();
    ObsStatus obsStatus = extractObsStatus(observationMap);

    if (toolRisk == org.cland.alice.tool.gateway.annotation.RiskLevel.HIGH) {

      if (obsStatus == ObsStatus.FAILURE || obsStatus == ObsStatus.TIMEOUT) {
        return AuditResult.invalid(
            "High-risk tool '"
                + toolName
                + "' (risk="
                + toolRisk
                + ") returned "
                + obsStatus
                + ". Manual review recommended.",
            CorrectionSuggestion.manualConfirm(
                "High-risk tool execution failed. Review before retrying."));
      }

      // 即使成功，高风险工具也标记为需要关注
      if (obsStatus == ObsStatus.SUCCESS) {
        return AuditResult.allowWithWarning(
            "High-risk tool '" + toolName + "' executed successfully. Risk level: " + toolRisk);
      }
    }

    // 2. 返回类型一致性检查
    Class<?> returnType = metadata.returnType();
    if (returnType != null && returnType != void.class) {
      Object rawData = observationMap.get("rawData");

      if (rawData == null && obsStatus == ObsStatus.SUCCESS) {
        // 工具声明了返回值但实际 rawData 为 null — 可能异常
        return AuditResult.allowWithWarning(
            "Tool '"
                + toolName
                + "' declares return type "
                + returnType.getSimpleName()
                + " but observation rawData is null");
      }

      if (rawData != null) {
        // 粗略检查返回类型是否匹配（实际类型 vs 声明类型）
        Class<?> actualType = rawData.getClass();
        if (!returnType.isAssignableFrom(actualType) && !isBoxedMatch(returnType, actualType)) {
          return AuditResult.allowWithWarning(
              "Tool '"
                  + toolName
                  + "' declares return type "
                  + returnType.getSimpleName()
                  + " but actual rawData is "
                  + actualType.getSimpleName());
        }
      }
    }

    return AuditResult.allow();
  }

  // ========================================================================
  // 内部方法
  // ========================================================================

  /** 从观测 Map 中提取状态枚举。 */
  private ObsStatus extractObsStatus(Map<String, Object> map) {
    if (map == null) return ObsStatus.FAILURE;
    Object statusObj = map.get("status");
    if (statusObj instanceof ObsStatus obs) {
      return obs;
    }
    if (statusObj instanceof String s) {
      try {
        return ObsStatus.valueOf(s.toUpperCase());
      } catch (IllegalArgumentException e) {
        return ObsStatus.FAILURE;
      }
    }
    return ObsStatus.SUCCESS;
  }

  /** 检查原始类型与装箱类型的匹配。 */
  private static boolean isBoxedMatch(Class<?> primitive, Class<?> boxed) {
    if (!primitive.isPrimitive()) return false;
    if (primitive == int.class) return boxed == Integer.class;
    if (primitive == long.class) return boxed == Long.class;
    if (primitive == double.class) return boxed == Double.class;
    if (primitive == float.class) return boxed == Float.class;
    if (primitive == boolean.class) return boxed == Boolean.class;
    if (primitive == short.class) return boxed == Short.class;
    if (primitive == byte.class) return boxed == Byte.class;
    if (primitive == char.class) return boxed == Character.class;
    return false;
  }
}
