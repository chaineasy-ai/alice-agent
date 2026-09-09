package org.cland.alice.core.agent.kernel.graph;

/** 门判定结果。 */
public record GateResult(boolean pass, String guardPort) {

  public static GateResult passed() {
    return new GateResult(true, null);
  }

  /** block：走 guardPort 指定的 guard 边（该 port 必须在 gate 节点出边中存在）。 */
  public static GateResult guard(String guardPort) {
    return new GateResult(false, guardPort);
  }
}
