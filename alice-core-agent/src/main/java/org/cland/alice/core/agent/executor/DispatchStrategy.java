package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.StepWithContext;

/**
 * Micro-ReAct 分派策略接口。
 *
 * <p>策略模式封装，将 LLM 推理和工具调用的分派逻辑从 {@link MicroReActEngine} 中解耦。 遵循开闭原则（OCP）：新增分派类型只需添加新的 {@link
 * DispatchStrategy} 实现， 无需修改 MicroReActEngine 或 AgentExecutor。
 *
 * <p>每个策略负责：
 *
 * <ol>
 *   <li>执行对应的 Action（LLM 调用或工具执行）
 *   <li>将结果写入 AgentContext
 *   <li>触发对应的事件（onThought/onAction/onObserve）
 *   <li>返回 {@link StepWithContext} 供下一阶段使用
 * </ol>
 *
 * @see LlmDispatchStrategy
 * @see ToolDispatchStrategy
 * @see MicroReActEngine
 */
public interface DispatchStrategy {

  /**
   * 执行给定的 Action 并返回结果。
   *
   * @param ctx 当前 Agent 上下文
   * @param action 要执行的 Action
   * @return 异步的 {@link StepWithContext}，包含更新后的上下文和步骤结果
   */
  Future<StepWithContext> execute(AgentContext ctx, Action action);

  /**
   * 判断此策略是否支持指定的 Action 类型。
   *
   * @param action 待检查的 Action
   * @return true 如果此策略可以处理该 Action
   */
  boolean supports(Action action);
}
