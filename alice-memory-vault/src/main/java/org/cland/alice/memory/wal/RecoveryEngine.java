package org.cland.alice.memory.wal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recovery Engine — 灾难恢复与状态重放引擎。
 *
 * <p>当 Agent 重启时，执行以下恢复流程：
 *
 * <ol>
 *   <li>加载最新 Checkpoint（获取最后的安全底线）
 *   <li>根据 last_applied_message_id 读取脏 WAL 段（Msg_ID > last_applied_id）
 *   <li>按序重放脏消息，修复内存状态
 *   <li>恢复完成后生成新 Checkpoint（CP_B），推进 last_applied_id
 *   <li>返回恢复结果供 Planner 继续执行
 * </ol>
 *
 * <p>对应设计文档中的 "灾难恢复状态机" (Crash Recovery State Machine)。
 */
public final class RecoveryEngine {

  private static final Logger log = LoggerFactory.getLogger(RecoveryEngine.class);

  private final WalStore store;
  private final CheckpointManager checkpointManager;

  public RecoveryEngine(WalStore store, CheckpointManager checkpointManager) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.checkpointManager =
        Objects.requireNonNull(checkpointManager, "checkpointManager must not be null");
  }

  /**
   * 执行完整恢复流程。
   *
   * @param sessionId 会话 ID
   * @return 恢复结果
   */
  public RecoveryResult recover(String sessionId) {
    log.info("[Recovery] Starting recovery for session={}", sessionId);

    // Step 1: 加载最新 Checkpoint
    var maybeCp = store.getLatestCheckpoint(sessionId);
    if (maybeCp.isEmpty()) {
      log.warn("[Recovery] No checkpoint found for session={}, performing full replay", sessionId);
      return fullReplay(sessionId);
    }

    Checkpoint cp = maybeCp.get();
    log.info(
        "[Recovery] Found checkpoint: id={}, node={}, lastAppliedId={}",
        cp.checkpointId(),
        cp.stateNode(),
        cp.lastAppliedMessageId());

    // Step 2: 读取脏 WAL 段
    List<RawMessage> dirtyMessages =
        store.getMessagesAfter(sessionId, cp.lastAppliedMessageId(), 0);

    if (dirtyMessages.isEmpty()) {
      log.info("[Recovery] No dirty WAL segments, checkpoint is up-to-date");

      // 直接使用 Checkpoint 状态
      Checkpoint freshCp =
          new Checkpoint(
              0,
              sessionId,
              cp.lastAppliedMessageId(),
              cp.stateNode(),
              cp.variableSnapshot(),
              cp.planSnapshot(),
              System.currentTimeMillis());
      long newCpId = store.saveCheckpoint(freshCp);
      checkpointManager.resetState();

      return new RecoveryResult(
          RecoveryStatus.CLEAN_RECOVERY,
          cp.checkpointId(),
          newCpId,
          cp.lastAppliedMessageId(),
          cp.stateNode(),
          cp.variableSnapshot(),
          List.of(),
          "Checkpoint is up-to-date, no replay needed");
    }

    // Step 3 & 4: 重放脏消息
    log.info("[Recovery] Found {} dirty messages to replay", dirtyMessages.size());
    ReplayResult replayResult = replayDirtyMessages(dirtyMessages, cp);

    // Step 5: 生成新 Checkpoint
    var newVariables = new java.util.LinkedHashMap<String, Object>(replayResult.variables());
    newVariables.put("recoveredAt", System.currentTimeMillis());
    newVariables.put("replayedCount", dirtyMessages.size());

    Checkpoint newCp =
        new Checkpoint(
            0,
            sessionId,
            replayResult.lastAppliedMessageId(),
            replayResult.stateNode(),
            Map.copyOf(newVariables),
            cp.planSnapshot(),
            System.currentTimeMillis());

    long newCpId = store.saveCheckpoint(newCp);
    checkpointManager.resetState();

    log.info(
        "[Recovery] Recovery complete for session={}, newCheckpointId={}, node={}",
        sessionId,
        newCpId,
        replayResult.stateNode());

    return new RecoveryResult(
        RecoveryStatus.REPLAYED_RECOVERY,
        cp.checkpointId(),
        newCpId,
        replayResult.lastAppliedMessageId(),
        replayResult.stateNode(),
        Map.copyOf(newVariables),
        dirtyMessages,
        "Replayed " + dirtyMessages.size() + " messages, state restored");
  }

  // ============================================================
  // 全量回放（无 Checkpoint 时的降级）
  // ============================================================

  private RecoveryResult fullReplay(String sessionId) {
    List<RawMessage> allMessages = store.getAllMessages(sessionId);

    if (allMessages.isEmpty()) {
      log.info("[Recovery] No messages at all for session={}, fresh start", sessionId);
      return new RecoveryResult(
          RecoveryStatus.FRESH_START,
          -1,
          -1,
          0,
          Checkpoint.NODE_START,
          Map.of("started", "fresh"),
          List.of(),
          "Fresh session, no previous state");
    }

    // 全量消息作为"脏"消息重放
    ReplayResult replayResult = replayDirtyMessages(allMessages, null);

    long lastId = allMessages.stream().mapToLong(RawMessage::messageId).max().orElse(0);

    Checkpoint newCp =
        new Checkpoint(
            0,
            sessionId,
            lastId,
            replayResult.stateNode(),
            replayResult.variables(),
            "",
            System.currentTimeMillis());
    long newCpId = store.saveCheckpoint(newCp);
    checkpointManager.resetState();

    return new RecoveryResult(
        RecoveryStatus.FULL_REPLAY,
        -1,
        newCpId,
        lastId,
        replayResult.stateNode(),
        replayResult.variables(),
        allMessages,
        "Full replay of " + allMessages.size() + " messages");
  }

  // ============================================================
  // 脏消息重放逻辑
  // ============================================================

  /**
   * 重放脏消息，在内存中恢复状态。
   *
   * <p>遍历每条脏消息，模拟其在运行时对状态的影响：
   *
   * <ul>
   *   <li>user 消息 → 重置当前目标
   *   <li>assistant 消息（带 tool_calls）→ 标记待处理工具
   *   <li>tool 消息 → 标记工具完成
   *   <li>assistant 消息（纯文本）→ 更新当前回复
   * </ul>
   *
   * @param dirtyMessages 脏消息列表（已排序）
   * @param baseCp 基础 Checkpoint（可能为 null）
   * @return 重放后的状态
   */
  private ReplayResult replayDirtyMessages(List<RawMessage> dirtyMessages, Checkpoint baseCp) {
    String stateNode = baseCp != null ? baseCp.stateNode() : Checkpoint.NODE_START;
    var variables = new java.util.LinkedHashMap<String, Object>();
    if (baseCp != null && baseCp.variableSnapshot() != null) {
      variables.putAll(baseCp.variableSnapshot());
    }
    var pendingToolCalls = new java.util.LinkedHashMap<String, String>();
    long lastAppliedId = baseCp != null ? baseCp.lastAppliedMessageId() : 0;

    for (RawMessage msg : dirtyMessages) {
      lastAppliedId = msg.messageId();

      switch (msg.role()) {
        case "user" -> {
          stateNode = Checkpoint.NODE_PERCEIVING;
          variables.put("lastUserInput", msg.content());
          variables.put("userInputTime", msg.timestamp());
        }
        case "assistant" -> {
          if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
            // Assistant 发起工具调用
            stateNode = Checkpoint.NODE_ACTING;
            for (ToolCall tc : msg.toolCalls()) {
              pendingToolCalls.put(tc.id(), tc.function().name());
            }
            variables.put("pendingToolCalls", String.join(",", pendingToolCalls.keySet()));
            variables.put("lastToolCallCount", pendingToolCalls.size());
          } else {
            // Assistant 纯文本回复
            stateNode = Checkpoint.NODE_REFLECTING;
            variables.put("lastAssistantContent", msg.content());
            if (pendingToolCalls.isEmpty()) {
              // 无待处理工具，推进到下一阶段
              stateNode = Checkpoint.NODE_REFLECTING;
            }
          }
        }
        case "tool" -> {
          // 工具执行结果返回
          String toolCallId = msg.toolCallId();
          if (toolCallId != null && pendingToolCalls.containsKey(toolCallId)) {
            String toolName = pendingToolCalls.remove(toolCallId);
            variables.put("lastToolResult", msg.content());
            variables.put("lastToolName", toolName);
            variables.put("toolResultTime", msg.timestamp());
          }
          if (pendingToolCalls.isEmpty()) {
            stateNode = Checkpoint.NODE_OBSERVING;
          }
        }
        case "system" -> {
          variables.put("systemMessage", msg.content());
        }
        default -> log.warn("[Recovery] Unknown role in replay: {}", msg.role());
      }

      // 记录重放进度
      variables.put("lastReplayedMessageId", msg.messageId());
    }

    // 如果有未完成的工具调用，状态设置为 ACTING
    if (!pendingToolCalls.isEmpty()) {
      stateNode = Checkpoint.NODE_ACTING;
      variables.put("unresolvedToolCalls", List.copyOf(pendingToolCalls.keySet()));
    }

    return new ReplayResult(lastAppliedId, stateNode, Map.copyOf(variables));
  }

  // ============================================================
  // 结果类型
  // ============================================================

  /** 恢复状态枚举。 */
  public enum RecoveryStatus {
    /** 全新会话，无历史数据 */
    FRESH_START,
    /** Checkpoint 已是最新，无需重放 */
    CLEAN_RECOVERY,
    /** 重放脏消息后恢复 */
    REPLAYED_RECOVERY,
    /** 无 Checkpoint，全量回放 */
    FULL_REPLAY
  }

  /**
   * 恢复结果。
   *
   * @param status 恢复状态
   * @param oldCheckpointId 旧 Checkpoint ID（-1 表示无）
   * @param newCheckpointId 新 Checkpoint ID
   * @param lastAppliedId 最后应用的消息 ID
   * @param recoveredNode 恢复后的状态节点
   * @param recoveredVariables 恢复后的变量快照
   * @param replayedMessages 重放的消息列表
   * @param summary 恢复摘要
   */
  public record RecoveryResult(
      RecoveryStatus status,
      long oldCheckpointId,
      long newCheckpointId,
      long lastAppliedId,
      String recoveredNode,
      Map<String, Object> recoveredVariables,
      List<RawMessage> replayedMessages,
      String summary) {

    public boolean isRecovered() {
      return status == RecoveryStatus.CLEAN_RECOVERY
          || status == RecoveryStatus.REPLAYED_RECOVERY
          || status == RecoveryStatus.FULL_REPLAY;
    }
  }

  /** 内部重放结果。 */
  private record ReplayResult(
      long lastAppliedMessageId, String stateNode, Map<String, Object> variables) {}
}
