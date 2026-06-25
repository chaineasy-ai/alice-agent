package org.cland.alice.memory.dreaming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.cland.alice.core.agent.wal.RawMessage;
import org.cland.alice.core.agent.wal.WalStore;
import org.cland.alice.memory.core.Knowledge;
import org.cland.alice.memory.core.Step;
import org.cland.alice.memory.dreaming.DreamingSession.DreamingOutcome;
import org.cland.alice.memory.dreaming.PromptMelter.EpisodicSummary;
import org.cland.alice.memory.vault.EpisodicVault;
import org.cland.alice.memory.vault.ProceduralVault;
import org.cland.alice.memory.vault.SemanticVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dreaming Engine 编排器 — 管理完整的离线处理管道。
 *
 * <p>接收触发事件，选择待处理的 WalSession，按顺序调用 PromptMelter → ConflictResolver → Crystallizer 三个阶段，
 * 并管理会话状态转换（US3 增强后支持状态锁定）。
 *
 * <p>触发机制（US4 增强后支持后台轮询）： - process(sessionId): 按需处理单个会话 - processAll(): 处理所有待处理会话 -
 * startBackgroundTriggers(): 启动定时/阈值后台触发器
 */
public final class DreamingEngine {

  private static final Logger log = LoggerFactory.getLogger(DreamingEngine.class);

  private static final String DREAMING_FACTS_COLLECTION = "_dreaming_facts";

  private final WalStore walStore;
  private final EpisodicVault episodicVault;
  private final SemanticVault semanticVault;
  private final ProceduralVault proceduralVault;
  private final DreamingTriggerConfig triggerConfig;

  // Session state management (populated in US3)
  private SessionStateManager sessionStateManager;

  // Internal components
  private final PromptMelter promptMelter;
  private final Crystallizer crystallizer;
  // ConflictResolver is populated in US2 — we use a simple fact-writing approach for US1 MVP
  private ConflictResolver conflictResolver;

