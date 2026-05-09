package org.cland.alice.core.agent.lifecycle;

import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.result.StepResult;

/**
 * PPAO (Perceive-Plan-Act-Verify) 核心生命周期接口。
 *
 * <p>对应设计文档中 Lifecycle 接口，定义了 Agent 核心决策循环的每个阶段：
 *
 * <ul>
 *   <li>{@link #onPerceive(Object)} — 感知输入，构建上下文
 *   <li>{@link #onPlan(AgentContext)} — 基于上下文规划下一步
 *   <li>{@link #onAct(Action)} — 执行规划的动作
 *   <li>{@link #onVerify(StepResult)} — 验证执行结果并决定后续
 * </ul>
 *
 * @param <I> 输入类型
 */
public interface Lifecycle<I> {

  /**
   * 1. Perceive: 将原始输入转换为结构化的 AgentContext。
   *
   * @param input 原始用户输入或环境信号
   * @return 构建的 Agent 上下文（包含 Memory、Session 等）
   */
  AgentContext onPerceive(I input);

  /**
   * 2. Plan: 基于当前上下文，决策下一步 Action。
   *
   * <p>内部调用 Planner 模块，结合 LLM 推理产生 Thought + Action 对。
   *
   * @param context 当前 Agent 上下文
   * @return 下一步的 Action 规划
   */
  Action onPlan(AgentContext context);

  /**
   * 3. Act: 执行指定的 Action。
   *
   * <p>内部调用 ToolGateway 派发 Action，或调用 ModelProvider 进行 LLM 调用。
   *
   * @param action 要执行的动作
   * @param context 当前上下文
   * @return 执行后的原始观测结果
   */
  Observation onAct(Action action, AgentContext context);

  /**
   * 4. Verify (Pre + Post): 验证动作或观测结果。
   *
   * <p>Pre-verify: 在 Act 前拦截 Action，检测安全性/策略合规性。 Post-verify: 在 Observe 后审计结果，触发自省
   * (Self-Correction)。
   *
   * @param stepResult 当前步骤的待验证结果
   * @return true 表示验证通过，false 表示需要 Revision（重新规划）
   */
  boolean onVerify(StepResult stepResult);

  /**
   * 5. Reflect: 当 Verify 失败时，将反馈注入上下文以触发重新规划。
   *
   * @param context 当前上下文
   * @param feedback 验证失败的反馈信息
   */
  void onReflect(AgentContext context, String feedback);
}
