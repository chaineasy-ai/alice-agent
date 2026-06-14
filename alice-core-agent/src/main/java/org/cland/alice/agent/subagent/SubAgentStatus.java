/*
 * Alice Agent — SubAgentStatus
 *
 * 定义子 Agent 的生命周期状态。
 */
package org.cland.alice.agent.subagent;

/**
 * 子 Agent 生命周期状态枚举。
 *
 * <p>状态转换规则：
 *
 * <pre>
 *   RUNNING → COMPLETED | FAILED | CANCELED   (ALICE type)
 *   CONNECTED → RUNNING | FAILED | CANCELED   (ACP type)
 * </pre>
 *
 * <ul>
 *   <li>{@link #RUNNING} — 正在执行（ALICE）或可接受提示（ACP，主动状态）
 *   <li>{@link #COMPLETED} — 目标已达成（仅 ALICE 类型）
 *   <li>{@link #FAILED} — 因异常终止
 *   <li>{@link #CANCELED} — 用户或父会话主动取消
 *   <li>{@link #CONNECTED} — 已注册并连接但空闲（仅 ACP 类型）
 * </ul>
 */
public enum SubAgentStatus {
  RUNNING,
  COMPLETED,
  FAILED,
  CANCELED,
  CONNECTED
}
