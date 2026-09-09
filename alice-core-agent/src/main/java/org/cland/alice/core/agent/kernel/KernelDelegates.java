package org.cland.alice.core.agent.kernel;

import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.memory.AgentSession;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.planner.PlannerService;
import org.cland.alice.tool.gateway.ToolRegistry;

/**
 * 内核回调 SPI（KernelDelegates）— 由 legacy {@code AgentFacade} 收敛改名而来。
 *
 * <p>执行实现（L3）对宿主 Agent（L2）的最小回调契约：身份、配置、策略模块访问与三个语义钩子（Pre/Post 验证与终止判断）。 本接口由 L2 Agent
 * 实现并注入执行实现，保证依赖单向（Facade → Agent → Kernel）与依赖倒置。
 *
 * <p><b>过渡说明</b>：签名中的 legacy 类型（{@link AgentContext}/{@link StepResult}/{@link Action}）是先行实现的载体 ——
 * 随 §4 策略钩子（GoalPlanner/ToolGateway/Guardrail/PromptProvider/Inferencer…）逐个落地， 本接口将收敛为纯内核词汇 （策略钩子由
 * Agent 按装配注入，回调 SPI 只保留身份/状态查询）。
 */
public interface KernelDelegates {

  /** 返回 Agent 的唯一标识符。 */
  String agentId();

  /** 返回 Agent 配置。 */
  AgentConfig config();

  /** 返回规划器服务（可能为 null）。 */
  PlannerService plannerService();

  /** 返回工具注册中心（可能为 null）。 */
  ToolRegistry toolRegistry();

  /** 返回记忆会话（可能为 null）。 */
  AgentSession memory();

  /**
   * Pre-Verify: 在执行 Action 前拦截检查安全性和策略合规性。
   *
   * @param action 待验证的 Action
   * @return true 表示通过，false 表示被拦截
   */
  boolean verifyPre(Action action);

  /**
   * Post-Verify: 执行完成后审计观测结果。
   *
   * @param stepResult 当前步骤的结果
   * @return true 表示通过，false 表示需要 Revision
   */
  boolean verifyPost(StepResult stepResult);

  /**
   * 判断执行循环是否需要终止。
   *
   * @param context 当前 Agent 上下文
   * @param result 当前步骤结果（可能为 null）
   * @return true 表示应终止循环
   */
  boolean shouldFinish(AgentContext context, StepResult result);
}
