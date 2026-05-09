package org.cland.alice.guardrail;

import java.util.Map;

/**
 * 验证器接口，对应设计文档中 V 层的 Guardrail。
 *
 * <p>负责 PPAO 循环中的 Pre-Verify 和 Post-Verify：
 *
 * <ul>
 *   <li>{@link #intercept(Map)} — Pre-Verify: 执行 Action 前拦截检查安全性和策略合规性
 *   <li>{@link #audit(Object)} — Post-Verify: 执行完成后审计观测结果
 * </ul>
 *
 * <p>本接口对 alice-core-agent 无编译依赖，通过 Map / Object 与外部交互。
 */
public interface Verificator {

  /**
   * Pre-Verify: 在 Action 执行前拦截检查。
   *
   * <p>检查安全策略、权限、资源限制等。
   *
   * @param action 待执行的 Action 属性结构（包含 type, target, parameters 等）
   * @return true 表示通过（Allow），false 表示被拦截（Blocked）
   */
  default boolean intercept(Map<String, Object> action) {
    return true;
  }

  /**
   * Post-Verify: 执行完成后审计观测结果。
   *
   * <p>检查执行结果是否符合预期，是否触发了安全规则， 是否需要进行 Self-Correction。
   *
   * @param stepResult 当前步骤的结果对象
   * @return true 表示审计通过，false 表示需要 Revision
   */
  default boolean audit(Object stepResult) {
    return true;
  }
}
