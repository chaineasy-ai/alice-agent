package org.cland.alice.memory.dreaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 触发器配置 — 控制 Dreaming Engine 的触发行为和资源限制。
 *
 * <p>字段均为编译时常量默认值，可通过紧凑构造函数覆盖。
 *
 * @param idleCooldownMs 系统空闲多少毫秒后自动触发 Dreaming（默认 60000，最小 1000）
 * @param walThresholdEntries 未处理的 WAL 条目数上限，超过则强制 Dreaming（默认 500，最小 10）
 * @param walThresholdBytes 未处理的 WAL 字节数上限，超过则强制 Dreaming（默认 10 MB）
 * @param pollingIntervalMs 后台轮询间隔（默认 30000，最小 1000）
 * @param maxConcurrency 最大并发 Dreaming 周期数（默认 1，最小 1）
 * @param maxStepsPerCycle 每个 Dreaming 周期最大处理的 WAL 条目数（默认 1000，最小 10）
 */
public record DreamingTriggerConfig(
    long idleCooldownMs,
    int walThresholdEntries,
    long walThresholdBytes,
    long pollingIntervalMs,
    int maxConcurrency,
    int maxStepsPerCycle) {

  private static final Logger log = LoggerFactory.getLogger(DreamingTriggerConfig.class);

  /** 默认 idleCooldownMs */
  public static final long DEFAULT_IDLE_COOLDOWN_MS = 60_000L;

  /** 默认 walThresholdEntries */
  public static final int DEFAULT_WAL_THRESHOLD_ENTRIES = 500;

  /** 默认 walThresholdBytes (10 MB) */
  public static final long DEFAULT_WAL_THRESHOLD_BYTES = 10_485_760L;

  /** 默认 pollingIntervalMs */
  public static final long DEFAULT_POLLING_INTERVAL_MS = 30_000L;

  /** 默认 maxConcurrency */
  public static final int DEFAULT_MAX_CONCURRENCY = 1;

  /** 默认 maxStepsPerCycle */
  public static final int DEFAULT_MAX_STEPS_PER_CYCLE = 1000;

  /** 使用全部默认值创建配置。 */
  public static DreamingTriggerConfig defaults() {
    return new DreamingTriggerConfig(
        DEFAULT_IDLE_COOLDOWN_MS,
        DEFAULT_WAL_THRESHOLD_ENTRIES,
        DEFAULT_WAL_THRESHOLD_BYTES,
        DEFAULT_POLLING_INTERVAL_MS,
        DEFAULT_MAX_CONCURRENCY,
        DEFAULT_MAX_STEPS_PER_CYCLE);
  }

  public DreamingTriggerConfig {
    if (idleCooldownMs < 1000) {
      throw new IllegalArgumentException("idleCooldownMs must be >= 1000, got: " + idleCooldownMs);
    }
    if (walThresholdEntries < 10) {
      throw new IllegalArgumentException(
          "walThresholdEntries must be >= 10, got: " + walThresholdEntries);
    }
    if (walThresholdBytes < 1024) {
      throw new IllegalArgumentException(
          "walThresholdBytes must be >= 1024, got: " + walThresholdBytes);
    }
    if (pollingIntervalMs < 1000) {
      throw new IllegalArgumentException(
          "pollingIntervalMs must be >= 1000, got: " + pollingIntervalMs);
    }
    if (maxConcurrency < 1) {
      throw new IllegalArgumentException("maxConcurrency must be >= 1, got: " + maxConcurrency);
    }
    if (maxStepsPerCycle < 10) {
      throw new IllegalArgumentException(
          "maxStepsPerCycle must be >= 10, got: " + maxStepsPerCycle);
    }
    log.debug(
        "DreamingTriggerConfig created: idleCooldownMs={}, walThresholdEntries={}, "
            + "walThresholdBytes={}, pollingIntervalMs={}, maxConcurrency={}, maxStepsPerCycle={}",
        idleCooldownMs,
        walThresholdEntries,
        walThresholdBytes,
        pollingIntervalMs,
        maxConcurrency,
        maxStepsPerCycle);
  }
}
