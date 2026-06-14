/*
 * WalCompactor — WAL 后台压缩与清理引擎
 *
 * 功能：
 *   1. 基于 Checkpoint 的 last_applied_message_id 标记可压缩的旧消息
 *   2. 后台线程定时执行压缩清理（可配置间隔）
 *   3. 支持手动触发压缩
 *   4. 支持配置保留的消息条数（保留最近 N 条，无论是否已确认）
 *
 * 安全保证：
 *   - 压缩仅删除 last_applied_message_id 之前的消息
 *   - 未确认的消息（last_applied_message_id 之后）永远不会被删除
 *   - 压缩操作是幂等的
 */
package org.cland.alice.memory.wal;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WAL 压缩与清理引擎。
 *
 * <p>后台线程定期扫描所有活跃 session，基于 Checkpoint 的 {@code lastAppliedMessageId} 删除已确认的旧 WAL 消息，释放磁盘空间。
 *
 * <p>配置项：
 *
 * <ul>
 *   <li>{@code intervalSec} — 压缩检查间隔（默认 300s = 5 分钟）
 *   <li>{@code minRetentionCount} — 每个 session 最少保留的消息数（默认 50）
 *   <li>{@code enabled} — 是否启用后台自动压缩（默认 true）
 * </ul>
 */
public final class WalCompactor {

  private static final Logger log = LoggerFactory.getLogger(WalCompactor.class);

  /** 默认压缩检查间隔：5 分钟 */
  public static final long DEFAULT_INTERVAL_SEC = 300;

  /** 默认每个 session 最少保留消息数 */
  public static final int DEFAULT_MIN_RETENTION_COUNT = 50;

  private final WalStore store;
  private final ScheduledExecutorService scheduler;
  private final long intervalSec;
  private final int minRetentionCount;
  private final boolean enabled;

  private ScheduledFuture<?> future;
  private volatile boolean running;
  private long lastRunTimestamp;
  private long totalCompactedMessages;

  /**
   * 创建 WalCompactor。
   *
   * @param store WalStore 实例
   * @param scheduler 调度器（由调用方管理生命周期）
   * @param intervalSec 压缩检查间隔（秒）
   * @param minRetentionCount 每个 session 最少保留消息数
   * @param enabled 是否启用自动压缩
   */
  public WalCompactor(
      WalStore store,
      ScheduledExecutorService scheduler,
      long intervalSec,
      int minRetentionCount,
      boolean enabled) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    this.intervalSec = intervalSec;
    this.minRetentionCount = minRetentionCount;
    this.enabled = enabled;
    this.totalCompactedMessages = 0;
  }

  /**
   * 使用默认配置创建 WalCompactor。
   *
   * @param store WalStore 实例
   * @param scheduler 调度器
   */
  public WalCompactor(WalStore store, ScheduledExecutorService scheduler) {
    this(store, scheduler, DEFAULT_INTERVAL_SEC, DEFAULT_MIN_RETENTION_COUNT, true);
  }

  /** 启动后台压缩调度。 */
  public synchronized void start() {
    if (running) {
      log.debug("[Compactor] Already running");
      return;
    }
    if (!enabled) {
      log.info("[Compactor] Auto-compaction is disabled");
      return;
    }
    running = true;
    future =
        scheduler.scheduleWithFixedDelay(
            this::compactAll, intervalSec, intervalSec, TimeUnit.SECONDS);
    log.info("[Compactor] Started (interval={}s, minRetention={})", intervalSec, minRetentionCount);
  }

  /** 停止后台压缩调度。 */
  public synchronized void stop() {
    if (future != null && !future.isCancelled()) {
      future.cancel(false);
    }
    running = false;
    log.info("[Compactor] Stopped. Total compacted: {}", totalCompactedMessages);
  }

  /**
   * 手动触发一次对所有活跃 session 的压缩。
   *
   * @return 本次压缩删除的消息总数
   */
  public int compactAll() {
    int totalDeleted = 0;
    List<String> sessionIds = store.activeSessionIds();
    for (String sessionId : sessionIds) {
      totalDeleted += compactSession(sessionId);
    }
    lastRunTimestamp = System.currentTimeMillis();
    if (totalDeleted > 0) {
      totalCompactedMessages += totalDeleted;
      log.info(
          "[Compactor] Compacted {} messages across {} sessions", totalDeleted, sessionIds.size());
    }
    return totalDeleted;
  }

  /**
   * 对指定 session 执行压缩。
   *
   * <p>策略：
   *
   * <ol>
   *   <li>获取最新 Checkpoint 的 {@code lastAppliedMessageId}
   *   <li>如果无 Checkpoint，跳过该 session（不压缩未确认的消息）
   *   <li>该 session 的消息列表中，找到 <= lastAppliedId 的最大 ID（精确截断）
   *   <li>minRetentionCount 保护：确保保留最近至少 N 条消息
   *   <li>调用 {@link WalStore#deleteMessagesUpTo} 删除
   * </ol>
   *
   * <p>此方法通过遍历会话内消息列表找到精确的截断点，避免了全局 ID 序列与 不同会话 ID 区间重叠导致的问题。
   *
   * @param sessionId 会话 ID
   * @return 删除的消息数
   */
  public int compactSession(String sessionId) {
    var checkpoint = store.getLatestCheckpoint(sessionId);
    if (checkpoint.isEmpty()) {
      log.trace("[Compactor] No checkpoint for session={}, skipping", sessionId);
      return 0;
    }

    long lastAppliedId = checkpoint.get().lastAppliedMessageId();
    var allMessages = store.getAllMessages(sessionId);
    int msgCount = allMessages.size();

    // 如果消息总数少于 minRetentionCount，跳过
    if (msgCount <= minRetentionCount) {
      log.trace(
          "[Compactor] Session={} msgCount={} <= minRetention={}, skipping",
          sessionId,
          msgCount,
          minRetentionCount);
      return 0;
    }

    // 在 session 实际消息列表中查找 <= lastAppliedId 的最大 ID
    long maxDeleteId = 0;
    for (var msg : allMessages) {
      if (msg.messageId() <= lastAppliedId) {
        maxDeleteId = msg.messageId();
      } else {
        break; // 消息已按 ID 升序排列
      }
    }

    if (maxDeleteId <= 0) return 0;

    // minRetentionCount 保护：确保保留最近至少 N 条
    long retentionBoundary = allMessages.get(msgCount - 1).messageId();
    if (msgCount > minRetentionCount) {
      retentionBoundary = allMessages.get(msgCount - 1 - minRetentionCount).messageId();
    }
    if (maxDeleteId > retentionBoundary) {
      maxDeleteId = retentionBoundary;
    }

    if (maxDeleteId <= 0) return 0;

    int deleted = store.deleteMessagesUpTo(sessionId, maxDeleteId);
    if (deleted > 0) {
      log.debug(
          "[Compactor] Session={} deleted={} messages (upToId={})",
          sessionId,
          deleted,
          maxDeleteId);
    }
    return deleted;
  }

  // ========== 状态查询 ==========

  /** 是否正在运行。 */
  public boolean isRunning() {
    return running;
  }

  /** 获取最近一次压缩运行的时间戳。 */
  public long getLastRunTimestamp() {
    return lastRunTimestamp;
  }

  /** 获取累计压缩的消息总数。 */
  public long getTotalCompactedMessages() {
    return totalCompactedMessages;
  }

  /** 获取压缩检查间隔（秒）。 */
  public long getIntervalSec() {
    return intervalSec;
  }

  /** 获取每个 session 最少保留的消息数。 */
  public int getMinRetentionCount() {
    return minRetentionCount;
  }
}
