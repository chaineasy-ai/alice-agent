/*
 * Alice Agent — 基础运行指标快照（#173 M3-2 可观测）
 *
 * 供协议面 {@code /api/v1/metrics} 与看板/看门狗读取。
 * 只做「汇总快照」：轮次/耗时/用量（来自 HostHealth）+ 进程级 JVM 指标（堆/线程/运行时长）。
 */
package org.cland.alice.runtime;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;

/**
 * 基础运行指标快照。
 *
 * @param sessionId 会话标识
 * @param uptimeMs 宿主启动至今毫秒数
 * @param roundActive 当前是否有轮次在执行
 * @param rounds 已执行轮数（累计）
 * @param lastRoundMs 最近一轮耗时（毫秒；未执行过为 0）
 * @param tokensLastRound 最近一轮 token 用量（未执行过为 0）
 * @param heapUsedBytes JVM 堆已用字节
 * @param heapMaxBytes JVM 堆上限字节（-1 表示未定义）
 * @param threads 当前活跃线程数
 */
public record Metrics(
    String sessionId,
    long uptimeMs,
    boolean roundActive,
    long rounds,
    long lastRoundMs,
    long tokensLastRound,
    long heapUsedBytes,
    long heapMaxBytes,
    int threads) {

  /**
   * 从宿主健康快照 + 进程 JVM 指标汇总一份指标快照。
   *
   * @param health 宿主健康快照（非 null）
   * @return 指标快照
   */
  public static Metrics collect(HostHealth health) {
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    long uptimeMs = Math.max(0, Duration.between(health.startedAt(), Instant.now()).toMillis());
    long used = memory.getHeapMemoryUsage().getUsed();
    long max = memory.getHeapMemoryUsage().getMax();
    return new Metrics(
        health.sessionId(),
        uptimeMs,
        health.roundActive(),
        health.rounds(),
        health.lastRoundMs(),
        health.lastUsage().totalTokens(),
        used,
        max,
        threads.getThreadCount());
  }
}
