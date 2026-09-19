/*
 * Alice Agent — 宿主健康快照
 */
package org.cland.alice.runtime;

import java.time.Instant;
import org.cland.alice.agent.proto.event.StepEvent;

/**
 * 宿主健康快照（供协议面 {@code /health}、看板与看门狗使用）。
 *
 * @param sessionId 会话标识
 * @param roundActive 当前是否有轮次在执行
 * @param rounds 已执行轮数
 * @param lastRoundMs 最近一轮耗时（毫秒；未执行过为 0）
 * @param lastUsage 最近一轮用量
 * @param startedAt 宿主启动时间
 */
public record HostHealth(
    String sessionId,
    boolean roundActive,
    long rounds,
    long lastRoundMs,
    StepEvent.Usage lastUsage,
    Instant startedAt) {

  public HostHealth {
    if (lastUsage == null) {
      lastUsage = StepEvent.Usage.zero();
    }
  }
}
