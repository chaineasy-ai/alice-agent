package org.cland.alice.core.agent;

import java.util.Map;
import org.cland.alice.core.agent.executor.AgentExecutor;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.planner.ReAct;
import org.cland.alice.env.adapter.EnvEvent;
import org.cland.alice.guardrail.Verificator;
import org.cland.alice.memory.AgentSession;
import org.cland.alice.tool.gateway.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentCore 是 PPAO 循环的核心协调者。
 *
 * <p>对应设计文档中的 AgentCore，作为 Perceive-Plan-Act-Observe-Vefify 闭环的中央控制器。 它持有所有子模块的引用，并通过 {@link
 * AgentExecutor} 驱动整个循环。
 *
 * <p>生命周期状态机参见设计文档：
 *
 * <pre>
 *   START -> PERCEIVING -> PLANNING -> VERIFYING_PRE -> ACTING
 *       -> OBSERVING -> VERIFYING_POST -> REFLECTING -> (loop|FINISH)
 * </pre>
 */
public class AgentCore {

  private static final Logger logger = LoggerFactory.getLogger(AgentCore.class);

  /** Agent 唯一 ID */
  private final String agentId;

  /** 运行时配置 */
  private final AgentConfig config;

  // ========== 子模块引用 ==========

  private AgentExecutor executor;
  private ReAct planner;
  private Verificator guardrail;
  private ToolRegistry toolRegistry;
  private AgentSession memory;
  private EnvEvent envAdapter;

  // ========== 构造 ==========

  public AgentCore() {
    this.agentId = java.util.UUID.randomUUID().toString().substring(0, 8);
    this.config = AgentConfig.defaults();
  }

  public AgentCore(String agentId) {
    this.agentId = agentId;
    this.config = AgentConfig.defaults();
  }

  public AgentCore(AgentConfig config) {
    this.agentId = java.util.UUID.randomUUID().toString().substring(0, 8);
    this.config = config;
  }

  public AgentCore(String agentId, AgentConfig config) {
    this.agentId = agentId;
    this.config = config;
  }

  // ========== 依赖注入 ==========

  public AgentCore withExecutor(AgentExecutor executor) {
    this.executor = executor;
    return this;
  }

  public AgentCore withPlanner(ReAct planner) {
    this.planner = planner;
    return this;
  }

  public AgentCore withGuardrail(Verificator guardrail) {
    this.guardrail = guardrail;
    return this;
  }

  public AgentCore withToolRegistry(ToolRegistry toolRegistry) {
    this.toolRegistry = toolRegistry;
    return this;
  }

  public AgentCore withMemory(AgentSession memory) {
    this.memory = memory;
    return this;
  }

  public AgentCore withEnvAdapter(EnvEvent envAdapter) {
    this.envAdapter = envAdapter;
    return this;
  }

  // ========== Getters ==========

  public String agentId() {
    return agentId;
  }

  public AgentConfig config() {
    return config;
  }

  public AgentExecutor executor() {
    return executor;
  }

  public ReAct planner() {
    return planner;
  }

  public Verificator guardrail() {
    return guardrail;
  }

  public ToolRegistry toolRegistry() {
    return toolRegistry;
  }

  public AgentSession memory() {
    return memory;
  }

  public EnvEvent envAdapter() {
    return envAdapter;
  }

  // ========== 验证钩子（供 AgentExecutor 调用） ==========

  /**
   * Pre-Verify: 在执行 Action 前拦截检查安全性和策略合规性。
   *
   * @param action 待验证的 Action
   * @return true 表示通过，false 表示被拦截
   */
  public boolean verifyPre(Action action) {
    if (!config.preVerifyEnabled() || guardrail == null) {
      return true;
    }
    logger.debug("Pre-verify action: {}", action);
    // 将 Action 转为 Map 传递给 Guardrail
    return guardrail.intercept(
        Map.of(
            "type", action.type().name(),
            "target", action.target() != null ? action.target() : "",
            "actionId", action.actionId()));
  }

  /**
   * Post-Verify: 执行完成后审计观测结果。
   *
   * @param stepResult 当前步骤的结果
   * @return true 表示通过，false 表示需要 Revision
   */
  public boolean verifyPost(StepResult stepResult) {
    if (!config.postVerifyEnabled() || guardrail == null) {
      return true;
    }
    logger.debug("Post-verify result: {}", stepResult);
    return guardrail.audit(stepResult);
  }

  /** 判断 PPAO 循环是否需要终止。 */
  public boolean shouldFinish(AgentContext context, StepResult result) {
    // 1. 显式 Finish 结果
    if (result instanceof StepResult.Finish) {
      return true;
    }
    // 2. 不可恢复错误
    if (result instanceof StepResult.Failure) {
      return true;
    }
    // 3. 达到最大迭代次数
    if (context.isMaxIterationsReached()) {
      logger.warn("Agent {} reached max iterations ({})", agentId, config.maxIterations());
      return true;
    }
    return false;
  }
}
