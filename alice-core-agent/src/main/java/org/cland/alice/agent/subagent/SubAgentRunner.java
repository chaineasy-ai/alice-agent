/*
 * Alice Agent — 子 Agent 执行端口
 *
 * 让"怎么跑一个子 Agent"可替换：默认进程内 Agent（原行为）；亦可换契约实现
 * （alice-agent-runtime 的 ContractSubAgentRunner：AgentHost + InProcessTransport/StdioJsonlTransport）
 * —— SubAgentManager 不感知差异 ✓。
 */
package org.cland.alice.agent.subagent;

import org.cland.alice.core.agent.AgentConfig;

/**
 * 子 Agent 执行端口。
 *
 * <p>实现：
 *
 * <ul>
 *   <li>{@link DefaultSubAgentRunner} — 进程内 {@code Agent}（保持既有行为，缺省 ✓）
 *   <li>{@code org.cland.alice.runtime.subagent.ContractSubAgentRunner} — 走协议契约（宿主 + 传输）， 跨进程只需换传输
 * </ul>
 */
public interface SubAgentRunner {

  /**
   * 执行一个子 Agent 目标（**阻塞**到完成）。
   *
   * @param subAgentId 子 Agent 标识
   * @param goal 目标（自然语言）
   * @param config 子 Agent 配置（模型/迭代上限等）
   * @return 最终文本（子 Agent 答复）
   * @throws Exception 执行失败（由 SubAgentManager 记 FAILED）
   */
  String run(String subAgentId, String goal, AgentConfig config) throws Exception;
}
