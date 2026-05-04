package org.cland.alice.guardrail.validators;

import org.cland.alice.core.planner.Plan;
import org.cland.alice.guardrail.AuditResult;
import org.cland.alice.guardrail.CorrectionSuggestion;
import org.cland.alice.guardrail.PostValidator;

import java.util.Map;
import java.util.Optional;

/**
 * 幻觉检测验证器 (Post-Validator)。
 * <p>
 * 对应设计文档中 "幻觉检测" 的具体实现。
 * 在工具执行完成后，对返回的 Observation 进行一致性评估。
 * <p>
 * <b>检查规则：</b>
 * <ul>
 *   <li><b>空结果检测：</b>检测观测是否返回空数据</li>
 *   <li><b>错误模式检测：</b>检测输出中是否包含常见的错误/异常模式</li>
 *   <li><b>类型一致性：</b>根据 Plan 的步骤类型，检查返回数据格式的合理性</li>
 * </ul>
 * <p>
 * <b>设计说明：</b>
 * 当前实现基于确定性规则。对于深度语义级别的幻觉检测，
 * 建议使用轻量级 LLM（如 Qwen-7B）作为 "独立评审员" 进行二次确认，
 * 实现 "模型验证模型" 的架构（设计文档第5.1节）。
 */
public final class HallucinationDetector implements PostValidator {

    /** 空结果关键字集合 */
    private static final String[] EMPTY_RESULT_PATTERNS = {
        "no results found",
        "no data",
        "empty result",
        "null",
        "undefined"
    };

    /** 常见错误模式集合 */
    private static final String[] ERROR_PATTERNS = {
        "error:",
        "exception:",
        "failed to",
        "unable to",
        "permission denied",
        "access denied",
        "connection refused",
        "timeout",
        "not found"
    };

    @Override
    public AuditResult check(Map<String, Object> observationMap, Plan originalPlan) {
        // 1. 提取观测状态
        ObsStatus status = extractStatus(observationMap);
        if (status == ObsStatus.FAILURE) {
            String summary = extractString(observationMap, "summary");
            return AuditResult.invalid(
                "Observation has FAILURE status: " + summary,
                CorrectionSuggestion.replan("Retry or choose an alternative approach")
            );
        }

        // 2. 检查 rawData 是否存在空结果或错误模式
        String rawData = extractString(observationMap, "rawData");
        if (rawData != null && !rawData.isBlank()) {
            // 空结果检测
            Optional<String> emptyResult = detectPattern(rawData, EMPTY_RESULT_PATTERNS);
            if (emptyResult.isPresent()) {
                return AuditResult.invalid(
                    "Hallucination detected: " + emptyResult.get(),
                    CorrectionSuggestion.modifyParameters(
                        "The tool returned an empty/non-existent result pattern. "
                            + "Consider refining the query or checking data source availability.",
                        observationMap.get("metadata")
                    )
                );
            }

            // 错误模式检测
            Optional<String> errorDetected = detectPattern(rawData, ERROR_PATTERNS);
            if (errorDetected.isPresent()) {
                return AuditResult.invalid(
                    "Error pattern detected in observation: " + errorDetected.get(),
                    CorrectionSuggestion.replan(
                        "Tool execution encountered an error. Consider retrying with different parameters."
                    )
                );
            }
        }

        // 3. 类型一致性校验
        AuditResult consistencyResult = checkTypeConsistency(rawData, originalPlan);
        if (!consistencyResult.isPassed()) {
            return consistencyResult;
        }

        return AuditResult.allow();
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    /**
     * 从观测 Map 中提取状态枚举。
     */
    @SuppressWarnings("unchecked")
    private ObsStatus extractStatus(Map<String, Object> map) {
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

    /**
     * 从观测 Map 中提取字符串字段。
     */
    private String extractString(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        return val instanceof String s ? s : null;
    }

    /**
     * 在数据中检测是否包含任意目标模式。
     */
    private Optional<String> detectPattern(String data, String[] patterns) {
        String dataLower = data.toLowerCase();
        for (String pattern : patterns) {
            if (dataLower.contains(pattern)) {
                return Optional.of("Pattern detected: '" + pattern + "'");
            }
        }
        return Optional.empty();
    }

    /**
     * 类型一致性校验。
     * 根据 Plan 中步骤的类型，粗略检查返回格式的合理性。
     */
    private AuditResult checkTypeConsistency(String rawData, Plan plan) {
        if (plan.steps() == null || plan.steps().isEmpty()) {
            return AuditResult.allow();
        }

        Plan.Step lastStep = plan.steps().get(plan.steps().size() - 1);

        switch (lastStep.actionType()) {
            case "TOOL_CALL":
                if (rawData == null || rawData.isBlank()) {
                    return AuditResult.invalid(
                        "Type consistency: TOOL_CALL returned empty data, expected structured output",
                        CorrectionSuggestion.replan("Tool returned empty result. Check tool availability.")
                    );
                }
                break;

            case "LLM_INFERENCE":
                if (rawData == null || rawData.isBlank()) {
                    return AuditResult.invalid(
                        "Type consistency: LLM_INFERENCE returned empty response",
                        CorrectionSuggestion.replan("Model returned empty response. Retry inference.")
                    );
                }
                break;

            default:
                break;
        }

        return AuditResult.allow();
    }
}
