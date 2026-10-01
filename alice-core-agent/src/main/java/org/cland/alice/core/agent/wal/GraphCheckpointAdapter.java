package org.cland.alice.core.agent.wal;

import java.util.Map;
import java.util.Optional;
import org.cland.alice.core.agent.kernel.graph.SafePoint;

/**
 * 图内核 ↔ WAL/Checkpoint 适配器（§3.2 ⑥）— 把 {@link SafePoint}（图内核安全点）与既有 {@link Checkpoint} （WAL
 * 双轨的持久化载体）互转，复用 `WalStore`/`CheckpointManager`，零新存储体系。
 *
 * <p>映射：{@code stateNode = safePoint.pos()}；{@code lastAppliedMessageId =
 * safePoint.steps()}（占位，恢复判定只看 {@code stateNode}+{@link SafePoint}）；{@code
 * variableSnapshot[graphSafePoint] = SafePoint}； {@code schemaVersion = GRAPH_SCHEMA_VERSION}。恢复只读
 * {@code stateNode} 与 {@code SafePoint}，占位字段不渗入恢复。
 */
public final class GraphCheckpointAdapter {

  /** variableSnapshot 中承载整个 SafePoint 的键。 */
  public static final String KEY_SAFE_POINT = "graphSafePoint";

  /** 图内核 Checkpoint schema 版本。 */
  public static final int GRAPH_SCHEMA_VERSION = 1;

  private GraphCheckpointAdapter() {}

  /** SafePoint → Checkpoint（sessionId 由调用方提供）。 */
  public static Checkpoint toCheckpoint(String sessionId, SafePoint safePoint) {
    if (safePoint == null) {
      throw new IllegalArgumentException("safePoint must not be null");
    }
    return new Checkpoint(
        0,
        sessionId,
        safePoint.steps(),
        safePoint.pos(),
        Map.of(KEY_SAFE_POINT, safePoint),
        "",
        0,
        GRAPH_SCHEMA_VERSION);
  }

  /** Checkpoint → SafePoint（非图内核 Checkpoint 返回 empty）。 */
  public static Optional<SafePoint> fromCheckpoint(Checkpoint checkpoint) {
    if (checkpoint == null) {
      return Optional.empty();
    }
    Object value = checkpoint.variableSnapshot().get(KEY_SAFE_POINT);
    return value instanceof SafePoint sp ? Optional.of(sp) : Optional.empty();
  }

  /** 该 Checkpoint 是否由图内核写入。 */
  public static boolean isGraphCheckpoint(Checkpoint checkpoint) {
    return checkpoint != null && checkpoint.variableSnapshot().containsKey(KEY_SAFE_POINT);
  }
}
