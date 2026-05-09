package org.cland.alice.guardrail;

import org.cland.alice.core.planner.Plan;

/**
 * 预执行验证器接口 (Pre-Validator)。
 *
 * <p>对应设计文档中 {@code PreValidator} 接口。 在 Action 执行前拦截检查 Plan 的合法性，包括：
 *
 * <ul>
 *   <li>权限沙箱：验证 Agent 是否试图访问超出其 Scope 的资源
 *   <li>逻辑闭环检查：验证推理路径是否存在死循环或前置依赖缺失
 *   <li>策略合规性：检查是否符合定义的 Policy
 * </ul>
 *
 * <p>实现方需要注册到 {@link GuardrailService} 的 preChain 列表中。 所有 PreValidator 通过后，Plan 才能进入执行阶段。
 */
@FunctionalInterface
public interface PreValidator {

  /**
   * 对 Plan 执行预验证检查。
   *
   * @param plan Planner 输出的行动方案
   * @return 审计结果。{@link AuditResult.Status#ALLOW} 表示通过， {@link AuditResult.Status#REJECT} 表示被拒绝。
   */
  AuditResult check(Plan plan);
}
