package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.lifecycle.StepWithContext;

/**
 * PPAO Macro Loop 单阶段策略接口。
 *
 * <p>遵循开闭原则（OCP）：新增或重排 PPAO 阶段只需实现此接口并注册到 {@link AgentExecutor}， 无需修改 {@code loopBody()} 编排代码。
 *
 * <p><b>与 {@code AgentContext.Phase} 的关系：</b> 每个阶段通常对应一个 {@code AgentContext.Phase} 状态，但非强制 — 一个
 * PhaseHandler 可以处理多个 PPAO 子步骤（如 observe+verifyPost 可合并）。
 *
 * <pre>
 *   使用示例:
 *   executor.registerPhase(new MyCustomPhase());
 *
 *   默认 PPAO 链:
 *   PlanPhase → VerifyPrePhase → ActPhase → ObservePhase → VerifyPostPhase → ReflectPhase
 * </pre>
 *
 * @see AgentExecutor#registerPhase(MacroLoopPhase)
 * @see AgentExecutor#loopBody(AgentContext)
 */
@FunctionalInterface
public interface MacroLoopPhase {

  /**
   * 执行当前 PPAO 阶段。
   *
   * @param ctx 当前 Agent 上下文（含 iteration、thoughtChain、phase 等状态）
   * @param input 上一阶段输出的 StepWithContext（含 Action 和 Observation）
   * @return 当前阶段处理后的 StepWithContext，供下一阶段消费
   */
  Future<StepWithContext> execute(AgentContext ctx, StepWithContext input);

  /**
   * 返回此阶段对应的 PPAO 阶段标识。
   *
   * <p>用于日志、WAL Checkpoint 和调试追踪。
   *
   * @return 阶段名称（如 "PLAN", "VERIFY_PRE", "ACT"）
   */
  default String phaseName() {
    return getClass().getSimpleName().replace("Phase", "").toUpperCase();
  }
}
