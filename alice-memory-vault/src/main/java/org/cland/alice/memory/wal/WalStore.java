package org.cland.alice.memory.wal;

import java.util.List;
import java.util.Optional;

/**
 * WAL 存储层接口 — 管理 RawMessage 和 Checkpoint 的持久化。
 *
 * <p>RawMessage 以 Append-Only 方式写入，Checkpoint 采用同 session 覆盖策略。
 *
 * <p>对应设计文档中的 "WAL 存储层" 和 "Checkpoint 存储层"。
 */
public interface WalStore {

  // ============================================================
  // RawMessage 操作
  // ============================================================

  /**
   * Append-Only 写入一条 RawMessage。
   *
   * @param message 待写入的消息
   * @return 分配的消息 ID
   */
  long appendMessage(RawMessage message);

  /**
   * 批量 Append 写入多条消息（性能优化）。
   *
   * @param messages 待写入的消息列表
   * @return 最后一条消息的 ID
   */
  long appendMessages(List<RawMessage> messages);

  /**
   * 按 messageId 查询单条消息。
   *
   * @param messageId 消息 ID
   * @return 消息（若存在）
   */
  Optional<RawMessage> getMessage(long messageId);

  /**
   * 查询某个 session 中 ID 大于 {@code afterId} 的消息（差量读取）。
   *
   * @param sessionId 会话 ID
   * @param afterId 起始 ID（不包含）
   * @param limit 最大返回条数
   * @return 消息列表（按 ID 升序）
   */
  List<RawMessage> getMessagesAfter(String sessionId, long afterId, int limit);

  /**
   * 查询某个 session 的全部消息（全量回放）。
   *
   * @param sessionId 会话 ID
   * @return 消息列表（按 ID 升序）
   */
  List<RawMessage> getAllMessages(String sessionId);

  /**
   * 删除某个 session 中 ID 小于等于指定值的消息（WAL 压缩清理）。
   *
   * @param sessionId 会话 ID
   * @param upToId 上限 ID（包含）
   * @return 删除的消息数
   */
  int deleteMessagesUpTo(String sessionId, long upToId);

  /**
   * 获取某个 session 的消息总数。
   *
   * @param sessionId 会话 ID
   * @return 消息数
   */
  int messageCount(String sessionId);

  // ============================================================
  // Checkpoint 操作
  // ============================================================

  /**
   * 保存 Checkpoint（同 session 覆盖旧快照）。
   *
   * @param checkpoint 待保存的快照
   * @return 分配的 checkpoint ID
   */
  long saveCheckpoint(Checkpoint checkpoint);

  /**
   * 获取某个 session 的最新 Checkpoint。
   *
   * @param sessionId 会话 ID
   * @return 最新的 Checkpoint（若存在）
   */
  Optional<Checkpoint> getLatestCheckpoint(String sessionId);

  /**
   * 获取某个 session 的 Checkpoint 历史。
   *
   * @param sessionId 会话 ID
   * @param limit 最大返回条数
   * @return Checkpoint 列表（按创建时间降序）
   */
  List<Checkpoint> getCheckpointHistory(String sessionId, int limit);

  /**
   * 删除某个 session 中 ID 小于等于指定值的 Checkpoint（历史清理）。
   *
   * @param sessionId 会话 ID
   * @param upToCheckpointId 上限 checkpoint ID（包含）
   * @return 删除的 checkpoint 数
   */
  int deleteCheckpointsUpTo(String sessionId, long upToCheckpointId);

  /**
   * 获取某个 session 的 Checkpoint 数量。
   *
   * @param sessionId 会话 ID
   * @return checkpoint 数
   */
  int checkpointCount(String sessionId);

  // ============================================================
  // Session 管理
  // ============================================================

  /** 清除某个 session 的所有数据（消息 + Checkpoint）。 */
  void clearSession(String sessionId);

  /** 清除所有数据。 */
  void clearAll();

  /** 获取当前管理的 session ID 列表。 */
  List<String> activeSessionIds();
}
