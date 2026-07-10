package org.cland.alice.core.agent.lifecycle;

import java.util.Objects;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.result.StepResult;

/**
 * 将 {@link AgentContext} 与当前 {@link StepResult} 关联的内部记录。
 *
 * <p>用于在 PPAO 阶段之间传递两者，使后续阶段能同时访问上下文状态和当前步骤的执行结果。
 *
 * <p>原为 {@code AgentExecutor} 的私有内联记录，为遵循单一职责原则（SRP）提取为独立的公开类型， 使 {@code MicroReActEngine}
 * 等组件可以与之交互而不依赖 {@code AgentExecutor} 内部类型。
 *
 * @param context 当前 Agent 上下文（不可为 null）
 * @param result 当前步骤的结果（不可为 null）
 */
public record StepWithContext(AgentContext context, StepResult result) {

  public StepWithContext {
    Objects.requireNonNull(context, "context must not be null");
    // result 可为 null — 用于 Macro 循环启动时的初始占位
  }

  /**
   * 便捷方法：获取步骤中的 Continue action。
   *
   * @return 如果 result 为 Continue 则返回 nextAction，否则返回 {@code null}
   */
  public Action nextAction() {
    return result instanceof StepResult.Continue c ? c.nextAction() : null;
  }

  /**
   * 便捷方法：获取步骤中的 Observation。
   *
   * @return 如果 result 为 Continue 则返回 observation，否则返回 {@code null}
   */
  public Observation observation() {
    return result instanceof StepResult.Continue c ? c.observation() : null;
  }
}
