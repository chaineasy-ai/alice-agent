package org.cland.alice.core.agent.kernel.graph;

/**
 * 复合节点展开规格（§5.2.1 composite 语义）。
 *
 * @param inner 内部图（entry = inner.start）
 * @param exitBindings 内部 TERMINAL 节点 id → 外层出口（外层节点 id，或 {@link SessionGraph#SESSION_END}）
 */
public record CompositeSpec(SessionGraph inner, java.util.Map<String, String> exitBindings) {

  public CompositeSpec {
    exitBindings = exitBindings != null ? java.util.Map.copyOf(exitBindings) : java.util.Map.of();
    if (inner == null) {
      throw new IllegalArgumentException("CompositeSpec requires an inner graph");
    }
  }

  /** 查询内部终点的外层出口。 */
  public String exitTarget(String innerTerminalId) {
    return exitBindings.get(innerTerminalId);
  }
}
