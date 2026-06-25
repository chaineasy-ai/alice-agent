package org.cland.alice.core.agent.wal;

import java.util.Map;
import java.util.Objects;

/**
 * Checkpoint 快照实体 — 控制流的安全锚点。
 *
 * <p>对应设计文档中 Checkpoint 角色。在安全边界（Safe Point）触发， 冷冻当前控制流节点、Plan 任务树以及局部变量，同时更新
 * Last_Applied_Message_ID 指针。
 *
 * <p>恢复时：Checkpoint 提供底座（Base WAL），WAL 提供差量（Delta）， 通过重放 last_applied_message_id 之后的脏消息，将 Agent
 * 状态恢复到崩溃点。
 *
 * @param checkpointId 快照 ID（单调递增）
 * @param sessionId 所属会话 ID
 * @param lastAppliedMessageId 指针：最后一条已确认处理的消息 ID
 * @param stateNode 当前状态节点（如 PLANNING, TOOL_EXEC, VERIFYING, FINISHED 等）
 * @param variableSnapshot 变量快照（retry 计数、当前目标、活跃上下文等）
 * @param planSnapshot 序列化的 Plan 任务树快照（JSON 字符串）
 * @param createdAt 创建时间戳（毫秒）
 */
public record Checkpoint(
    long checkpointId,
    String sessionId,
    long lastAppliedMessageId,
    String stateNode,
    Map<String, Object> variableSnapshot,
    String planSnapshot,
    long createdAt) {

  /** 预定义状态节点常量 */
  public static final String NODE_START = "START";

  public static final String NODE_PERCEIVING = "PERCEIVING";
  public static final String NODE_PLANNING = "PLANNING";
  public static final String NODE_VERIFYING_PRE = "VERIFYING_PRE";
  public static final String NODE_VERIFYING_POST = "VERIFYING_POST";
  public static final String NODE_ACTING = "ACTING";
  public static final String NODE_OBSERVING = "OBSERVING";
  public static final String NODE_REFLECTING = "REFLECTING";
  public static final String NODE_REVISION = "REVISION";
  public static final String NODE_FINISHED = "FINISHED";
  public static final String NODE_ERROR = "ERROR";

  public Checkpoint {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(stateNode, "stateNode must not be null");
    if (lastAppliedMessageId < 0) {
      throw new IllegalArgumentException(
          "lastAppliedMessageId must be >= 0, got: " + lastAppliedMessageId);
    }
    if (createdAt <= 0) {
      createdAt = System.currentTimeMillis();
    }
    if (variableSnapshot == null) {
      variableSnapshot = Map.of();
    }
    if (planSnapshot == null) {
      planSnapshot = "";
    }
  }

  /** 返回一个新的 Checkpoint，其 lastAppliedMessageId 被推进到指定值。 用于恢复完成后生成新的快照。 */
  public Checkpoint withAdvancedPointer(long newLastAppliedMessageId) {
    return new Checkpoint(
        checkpointId + 1, // 如果用于新快照，ID 会递增
        sessionId,
        newLastAppliedMessageId,
        stateNode,
        variableSnapshot,
        planSnapshot,
        System.currentTimeMillis());
  }

  @Override
  public String toString() {
    return "Checkpoint{id=%d, session='%s', node=%s, lastAppliedId=%d}"
        .formatted(checkpointId, sessionId, stateNode, lastAppliedMessageId);
  }
}