  // Background trigger infrastructure (US4)
  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> pollingFuture;
  private final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());
  private final CopyOnWriteArrayList<DreamingSession> recentSessions = new CopyOnWriteArrayList<>();

  // Concurrency control
  private Semaphore concurrencySemaphore;

  /**
   * @param walStore WAL 存储层
   * @param episodicVault 情景记忆 Vault
   * @param semanticVault 语义记忆 Vault
   * @param proceduralVault 程序记忆 Vault
   * @param triggerConfig 触发器配置
   */
  public DreamingEngine(
      WalStore walStore,
      EpisodicVault episodicVault,
      SemanticVault semanticVault,
      ProceduralVault proceduralVault,
      DreamingTriggerConfig triggerConfig) {
    this.walStore = Objects.requireNonNull(walStore, "walStore must not be null");
    this.episodicVault = Objects.requireNonNull(episodicVault, "episodicVault must not be null");
    this.semanticVault = Objects.requireNonNull(semanticVault, "semanticVault must not be null");
    this.proceduralVault =
        Objects.requireNonNull(proceduralVault, "proceduralVault must not be null");
    this.triggerConfig = Objects.requireNonNull(triggerConfig, "triggerConfig must not be null");
    this.promptMelter = new PromptMelter(walStore);
    this.crystallizer = new Crystallizer(proceduralVault);
    this.concurrencySemaphore = new Semaphore(triggerConfig.maxConcurrency());
  }

  // ============================================================
  // Core Processing
  // ============================================================

  /**
   * 处理单个 WalSession 的完整管道。
   *
   * @param sessionId 要处理的会话 ID
   * @return DreamingSession 执行记录
   */
  public DreamingSession process(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    log.info("[DreamingEngine] Starting process for session={}", sessionId);

    // Phase 0: Session state locking (US3 capability — if SessionStateManager is set)
    if (sessionStateManager != null) {
      if (!sessionStateManager.tryLockForDreaming(sessionId)) {
        log.info(
            "[DreamingEngine] Session={} not dreamable (locked or wrong state), skipping",
            sessionId);
        DreamingSession skipped =
            new DreamingSession(
                sessionId,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                0L,
                null,
                0,
                0,
                DreamingOutcome.SKIPPED);
        recentSessions.add(0, skipped); // newest first
        trimRecentSessions();
        return skipped;
      }
    }

    long startTime = System.currentTimeMillis();
    int conflictCount = 0;
    int patternsCrystallized = 0;
    String episodicSummaryId = null;

    try {
      // Acquire concurrency permit
      concurrencySemaphore.acquire();
      try {
        // Phase 1: Read WAL messages
        List<RawMessage> messages = walStore.getAllMessages(sessionId);
        log.debug("[DreamingEngine] Session={}: {} messages loaded", sessionId, messages.size());

        // Phase 2: PromptMelter — produce episodic summary
        EpisodicSummary summary = promptMelter.melt(sessionId);
        Step summaryStep =
            Step.builder()
                .stepId("dreaming-summary-" + UUID.randomUUID().toString().substring(0, 8))
                .action("dreaming_summary")
                .input("sessionId=" + sessionId)
                .output(summary.summaryText())
                .timestamp(System.currentTimeMillis())
                .success(true)
                .importance(0.8)
                .build();
        episodicVault.appendStep(sessionId, summaryStep);
        episodicSummaryId = summary.summaryId();
        log.debug(
            "[DreamingEngine] Session={}: Episodic summary written, id={}",
            sessionId,
            episodicSummaryId);

        // Phase 3: Fact extraction → SemanticVault (ConflictResolver used if available)
        List<DreamingFact> facts = extractFacts(messages, sessionId);
        if (conflictResolver != null) {
          var result = conflictResolver.resolve(facts, sessionId);
          conflictCount = result.deprecatedFacts();
          log.debug(
              "[DreamingEngine] Session={}: ConflictResolver processed {} facts, {} deprecated",
              sessionId,
              result.factsProcessed(),
              result.deprecatedFacts());
        } else {
          // Fallback: write facts directly to SemanticVault
          for (DreamingFact fact : facts) {
            if (fact.confidence() >= 0.5) {
              Knowledge knowledge =
                  Knowledge.builder()
                      .knowledgeId(fact.factId())
                      .content(fact.content())
                      .source("dreaming:" + sessionId)
                      .collection(DREAMING_FACTS_COLLECTION)
                      .createdAt(fact.timestamp())
                      .build();
              semanticVault.store(DREAMING_FACTS_COLLECTION, knowledge);
            }
          }
          log.debug(
              "[DreamingEngine] Session={}: {} facts written directly to SemanticVault",
              sessionId,
              facts.size());
        }

        // Phase 4: Crystallizer — produce SOPs
        patternsCrystallized = crystallizer.crystallize(messages, sessionId);

        // Phase 5: Transition to ARCHIVED (US3)
        if (sessionStateManager != null) {
          sessionStateManager.transition(sessionId, SessionState.DREAMING, SessionState.ARCHIVED);
        }

        long endTime = System.currentTimeMillis();
        long durationMs = endTime - startTime;
        DreamingSession result =
            new DreamingSession(
                sessionId,
                startTime,
                endTime,
                durationMs,
                episodicSummaryId,
                conflictCount,
                patternsCrystallized,
                DreamingOutcome.SUCCESS);
        recentSessions.add(0, result); // newest first
        trimRecentSessions();

        log.info(
            "[DreamingEngine] Session={} completed: {}ms, {} conflicts, {} patterns",
            sessionId,
            durationMs,
            conflictCount,
            patternsCrystallized);
        return result;

      } finally {
        concurrencySemaphore.release();
      }
    } catch (Exception e) {
      long endTime = System.currentTimeMillis();
      long durationMs = endTime - startTime;
      log.error("[DreamingEngine] Session={} failed: {}", sessionId, e.getMessage(), e);

      // Revert session state on failure (US3)
      if (sessionStateManager != null) {
        try {
          sessionStateManager.transition(sessionId, SessionState.DREAMING, SessionState.COMPLETED);
        } catch (Exception revertEx) {
          log.warn("[DreamingEngine] Failed to revert state for session={}", sessionId, revertEx);
        }
      }

      DreamingSession failure =
          new DreamingSession(
              sessionId, startTime, endTime, durationMs, null, 0, 0, DreamingOutcome.FAILURE);
      recentSessions.add(0, failure);
      trimRecentSessions();
      return failure;
    }
  }

  // ============================================================
  // Batch Processing
  // ============================================================

  /**
   * 处理所有待处理的 COMPLETED/CRASHED 会话。 跳过已在 DREAMING 或 ARCHIVED 状态的会话。
   *
   * @return DreamingSession 记录列表
   */
  public List<DreamingSession> processAll() {
    List<DreamingSession> results = new ArrayList<>();
    List<String> allSessions = walStore.activeSessionIds();

    for (String sessionId : allSessions) {
      if (sessionStateManager != null) {
        if (!sessionStateManager.isDreamable(sessionId)) {
          log.trace("[DreamingEngine] Skipping session={} (not dreamable)", sessionId);
          continue;
        }
      }
      DreamingSession result = process(sessionId);
      results.add(result);
    }

    log.info("[DreamingEngine] processAll completed: {} sessions processed", results.size());
    return Collections.unmodifiableList(results);
  }

  // ============================================================
  // Trigger Mechanisms
  // ============================================================

  /** 启动后台轮询定时器，用于空闲检测和 WAL 阈值触发器。 使用 triggerConfig.pollingIntervalMs 作为轮询间隔。 */
  public void startBackgroundTriggers() {
    if (scheduler != null && !scheduler.isShutdown()) {
      log.warn("[DreamingEngine] Background triggers already running");
      return;
    }

    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "dreaming-engine-polling");
              t.setDaemon(true);
              return t;
            });

    pollingFuture =
        scheduler.scheduleAtFixedRate(
            this::pollingTick, 0, triggerConfig.pollingIntervalMs(), TimeUnit.MILLISECONDS);

    log.info(
        "[DreamingEngine] Background triggers started: interval={}ms",
        triggerConfig.pollingIntervalMs());
  }

  /** 停止后台轮询。进行中的 Dreaming 周期将继续完成。 */
  public void stopBackgroundTriggers() {
    if (pollingFuture != null && !pollingFuture.isCancelled()) {
      pollingFuture.cancel(false);
    }
    if (scheduler != null && !scheduler.isShutdown()) {
      scheduler.shutdown();
      try {
        scheduler.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("[DreamingEngine] Interrupted while stopping background triggers");
      }
    }
    log.info("[DreamingEngine] Background triggers stopped");
  }

  /** 检查后台触发器是否正在运行。 */
  public boolean isBackgroundRunning() {
    return scheduler != null && !scheduler.isShutdown() && !scheduler.isTerminated();
  }

  /** 由在线 ReAct 循环调用以标记系统活动时间。 影响空闲检测：只有 lastActivityTime 超过 idleCooldownMs 时才触发 Dreaming。 */
  public void setLastActivityTime(long timestampMs) {
    this.lastActivityTime.set(timestampMs);
  }

  /** 获取当前待处理会话数（COMPLETED + CRASHED 状态的会话数）。 如果 SessionStateManager 未设置，则返回活跃会话数作为估算。 */
  public int pendingSessionCount() {
    if (sessionStateManager != null) {
      int count = 0;
      for (String sid : walStore.activeSessionIds()) {
        if (sessionStateManager.isDreamable(sid)) count++;
      }
      return count;
    }
    return walStore.activeSessionIds().size();
  }

  // ============================================================
  // Configuration
  // ============================================================

  /** 获取当前触发配置的不可变副本。 */
  public DreamingTriggerConfig getTriggerConfig() {
    return triggerConfig;
  }

  // ============================================================
  // Status
  // ============================================================

  /**
   * 获取最近的 DreamingSession 记录（最新在前）。
   *
   * @param limit 最大返回数量
   * @return 最近的处理记录
   */
  public List<DreamingSession> recentSessions(int limit) {
    if (limit <= 0) return List.of();
    int end = Math.min(limit, recentSessions.size());
    return Collections.unmodifiableList(recentSessions.subList(0, end));
  }

  // ============================================================
  // Internal: Session State Manager Injection (for US3)
  // ============================================================

  /** 设置 SessionStateManager 以启用状态锁定和生命周期管理（US3 集成）。 允许在构建后注入，以支持增量功能交付。 */
  public void setSessionStateManager(SessionStateManager sessionStateManager) {
    this.sessionStateManager =
        Objects.requireNonNull(sessionStateManager, "sessionStateManager must not be null");
    log.info("[DreamingEngine] SessionStateManager injected");
  }

  /** 设置 ConflictResolver 以启用冲突解决（US2 集成）。 允许在构建后注入，以支持增量功能交付。 */
  public void setConflictResolver(ConflictResolver conflictResolver) {
    this.conflictResolver =
        Objects.requireNonNull(conflictResolver, "conflictResolver must not be null");
    log.info("[DreamingEngine] ConflictResolver injected");
  }

  // ============================================================
  // Internal Helpers
  // ============================================================

  /** 轮询触发器的执行体。 */
  private void pollingTick() {
    try {
      long now = System.currentTimeMillis();
      long idle = now - lastActivityTime.get();

      // 空闲检测：系统空闲超过 idleCooldownMs
      if (idle >= triggerConfig.idleCooldownMs()) {
        log.debug(
            "[DreamingEngine] Idle trigger: idle={}ms >= cooldown={}ms",
            idle,
            triggerConfig.idleCooldownMs());
        processAll();
        return;
      }

      // WAL 阈值检测：未处理条目数超过阈值
      int totalEntries = 0;
      for (String sid : walStore.activeSessionIds()) {
        totalEntries += walStore.messageCount(sid);
      }
      if (totalEntries >= triggerConfig.walThresholdEntries()) {
        log.debug(
            "[DreamingEngine] WAL threshold trigger: {} entries >= threshold={}",
            totalEntries,
            triggerConfig.walThresholdEntries());
        processAll();
      }
    } catch (Exception e) {
      log.error("[DreamingEngine] Polling tick failed", e);
    }
  }

  /**
   * 从 WAL 消息中提取 DreamingFact 条目。 使用简单的启发式方法： - system 消息 → 高置信度 (0.9) - assistant 消息 → 中等置信度 (0.7)
   * - user 消息 → 低置信度 (0.5) - tool 消息 → 跳过（它们是执行结果，不包含陈述性知识）
   */
  static List<DreamingFact> extractFacts(List<RawMessage> messages, String sessionId) {
    List<DreamingFact> facts = new ArrayList<>();
    for (RawMessage msg : messages) {
      double confidence;
      switch (msg.role()) {
        case "system" -> confidence = 0.9;
        case "assistant" -> confidence = 0.7;
        case "user" -> confidence = 0.5;
        default -> {
          continue; // skip tool messages
        }
      }

      if (msg.content() == null || msg.content().isBlank()) {
        continue;
      }

      // 简单句子分割：按句号、问号、感叹号分割
      String[] sentences = msg.content().split("[。！？.!?]");
      for (String sentence : sentences) {
        String trimmed = sentence.trim();
        if (trimmed.length() > 10) { // 只保留有意义的句子
          String factId = "fact-" + sessionId + "-" + msg.messageId() + "-" + facts.size();
          DreamingFact fact =
              new DreamingFact(
                  factId, trimmed, sessionId, msg.messageId(), msg.timestamp(), confidence);
          facts.add(fact);
        }
      }
    }
    return List.copyOf(facts);
  }

  /** 保持 recentSessions 列表长度不超过 100。 */
  private void trimRecentSessions() {
    while (recentSessions.size() > 100) {
      recentSessions.remove(recentSessions.size() - 1);
    }
  }
}
