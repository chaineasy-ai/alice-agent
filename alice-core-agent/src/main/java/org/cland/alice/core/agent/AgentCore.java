package org.cland.alice.core.agent;

import org.cland.alice.core.agent.executor.AgentExecutor;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.planner.PlannerService;
import org.cland.alice.env.adapter.EnvEvent;
import org.cland.alice.guardrail.Verificator;
import org.cland.alice.memory.AgentSession;
import org.cland.alice.tool.gateway.ToolRegistry;

/**
 * AgentCore 是 PPAO 循环的核心协调者（已废弃）。
 *
 * <p>从 v2.0 起，AgentCore 的功能已全部合并到 {@link Agent} 类中。 Agent 现在直接持有所有子模块引用并提供验证钩子。
 *
 * <p>AgentCore 保留为 {@link Agent} 的简单委托代理，以保持向后兼容。 新代码应直接使用 {@link Agent}。
 *
 * @deprecated 使用 {@link Agent} 替代。所有方法直接委托给内部 Agent 实例。
 */
@Deprecated
public class AgentCore {

  /** 内部持有的 Agent 实例 */
  private final Agent agent;

  // ========== 构造 ==========

  public AgentCore() {
    this.agent = new Agent();
  }

  public AgentCore(String agentId) {
    this.agent = new Agent(agentId);
  }

  public AgentCore(AgentConfig config) {
    this.agent = new Agent(config);
  }

  public AgentCore(String agentId, AgentConfig config) {
    this.agent = new Agent(agentId, config);
  }

  // ========== 属性 ==========

  public String agentId() {
    return agent.agentId();
  }

  public AgentConfig config() {
    return agent.config();
  }

  public AgentExecutor executor() {
    // AgentExecutor is internal; expose via the agent
    return null;
  }

  // ========== 依赖注入 ==========

  public AgentCore withExecutor(AgentExecutor executor) {
    // AgentExecutor is now managed internally by Agent, no-op
    return this;
  }

  public AgentCore withPlannerService(PlannerService plannerService) {
    agent.withPlannerService(plannerService);
    return this;
  }

  public AgentCore withGuardrail(Verificator guardrail) {
    agent.withGuardrail(guardrail);
    return this;
  }

  public AgentCore withToolRegistry(ToolRegistry toolRegistry) {
    agent.withToolRegistry(toolRegistry);
    return this;
  }

  public AgentCore withMemory(AgentSession memory) {
    agent.withMemory(memory);
    return this;
  }

  public AgentCore withEnvAdapter(EnvEvent envAdapter) {
    agent.withEnvAdapter(envAdapter);
    return this;
  }

  // ========== Getters ==========

  public PlannerService plannerService() {
    return agent.plannerService();
  }

  public Verificator guardrail() {
    return agent.guardrail();
  }

  public ToolRegistry toolRegistry() {
    return agent.toolRegistry();
  }

  public AgentSession memory() {
    return agent.memory();
  }

  public EnvEvent envAdapter() {
    return agent.envAdapter();
  }

  // ========== 验证钩子 ==========

  public boolean verifyPre(Action action) {
    return agent.verifyPre(action);
  }

  public boolean verifyPost(StepResult stepResult) {
    return agent.verifyPost(stepResult);
  }

  public boolean shouldFinish(AgentContext context, StepResult result) {
    return agent.shouldFinish(context, result);
  }
}
