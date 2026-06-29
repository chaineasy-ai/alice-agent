package org.cland.alice.guardrail.validators;

import java.util.Objects;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.guardrail.AuditResult;
import org.cland.alice.guardrail.CorrectionSuggestion;
import org.cland.alice.guardrail.PreValidator;
import org.cland.alice.tool.gateway.ToolRegistry;

/**
 * 工具存在性验证器 (Pre-Validator)。
 *
 * <p>验证 Planner 输出的 Plan 中引用的每一步工具目标是否已在 {@link ToolRegistry} 中注册。 防止 LLM 幻觉生成不存在的工具名，或 Planner
 * 规划了当前环境未挂载的工具。
 *
 * <p><b>检查规则：</b>
 *
 * <ul>
 *   <li><b>TOOL_CALL</b> 步骤的 target 必须对应一个已注册的工具
 *   <li>非 TOOL_CALL 步骤（LLM_INFERENCE, FINISH, REVISION, OBSERVE）不做工具查找
 *   <li>如果 {@link ToolRegistry} 为空（没有任何工具注册），所有 TOOL_CALL 都会被拒绝
 * </ul>
 *
 * <p><b>使用方式：</b>
 *
 * <pre>{@code
 * ToolRegistry registry = ...;
 * GuardrailService guardrail = new GuardrailService();
 * guardrail.registerPreValidator(new ToolExistenceValidator(registry));
 * }</pre>
 */
public final class ToolExistenceValidator implements PreValidator {

  private final ToolRegistry toolRegistry;

  /**
   * @param toolRegistry 已填充工具注册信息的 {@link ToolRegistry} 实例，不能为 null
   */
  public ToolExistenceValidator(ToolRegistry toolRegistry) {
    this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
  }

  @Override
  public AuditResult check(Plan plan) {
    if (plan.steps() == null || plan.steps().isEmpty()) {
      return AuditResult.allow();
    }

    for (int i = 0; i < plan.steps().size(); i++) {
      Plan.Step step = plan.steps().get(i);

      // 只检查 TOOL_CALL 类型的步骤
      if (!"TOOL_CALL".equals(step.actionType())) {
        continue;
      }

      String toolName = step.target();
      if (toolName == null || toolName.isBlank()) {
        return AuditResult.reject(
            "Step " + (i + 1) + " is TOOL_CALL but target (tool name) is null or empty",
            CorrectionSuggestion.replan("Provide a valid tool name for TOOL_CALL step " + (i + 1)));
      }

      if (!toolRegistry.hasTool(toolName)) {
        // 收集已注册的工具名称用于提示
        String availableTools = String.join(", ", toolRegistry.toolNames());
        String suggestion;
        if (availableTools.isEmpty()) {
          suggestion =
              "No tools are currently registered. The plan cannot execute any TOOL_CALL steps.";
        } else {
          suggestion =
              "Use one of the registered tools: ["
                  + availableTools
                  + "], or register '"
                  + toolName
                  + "' first.";
        }

        return AuditResult.reject(
            "Step "
                + (i + 1)
                + " references unknown tool '"
                + toolName
                + "'. Available tools: "
                + (availableTools.isEmpty() ? "(none)" : availableTools),
            CorrectionSuggestion.replan(suggestion));
      }
    }

    return AuditResult.allow();
  }
}
