package org.cland.alice.memory.wal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.cland.alice.memory.agent.AgentSession;
import org.cland.alice.memory.wal.RecoveryEngine.RecoveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WAL 会话 — 将 WAL + Checkpoint + Recovery 集成到 Agent 记忆系统的门面类。
 *
 * <p>封装 {@link WalStore}、{@link WalAppender}、{@link CheckpointManager}、 {@link
 * RecoveryEngine}、{@link PromptMelter} 五个组件， 提供统一的 WAL 双轨制操作入口。
 *
 * <p>与现有 {@link AgentSession} 的关系：
 *
 * <ul>
 *   <li>WalSession 补充 AgentSession 的短期记忆，增加崩溃恢复能力
 *   <li>AgentSession.getShortTerm() 可通过 WAL 全量回放获得
 *   <li>AgentSession.persist() 可通过 WalAppender 实现
 * </ul>
 */
public final class WalSession {

  private static final Logger log = LoggerFactory.getLogger(WalSession.class);

  private final WalStore store;
  private final WalAppender appender;
  private final CheckpointManager checkpointManager;
  private final RecoveryEngine recoveryEngine;
  private final PromptMelter promptMelter;

  /** 当前恢复结果（重启后设置） */
  private RecoveryResult lastRecoveryResult;

  public WalSession() {
    this(new InMemoryWalStore());
  }

  public WalSession(WalStore store) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.appender = new WalAppender(store);
    this.checkpointManager = new CheckpointManager(store, appender);
    this.recoveryEngine = new RecoveryEngine(store, checkpointManager);
    this.promptMelter = new PromptMelter(store);
    this.lastRecoveryResult = null;
  }

  // ============================================================
  // WAL 追加操作 （替代 AgentSession.persist）
  // ============================================================

  /** 追加 system 消息 */
  public long system(String sessionId, String content) {
    return appender.appendSystem(sessionId, content);
  }

  /** 追加 user 消息 */
  public long user(String sessionId, String content) {
    return appender.appendUser(sessionId, content);
  }

  /** 追加 assistant 回复 */
  public long assistant(String sessionId, String content) {
    return appender.appendAssistant(sessionId, content);
  }

  /** 追加 assistant 工具调用 */
  public long assistantToolCalls(String sessionId, List<ToolCall> toolCalls) {
    return appender.appendAssistantToolCalls(sessionId, toolCalls);
  }

  /** 追加 tool 执行结果 */
  public long toolResult(String sessionId, String toolCallId, String content) {
    return appender.appendToolResult(sessionId, toolCallId, content);
  }

  // ============================================================
  // Checkpoint 操作
  // ============================================================

  /** 在 ReAct 循环结束时触发 Checkpoint */
  public long checkpointOnReActEnd(
      String sessionId, String stateNode, Map<String, Object> variables, String planSnapshot) {
    return checkpointManager.onReActCycleEnd(sessionId, stateNode, variables, planSnapshot);
  }

  /** 收到用户输入时触发 Checkpoint */
  public long checkpointOnUserInput(String sessionId) {
    return checkpointManager.onUserInput(sessionId);
  }

  /** 工具调用返回时触发 Checkpoint */
  public long checkpointOnToolReturn(String sessionId, String toolName, boolean success) {
    return checkpointManager.onToolReturn(sessionId, toolName, success);
  }

  /** 异常时触发 Checkpoint */
  public long checkpointOnError(String sessionId, String errorNode, String errorMsg) {
    return checkpointManager.onError(sessionId, errorNode, errorMsg);
  }

  // ============================================================
  // 恢复
  // ============================================================

  /**
   * 执行恢复流程（重启后调用）。
   *
   * @param sessionId 会话 ID
   * @return 恢复结果
   */
  public RecoveryResult recover(String sessionId) {
    RecoveryResult result = recoveryEngine.recover(sessionId);
    this.lastRecoveryResult = result;
    log.info("[WalSession] Recovery result: {} (session={})", result.summary(), sessionId);
    return result;
  }

  /** 获取最近一次恢复结果。 */
  public Optional<RecoveryResult> lastRecoveryResult() {
    return Optional.ofNullable(lastRecoveryResult);
  }

  // ============================================================
  // Prompt 熔炼
  // ============================================================

  /**
   * 熔炼三段式 Prompt。
   *
   * @param sessionId 会话 ID
   * @param staticTrunk 静态主干（System Prompt + SOP + Tool Schemas）
   * @return 熔炼后的 Prompt
   */
  public PromptMelter.MeltedPrompt melt(String sessionId, String staticTrunk) {
    return promptMelter.melt(sessionId, staticTrunk);
  }

  // ============================================================
  // 消息查询
  // ============================================================

  /** 获取会话所有消息。 */
  public List<RawMessage> getAllMessages(String sessionId) {
    return appender.getAllMessages(sessionId);
  }

  /** 获取消息数量。 */
  public int messageCount(String sessionId) {
    return appender.messageCount(sessionId);
  }

  /** 校验消息链路完整性。 */
  public WalAppender.LinkageValidation validateLinkage(String sessionId) {
    return appender.validateLinkage(sessionId);
  }

  /** 获取最新 Checkpoint。 */
  public Optional<Checkpoint> getLatestCheckpoint(String sessionId) {
    return checkpointManager.getLatestCheckpoint(sessionId);
  }

  // ============================================================
  // 生命周期
  // ============================================================

  /** 清除指定会话的所有数据。 */
  public void clearSession(String sessionId) {
    store.clearSession(sessionId);
    checkpointManager.resetState();
    log.debug("[WalSession] Cleared session={}", sessionId);
  }

  /** 清除所有数据。 */
  public void clearAll() {
    store.clearAll();
    checkpointManager.resetState();
    lastRecoveryResult = null;
    log.debug("[WalSession] Cleared all sessions");
  }
}
