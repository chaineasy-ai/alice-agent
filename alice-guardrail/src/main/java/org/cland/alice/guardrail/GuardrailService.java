package org.cland.alice.guardrail;

import org.cland.alice.core.planner.Plan;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 审校委员会 — Guardrail 核心服务。
 * <p>
 * 对应设计文档中的 {@code GuardrailService}，是 V (Verify) 层的核心入口。
 * 采用拦截器链 (Interceptor Chain) 模式，支持动态扩展验证规则。
 * <p>
 * <b>职责：</b>
 * <ul>
 *   <li><b>Phase 1 (Pre-Execution)：</b> 接收 {@link Plan}，遍历所有 {@link PreValidator}
 *       进行预执行审计。包括权限沙箱、逻辑闭环检查、策略合规性。</li>
 *   <li><b>Phase 2 (Post-Execution)：</b> 接收观测数据的 Map 和原始 {@link Plan}，
 *       遍历所有 {@link PostValidator} 进行执行后审计。包括幻觉检测、
 *       副作用审计、数据 Schema 验证。</li>
 * </ul>
 * <p>
 * <b>验证决策状态机：</b>
 * <pre>
 *        [ INPUT RECEIVED ]
 *                |
 *       +--------v---------+
 *       |  PRE-EXEC AUDIT  | &lt;----------+
 *       +--------+---------+            |
 *                |                      |
 *       (Reject) +------&gt; [ LOG REASON & REQUEST FIX ]
 *                |                      ^
 *        (Pass)  |                      |
 *                v                      |
 *        [ TOOL EXECUTION ]             |
 *                |                      |
 *       +--------v---------+            |
 *       | POST-EXEC AUDIT  | -----------+ (Refine/Retry)
 *       +--------+---------+
 *                |
 *       (Fail)   +------&gt; [ FLAG AS UNTRUSTED ]
 *                |
 *        (Pass)  +------&gt; [ COMMIT TO MEMORY ]
 * </pre>
 */
public final class GuardrailService {

    private final List<PreValidator> preChain;
    private final List<PostValidator> postChain;
    private final PolicyEngine policyEngine;

    /** 高风险动作的关键字列表（命中后自动标记 MANUAL_CONFIRM） */
    private final Set<String> highRiskActions;

    public GuardrailService() {
        this.preChain = new CopyOnWriteArrayList<>();
        this.postChain = new CopyOnWriteArrayList<>();
        this.policyEngine = new PolicyEngine();
        this.highRiskActions = new HashSet<>();
        registerDefaultHighRiskPatterns();
    }

    /**
     * 注册默认的高风险动作模式。
     * 命中这些动作的 Plan 将触发 MANUAL_CONFIRM。
     */
    private void registerDefaultHighRiskPatterns() {
        // 高风险数据库操作
        highRiskActions.add("DROP");
        highRiskActions.add("DELETE");
        highRiskActions.add("TRUNCATE");
        // 系统级操作
        highRiskActions.add("SHUTDOWN");
        highRiskActions.add("REBOOT");
        highRiskActions.add("EXEC");
        // 敏感文件操作
        highRiskActions.add("RM_RF");
        highRiskActions.add("CHMOD_777");
    }

    // ========================================================================
    // 注册方法
    // ========================================================================

    /**
     * 注册一个预执行验证器 (PreValidator)。
     *
     * @param validator 待注册的验证器
     */
    public void registerPreValidator(PreValidator validator) {
        preChain.add(Objects.requireNonNull(validator, "PreValidator must not be null"));
    }

    /**
     * 移除一个预执行验证器。
     *
     * @param validator 待移除的验证器
     * @return 是否成功移除
     */
    public boolean unregisterPreValidator(PreValidator validator) {
        return preChain.remove(validator);
    }

    /**
     * 注册一个执行后验证器 (PostValidator)。
     *
     * @param validator 待注册的验证器
     */
    public void registerPostValidator(PostValidator validator) {
        postChain.add(Objects.requireNonNull(validator, "PostValidator must not be null"));
    }

    /**
     * 移除一个执行后验证器。
     *
     * @param validator 待移除的验证器
     * @return 是否成功移除
     */
    public boolean unregisterPostValidator(PostValidator validator) {
        return postChain.remove(validator);
    }

    // ========================================================================
    // 核心验证 API
    // ========================================================================

