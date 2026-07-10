package org.cland.alice.core.agent;

import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.memory.AgentSession;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.planner.PlannerService;
import org.cland.alice.tool.gateway.ToolRegistry;

/**
 * Agent 门面接口 — 向 PPAO 编排层暴露的最小职责集合。
 *
 * <p>遵循接口隔离原则（ISP）：PPAO 循环各阶段仅依赖此门面，而非完整的 {@link Agent} 类。 遵循依赖倒置原则（DIP）：{@link AgentExecutor} 和
 * {@code MicroReActEngine} 依赖此抽象， 而非 {@link Agent} 具体实现。
 *
 * <p>职责范围：
 *
 * <ul>
 *   <li><b>身份</b> — {@link #agentId()}
 *   <li><b>配置</b> — {@link #config()}
 *   <li><b>模块访问</b> — {@link #plannerService()}, {@link #toolRegistry()}, {@link #memory()}
 *   <li><b>验证</b> — {@link #verifyPre(Action)}, {@link #verifyPost(StepResult)}
 *   <li><b>生命周期</b> — {@link #shouldFinish(AgentContext, StepResult)}
 * </ul>
 */
public interface AgentFacade {

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
   * 判断 PPAO 循环是否需要终止。
   *
   * @param context 当前 Agent 上下文
   * @param result 当前步骤结果（可能为 null）
   * @return true 表示应终止循环
   */
  boolean shouldFinish(AgentContext context, StepResult result);
}
