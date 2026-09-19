/*
 * Alice Agent — 默认子 Agent 执行器（进程内 Agent）
 */
package org.cland.alice.agent.subagent;

import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.AgentConfig;

/**
 * 默认子 Agent 执行器 —— 进程内创建一个独立 {@link Agent} 并同步执行（与历史行为一致）。
 *
 * <p>生命周期由本类负责（执行完 {@code close()}）；异常直接抛出，由 {@code SubAgentManager} 记 FAILED。
 */
public final class DefaultSubAgentRunner implements SubAgentRunner {

  @Override
  public String run(String subAgentId, String goal, AgentConfig config) throws Exception {
    Agent subAgent = new Agent(subAgentId, config);
    try {
      // 同步阻塞 — ask() 内部使用 CountDownLatch 等待 PPAO 循环完成
      return subAgent.ask(goal, config.defaultModelId());
    } finally {
      try {
        subAgent.close();
      } catch (Exception ignored) {
        // 关闭失败不影响结果返回
      }
    }
  }
}