    /**
     * Phase 1: 预执行验证。
     * <p>
     * 对 Planner 提交的 Plan 执行一系列 Pre-Validator 检查。
     * 所有验证器通过后才返回 ALLOW。
     *
     * @param plan Planner 输出的行动方案
     * @return 审计结果
     */
    public AuditResult verifyPlan(Plan plan) {
        if (plan == null) {
            return AuditResult.reject("Plan is null",
                CorrectionSuggestion.replan("Cannot verify a null plan"));
        }

        // 1. 高风险动作检测
        AuditResult riskResult = checkHighRiskActions(plan);
        if (riskResult.needsManualConfirm()) {
            return riskResult;
        }

        // 2. 遍历预验证链
        for (PreValidator validator : preChain) {
            AuditResult result = validator.check(plan);
            if (!result.isPassed()) {
                return result;
            }
        }

        return AuditResult.allow();
    }

    /**
     * Phase 2: 执行后验证。
     * <p>
     * 对工具执行返回的观测数据执行 Post-Validator 检查。
     * 包括幻觉检测、数据 Schema 验证、副作用审计。
     *
     * @param observationMap 执行完成后从环境或工具返回的观测数据 Map
     *                       （由调用方从 Observation 转换而来，
     *                        包含 status, summary, rawData, metadata 等 key）
     * @param originalPlan 原始的 Plan，用于对比预期与实际输出
     * @return 审计结果
     */
    public AuditResult verifyResult(Map<String, Object> observationMap, Plan originalPlan) {
        if (observationMap == null) {
            return AuditResult.invalid("Observation is null",
                CorrectionSuggestion.replan("Cannot verify a null observation"));
        }

        // 如果观测本身已经是失败/超时/拦截状态，直接返回 INVALID
        PostValidator.ObsStatus status = extractObsStatus(observationMap);
        if (status == PostValidator.ObsStatus.FAILURE
            || status == PostValidator.ObsStatus.TIMEOUT
            || status == PostValidator.ObsStatus.BLOCKED) {
            return AuditResult.invalid(
                "Observation status is " + status + ": " + observationMap.get("summary"),
                CorrectionSuggestion.replan("Re-plan needed due to failed observation")
            );
        }

        // 遍历后验证链
        for (PostValidator validator : postChain) {
            AuditResult result = validator.check(observationMap, originalPlan);
            if (!result.isPassed()) {
                return result;
            }
        }

        return AuditResult.allow();
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    /**
     * 从观测 Map 中提取状态。
     */
    private PostValidator.ObsStatus extractObsStatus(Map<String, Object> map) {
        Object statusObj = map.get("status");
        if (statusObj instanceof PostValidator.ObsStatus obs) {
            return obs;
        }
        if (statusObj instanceof String s) {
            try {
                return PostValidator.ObsStatus.valueOf(s.toUpperCase());
            } catch (IllegalArgumentException e) {
                return PostValidator.ObsStatus.FAILURE;
            }
        }
        // 默认视为成功
        return PostValidator.ObsStatus.SUCCESS;
    }

    /**
     * 检查 Plan 中是否包含高风险动作。
     * <p>
     * 对应设计文档中 Human-in-the-loop 优化。
     * 高风险动作会触发 MANUAL_CONFIRM，挂起任务流。
     */
    private AuditResult checkHighRiskActions(Plan plan) {
        for (Plan.Step step : plan.steps()) {
            if (step.target() == null) continue;
            String targetUpper = step.target().toUpperCase();
            for (String risk : highRiskActions) {
                if (targetUpper.contains(risk)) {
                    return AuditResult.manualConfirm(
                        "High-risk action detected: " + step.actionType()
                            + " -> " + step.target()
                            + " (matched pattern: " + risk + ")"
                    );
                }
            }
        }
        return AuditResult.allow();
    }

    // ========================================================================
    // 访问器
    // ========================================================================

    /**
     * 获取策略引擎实例。
     */
    public PolicyEngine policyEngine() {
        return policyEngine;
    }

    /**
     * 获取当前已注册的预执行验证器列表（不可修改视图）。
     */
    public List<PreValidator> preValidators() {
        return Collections.unmodifiableList(preChain);
    }

    /**
     * 获取当前已注册的执行后验证器列表（不可修改视图）。
     */
    public List<PostValidator> postValidators() {
        return Collections.unmodifiableList(postChain);
    }
}
