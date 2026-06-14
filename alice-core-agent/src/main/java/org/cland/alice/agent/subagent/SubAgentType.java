/*
 * Alice Agent — SubAgentType
 *
 * 定义子 Agent 的类型分类。
 */
package org.cland.alice.agent.subagent;

/**
 * 子 Agent 类型枚举。
 *
 * <p>区分两种不同的子 Agent 类型：
 *
 * <ul>
 *   <li>{@link #ALICE} — 在相同 JVM 进程中创建的 Alice Agent 子会话
 *   <li>{@link #ACP} — 通过 ACP 协议连接的外部 Agent
 * </ul>
 */
public enum SubAgentType {
  ALICE,
  ACP
}
