package org.cland.alice.core.agent.wal;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于内存的 {@link WalStore} 实现。
 *
 * <p>用于开发和测试阶段。RawMessage 存储在 {@link CopyOnWriteArrayList} 中保证线程安全， Checkpoint 存储在
 * ConcurrentHashMap 中实现 session-level 覆盖。
 *
 * <p>生产环境应替换为 PostgreSQL / Redis 实现。
 */
public final class InMemoryWalStore implements WalStore {

  private static final Logger log = LoggerFactory.getLogger(InMemoryWalStore.class);

  /** 全局消息 ID 生成器 */
  private final AtomicLong messageIdSeq = new AtomicLong(1);

  /** sessionId → 消息列表 */
  private final ConcurrentMap<String, CopyOnWriteArrayList<RawMessage>> messages =
      new ConcurrentHashMap<>();

  /** sessionId → checkpoint（同 session 覆盖） */
  private final ConcurrentMap<String, Checkpoint> checkpoints = new ConcurrentHashMap<>();

  /** checkpoint ID 生成器 */
  private final AtomicLong checkpointIdSeq = new AtomicLong(1);

  // ============================================================
  // RawMessage 操作
  // ============================================================

  @Override
  public long appendMessage(RawMessage message) {
    // 如果消息已有 ID > 0 则使用，否则分配新 ID
    long id = message.messageId() > 0 ? message.messageId() : messageIdSeq.getAndIncrement();
    RawMessage stored =
        new RawMessage(
            id,
            message.sessionId(),
            message.role(),
            message.content(),
            message.toolCalls(),
            message.toolCallId(),
            message.name(),
            message.timestamp() > 0 ? message.timestamp() : System.currentTimeMillis(),
            message.metadata());

    messages.computeIfAbsent(message.sessionId(), k -> new CopyOnWriteArrayList<>()).add(stored);
    log.trace(
        "[WAL] appended message id={} session={} role={}", id, message.sessionId(), message.role());
    return id;
  }

  @Override
  public long appendMessages(List<RawMessage> messageList) {
    long lastId = 0;
    for (RawMessage msg : messageList) {
      lastId = appendMessage(msg);
    }
    return lastId;
  }

  @Override
  public Optional<RawMessage> getMessage(long messageId) {
    return messages.values().stream()
        .flatMap(List::stream)
        .filter(m -> m.messageId() == messageId)
        .findFirst();
  }

  @Override
  public List<RawMessage> getMessagesAfter(String sessionId, long afterId, int limit) {
    var list = messages.get(sessionId);
    if (list == null) return List.of();

    return list.stream()
        .filter(m -> m.messageId() > afterId)
        .sorted(Comparator.comparingLong(RawMessage::messageId))
        .limit(limit > 0 ? limit : Long.MAX_VALUE)
        .collect(Collectors.toUnmodifiableList());
  }

  @Override
  public List<RawMessage> getAllMessages(String sessionId) {
    var list = messages.get(sessionId);
    if (list == null) return List.of();
    return List.copyOf(list);
  }

  @Override
  public int deleteMessagesUpTo(String sessionId, long upToId) {
    var list = messages.get(sessionId);
    if (list == null) return 0;

    var toKeep = list.stream().filter(m -> m.messageId() > upToId).collect(Collectors.toList());

    int removed = list.size() - toKeep.size();
    if (removed > 0) {
      messages.put(sessionId, new CopyOnWriteArrayList<>(toKeep));
      log.debug("[WAL] cleaned {} messages up to id={} for session={}", removed, upToId, sessionId);
    }
    return removed;
  }

  @Override
  public int messageCount(String sessionId) {
    var list = messages.get(sessionId);
    return list != null ? list.size() : 0;
  }

  // ============================================================
  // Checkpoint 操作
  // ============================================================

  @Override
  public long saveCheckpoint(Checkpoint checkpoint) {
    long id =
        checkpoint.checkpointId() > 0
            ? checkpoint.checkpointId()
            : checkpointIdSeq.getAndIncrement();

    Checkpoint stored =
        new Checkpoint(
            id,
            checkpoint.sessionId(),
            checkpoint.lastAppliedMessageId(),
            checkpoint.stateNode(),
            checkpoint.variableSnapshot(),
            checkpoint.planSnapshot(),
            checkpoint.createdAt() > 0 ? checkpoint.createdAt() : System.currentTimeMillis());

    checkpoints.put(checkpoint.sessionId(), stored);
    log.debug(
        "[Checkpoint] saved id={} session={} node={} lastAppliedId={}",
        id,
        checkpoint.sessionId(),
        checkpoint.stateNode(),
        checkpoint.lastAppliedMessageId());
    return id;
  }

  @Override
  public Optional<Checkpoint> getLatestCheckpoint(String sessionId) {
    return Optional.ofNullable(checkpoints.get(sessionId));
  }

  @Override
  public List<Checkpoint> getCheckpointHistory(String sessionId, int limit) {
    Checkpoint cp = checkpoints.get(sessionId);
    if (cp == null) return List.of();
    return List.of(cp); // InMemory 只保留最新一个
  }

  @Override
  public int deleteCheckpointsUpTo(String sessionId, long upToCheckpointId) {
    Checkpoint cp = checkpoints.get(sessionId);
    if (cp == null) return 0;
    if (cp.checkpointId() <= upToCheckpointId) {
      checkpoints.remove(sessionId);
      return 1;
    }
    return 0;
  }

  @Override
  public int checkpointCount(String sessionId) {
    return checkpoints.containsKey(sessionId) ? 1 : 0;
  }

  // ============================================================
  // Session 管理
  // ============================================================

  @Override
  public void clearSession(String sessionId) {
    messages.remove(sessionId);
    checkpoints.remove(sessionId);
    log.debug("[WAL] cleared session={}", sessionId);
  }

  @Override
  public void clearAll() {
    messages.clear();
    checkpoints.clear();
    log.debug("[WAL] cleared all sessions");
  }

  @Override
  public List<String> activeSessionIds() {
    return List.copyOf(messages.keySet());
  }
}
