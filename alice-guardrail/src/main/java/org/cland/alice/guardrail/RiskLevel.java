package org.cland.alice.guardrail;

/**
 * 审计结果风险等级，对应设计文档 AuditResult 的 risk 字段。
 *
 * <p>决定验证通过后的后续处理策略：
 *
 * <ul>
 *   <li>{@link #LOW} — 低风险，自动通过
 *   <li>{@link #MEDIUM} — 中等风险，需标记但可继续
 *   <li>{@link #HIGH} — 高风险，需人工确认 (Human-in-the-loop)
 *   <li>{@link #CRITICAL} — 严重风险，直接拒绝
 * </ul>
 */
public enum RiskLevel {

  /** 低风险：自动通过，无需额外处理 */
  LOW,

  /** 中等风险：通过但标记记录，后续可用于策略优化 */
  MEDIUM,

  /** 高风险：需要人工确认 (Human-in-the-loop)，挂起任务流 */
  HIGH,

  /** 严重风险：直接拒绝，不允许执行 */
  CRITICAL
}
