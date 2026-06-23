package org.cland.alice.memory.wal;

import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checkpoint 管理器 — 负责在安全边界（Safe Point）触发 Checkpoint 的生成与保存。
 *
 * <p>安全边界定义：
 *
 * <ul>
 *   <li>每个 ReAct 循环结束时
 *   <li>收到用户新输入时
 *   <li>工具调用返回时
 *   <li>异常/错误捕获时
 *   <li>可配置的时间间隔触发
 * </ul>
 *
 * <p>Checkpoint 保存采用异步非阻塞方式，不阻塞主线执行。
 */
public final class CheckpointManager {

  private static final Logger log = LoggerFactory.getLogger(CheckpointManager.class);

  private final WalStore store;
  private final WalAppender appender;

  /** 当前 session 的 Checkpoint 状态追踪 */
  private volatile CheckpointState lastState;

  public CheckpointManager(WalStore store, WalAppender appender) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.appender = Objects.requireNonNull(appender, "appender must not be null");
    this.lastState = null;
  }

  // ============================================================
  // Checkpoint 触发
  // ============================================================

  /**
   * 在 ReAct 循环结束时触发 Checkpoint。
   *
   * @param sessionId 会话 ID
   * @param stateNode 当前状态节点
   * @param variables 变量快照
   * @param planSnapshot Plan 任务树快照（JSON）
   * @return 生成的 Checkpoint ID
   */
  public long onReActCycleEnd(
      String sessionId, String stateNode, Map<String, Object> variables, String planSnapshot) {

    return triggerCheckpoint(sessionId, stateNode, variables, planSnapshot);
  }

  /**
   * 在收到用户新输入时触发 Checkpoint。
   *
   * @param sessionId 会话 ID
   * @return 生成的 Checkpoint ID
   */
  public long onUserInput(String sessionId) {
    return triggerCheckpoint(sessionId, Checkpoint.NODE_START, Map.of("trigger", "user_input"), "");
  }

  /**
   * 在工具调用返回时触发 Checkpoint。
   *
   * @param sessionId 会话 ID
   * @param toolName 工具名
   * @param success 是否成功
   * @return 生成的 Checkpoint ID
   */
  public long onToolReturn(String sessionId, String toolName, boolean success) {
    return triggerCheckpoint(
        sessionId,
        Checkpoint.NODE_ACTING,
        Map.of("lastTool", toolName, "toolSuccess", success),
        "");
  }

  /**
   * 在异常/错误捕获时触发 Checkpoint。
   *
   * @param sessionId 会话 ID
   * @param errorNode 出错的节点
   * @param errorMsg 错误描述
   * @return 生成的 Checkpoint ID
   */
  public long onError(String sessionId, String errorNode, String errorMsg) {
    return triggerCheckpoint(
        sessionId, Checkpoint.NODE_ERROR, Map.of("errorNode", errorNode, "error", errorMsg), "");
  }

  // ============================================================
  // 内部触发逻辑
  // ============================================================

  /**
   * 触发 Checkpoint 生成的核心方法。
   *
   * <p>幂等性保证：如果同一 safe point 已保存过相同 last_applied_id，则跳过。
   *
   * @param sessionId 会话 ID
   * @param stateNode 状态节点
   * @param variables 变量快照
   * @param planSnapshot Plan 快照
   * @return 新 Checkpoint 的 ID（或已有 Checkpoint 的 ID）
   */
  private synchronized long triggerCheckpoint(
      String sessionId, String stateNode, Map<String, Object> variables, String planSnapshot) {

    // 获取当前最新消息 ID 作为 Last_Applied_ID
    long currentLastAppliedId = getLastMessageId(sessionId);

    // 幂等性检查：如果与上次保存的 last_applied_id 相同并且状态节点也相同，跳过
    // 不同状态节点（如 ERROR → FINISHED）即使 lastAppliedId 相同也要允许写入
    if (lastState != null
        && lastState.sessionId.equals(sessionId)
        && lastState.lastAppliedMessageId == currentLastAppliedId
        && lastState.stateNode != null
        && lastState.stateNode.equals(stateNode)) {
      log.trace(
          "[Checkpoint] Skipped (idempotent) session={} lastAppliedId={} node={}",
          sessionId,
          currentLastAppliedId,
          stateNode);
      return lastState.checkpointId;
    }

    var snapshot = new java.util.LinkedHashMap<>(variables);
    snapshot.put("checkpointTime", System.currentTimeMillis());
    snapshot.put("messageCount", appender.messageCount(sessionId));

    Checkpoint cp =
        new Checkpoint(
            0,
            sessionId,
            currentLastAppliedId,
            stateNode,
            Map.copyOf(snapshot),
            planSnapshot != null ? planSnapshot : "",
            System.currentTimeMillis());

    long cpId = store.saveCheckpoint(cp);
    this.lastState = new CheckpointState(sessionId, cpId, currentLastAppliedId, stateNode);

    log.info(
        "[Checkpoint] Saved session={} id={} node={} lastAppliedId={}",
        sessionId,
        cpId,
        stateNode,
        currentLastAppliedId);
    return cpId;
  }

  /** 获取某个 session 的最新消息 ID。 */
  private long getLastMessageId(String sessionId) {
    var msgs = store.getAllMessages(sessionId);
    if (msgs.isEmpty()) return 0;
    return msgs.stream().mapToLong(RawMessage::messageId).max().orElse(0);
  }

  // ============================================================
  // 查询
  // ============================================================

  /** 获取最新 Checkpoint。 */
  public java.util.Optional<Checkpoint> getLatestCheckpoint(String sessionId) {
    return store.getLatestCheckpoint(sessionId);
  }

  /** 获取最近一次保存的状态。 */
  public CheckpointState lastState() {
    return lastState;
  }

  /** 重置状态追踪（用于测试或 session 切换）。 */
  public void resetState() {
    this.lastState = null;
  }

  // ============================================================
  // 内部状态
  // ============================================================

  /** 内部追踪的上次 Checkpoint 状态（用于幂等性判断）。 */
  public record CheckpointState(
      String sessionId, long checkpointId, long lastAppliedMessageId, String stateNode) {
    public CheckpointState(String sessionId, long checkpointId, long lastAppliedMessageId) {
      this(sessionId, checkpointId, lastAppliedMessageId, null);
    }
  }
}
