package org.cland.alice.core.agent.kernel;

/**
 * 内核状态只读快照（阶段/迭代/会话）。
 *
 * <p>目标态下，阶段枚举与迭代单计数将被账本槽位（goal 图游标、多级预算注解）取代； 当前 phase 以字符串承载 legacy 阶段名，避免内核词汇耦合 legacy 的 Phase
 * 枚举。
 *
 * @param sessionId 当前会话 ID；未执行时为 {@code null}
 * @param phase 当前阶段名（legacy：PPAO Phase 名；未执行时为 {@code IDLE}）
 * @param iteration 已完成的迭代步数
 * @param maxIterations 本次会话的迭代预算
 * @param cancelled 是否已收到取消信号
 */
public record KernelState(
    String sessionId, String phase, int iteration, int maxIterations, boolean cancelled) {

  /** 空闲态：尚未执行任何会话。 */
  public static KernelState idle() {
    return new KernelState(null, "IDLE", 0, 0, false);
  }
}
