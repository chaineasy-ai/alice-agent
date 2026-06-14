package org.cland.alice.memory.dreaming;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.cland.alice.memory.wal.Checkpoint;
import org.cland.alice.memory.wal.RawMessage;
import org.cland.alice.memory.wal.WalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WAL 会话读取守卫 — 在在线 ReAct 平面执行 DREAMING 会话的 READ-lock 强制。
 *
 * <p>包装 {@link WalStore} 实例，在每次读取操作前检查会话状态。 如果会话处于 DREAMING 状态，则拒绝读取，抛出 {@link
 * IllegalStateException}。 这防止了在线 ReAct 循环从正在被 Dreaming 处理的会话中读取不一致的数据。
 */
public final class WalSessionReadGuard implements WalStore {

  private static final Logger log = LoggerFactory.getLogger(WalSessionReadGuard.class);

  private final WalStore delegate;
  private final SessionStateManager stateManager;

  /**
   * @param delegate 被包装的 WalStore 实例
   * @param stateManager 用于检查会话状态的 SessionStateManager
   */
  public WalSessionReadGuard(WalStore delegate, SessionStateManager stateManager) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.stateManager = Objects.requireNonNull(stateManager, "stateManager must not be null");
  }

  // ============================================================
  // 读取操作 — 应用 READ-lock
  // ============================================================

  @Override
  public Optional<RawMessage> getMessage(long messageId) {
    // 无法从 ID 确定 session — 直接委托（只读操作，不做过滤）
    return delegate.getMessage(messageId);
  }

  @Override
  public List<RawMessage> getMessagesAfter(String sessionId, long afterId, int limit) {
    assertNotDreaming(sessionId);
    return delegate.getMessagesAfter(sessionId, afterId, limit);
  }

  @Override
  public List<RawMessage> getAllMessages(String sessionId) {
    assertNotDreaming(sessionId);
    return delegate.getAllMessages(sessionId);
  }

  @Override
  public int messageCount(String sessionId) {
    assertNotDreaming(sessionId);
    return delegate.messageCount(sessionId);
  }

  // ============================================================
  // Checkpoint 操作 — 同样受 READ-lock 保护
  // ============================================================

  @Override
  public Optional<Checkpoint> getLatestCheckpoint(String sessionId) {
    assertNotDreaming(sessionId);
    return delegate.getLatestCheckpoint(sessionId);
  }

  @Override
  public List<Checkpoint> getCheckpointHistory(String sessionId, int limit) {
    assertNotDreaming(sessionId);
    return delegate.getCheckpointHistory(sessionId, limit);
  }

  @Override
  public int checkpointCount(String sessionId) {
    assertNotDreaming(sessionId);
    return delegate.checkpointCount(sessionId);
  }

  // ============================================================
  // 写操作 — 直接委托（写操作在 DREAMING 状态仍被允许用于状态回退）
  // ============================================================

  @Override
  public long appendMessage(RawMessage message) {
    return delegate.appendMessage(message);
  }

  @Override
  public long appendMessages(List<RawMessage> messageList) {
    return delegate.appendMessages(messageList);
  }

  @Override
  public int deleteMessagesUpTo(String sessionId, long upToId) {
    return delegate.deleteMessagesUpTo(sessionId, upToId);
  }

  @Override
  public long saveCheckpoint(Checkpoint checkpoint) {
    return delegate.saveCheckpoint(checkpoint);
  }

  @Override
  public int deleteCheckpointsUpTo(String sessionId, long upToCheckpointId) {
    return delegate.deleteCheckpointsUpTo(sessionId, upToCheckpointId);
  }

  @Override
  public void clearSession(String sessionId) {
    delegate.clearSession(sessionId);
  }

  @Override
  public void clearAll() {
    delegate.clearAll();
  }

  // ============================================================
  // Session 管理
  // ============================================================

  @Override
  public List<String> activeSessionIds() {
    return delegate.activeSessionIds();
  }

  // ============================================================
  // READ-lock 检查
  // ============================================================

  /** 检查会话是否处于 DREAMING 状态，如果是则抛出异常。 */
  private void assertNotDreaming(String sessionId) {
    SessionState state = stateManager.getState(sessionId);
    if (state == SessionState.DREAMING) {
      throw new IllegalStateException(
          "Session '"
              + sessionId
              + "' is in DREAMING state and is read-locked. "
              + "Online ReAct must not read from a session being dreamed.");
    }
  }
}
