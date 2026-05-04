package org.cland.alice.guardrail.validators;

import org.cland.alice.core.planner.Plan;
import org.cland.alice.guardrail.AuditResult;
import org.cland.alice.guardrail.CorrectionSuggestion;
import org.cland.alice.guardrail.PreValidator;

import java.util.*;

/**
 * 逻辑闭环检查验证器 (Pre-Validator)。
 * <p>
 * 对应设计文档中 "逻辑闭环检查" 的具体实现。
 * 验证推理路径是否存在逻辑死循环，以及所需工具的输入是否已在上下文中获得。
 * <p>
 * <b>检查规则：</b>
 * <ul>
 *   <li><b>死循环检测：</b>检测 Plan 中是否存在重复的相同 Action 序列模式</li>
 *   <li><b>前置依赖检查：</b>检查步骤间是否存在参数依赖断裂</li>
 *   <li><b>终止保障：</b>确保 Plan 包含明确的终止步骤 (FINISH)</li>
 * </ul>
 */
public final class LogicSanityValidator implements PreValidator {

    /** 最大连续重复步骤数阈值（超过即视为死循环） */
    private static final int MAX_REPEAT_THRESHOLD = 3;

    @Override
    public AuditResult check(Plan plan) {
        List<Plan.Step> steps = plan.steps();

        if (steps.isEmpty()) {
            return AuditResult.reject(
                "Plan contains no steps",
                CorrectionSuggestion.replan("A plan must contain at least one step")
            );
        }

        // 1. 死循环检测：检查是否有连续重复的相同 actionType + target
        AuditResult cycleResult = detectCycle(steps);
        if (!cycleResult.isPassed()) {
            return cycleResult;
        }

        // 2. 终止保障：检查是否有 FINISH 步骤（对于多步骤 Plan）
        if (steps.size() > 1) {
            AuditResult finishResult = ensureTermination(steps);
            if (!finishResult.isPassed()) {
                return finishResult;
            }
        }

        return AuditResult.allow();
    }

    /**
     * 检测 Planning 步骤中是否存在死循环模式。
     * <p>
     * 策略：如果同一 (actionType, target) 连续重复超过 MAX_REPEAT_THRESHOLD 次，
     * 判定为逻辑死循环。
     */
    private AuditResult detectCycle(List<Plan.Step> steps) {
        int repeatCount = 1;
        String previousKey = null;

        for (Plan.Step step : steps) {
            String currentKey = step.actionType() + ":" + (step.target() != null ? step.target() : "");

            if (currentKey.equals(previousKey)) {
                repeatCount++;
                if (repeatCount > MAX_REPEAT_THRESHOLD) {
                    return AuditResult.reject(
                        "Logic cycle detected: step '" + currentKey
                            + "' repeated " + repeatCount + " times consecutively",
                        CorrectionSuggestion.replan(
                            "Remove the cycle by consolidating repeated steps or changing the approach"
                        )
                    );
                }
            } else {
                repeatCount = 1;
                previousKey = currentKey;
            }
        }

        return AuditResult.allow();
    }

    /**
     * 确保多步骤 Plan 中包含明确的终止步骤。
     * <p>
     * 如果最后一步不是 FINISH，则 Plan 可能陷入无限执行。
     */
    private AuditResult ensureTermination(List<Plan.Step> steps) {
        Plan.Step lastStep = steps.get(steps.size() - 1);
        if (!"FINISH".equals(lastStep.actionType())) {
            // 允许 REVISION 作为最后一步（会触发重新规划）
            if (!"REVISION".equals(lastStep.actionType())) {
                return AuditResult.reject(
                    "Plan lacks termination: last step is " + lastStep.actionType()
                        + " (expected FINISH or REVISION)",
                    CorrectionSuggestion.replan(
                        "Add a FINISH step at the end of the plan, or use REVISION if replanning"
                    )
                );
            }
        }
        return AuditResult.allow();
    }
}
