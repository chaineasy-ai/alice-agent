package org.cland.alice.guardrail;

import org.cland.alice.core.planner.Plan;

import java.util.Map;

/**
 * 执行后验证器接口 (Post-Validator)。
 * <p>
 * 对应设计文档中 {@code PostValidator} 接口。
 * 在 Action 执行完成后审计观测结果，包括：
 * <ul>
 *   <li>幻觉检测 (Hallucination Detection)：交叉验证事实性陈述</li>
 *   <li>一致性评估：比较 LLM 预测的输出类型与实际返回类型是否匹配</li>
 *   <li>副作用审计：检测环境状态变化是否超出预期</li>
 *   <li>数据 Schema 验证：验证返回数据是否符合预期结构</li>
 * </ul>
 * <p>
 * 实现方需要注册到 {@link GuardrailService} 的 postChain 列表中。
 * PostValidator 失败将触发重新规划 (Re-plan)。
 * <p>
 * 接口使用 {@link Map} 作为观测数据的载体，以避免对 alice-core-agent 模块的编译依赖，
 * 防止产生循环依赖。调用方在传入前将 {@code Observation} 转换为 Map 即可。
 */
@FunctionalInterface
public interface PostValidator {

    /**
     * 观测结果的状态枚举（避免对 alice-core-agent.Observation.Status 的依赖）。
     */
    enum ObsStatus {
        SUCCESS,
        FAILURE,
        PARTIAL,
        TIMEOUT,
        BLOCKED
    }

    /**
     * 对执行结果执行后验证审计。
     *
     * @param observationMap 执行完成后的观测数据 Map，应包含：
     *                       <ul>
     *                         <li>{@code "status"} — {@link ObsStatus} 或字符串</li>
     *                         <li>{@code "summary"} — 结果摘要</li>
     *                         <li>{@code "rawData"} — 原始返回数据</li>
     *                         <li>{@code "metadata"} — 元数据 Map</li>
     *                       </ul>
     * @param originalPlan 原始的 Plan，用于比较预期与实际输出
     * @return 审计结果。{@link AuditResult.Status#ALLOW} 表示数据有效，
     *         {@link AuditResult.Status#INVALID} 表示检测到异常/幻觉。
     */
    AuditResult check(Map<String, Object> observationMap, Plan originalPlan);
}
