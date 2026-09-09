package org.cland.alice.core.agent.kernel.graph;

/** 效果执行结果（observation 回流）。 */
public record EffectOutcome(boolean success, String observation) {

  public static EffectOutcome ok(String observation) {
    return new EffectOutcome(true, observation);
  }

  public static EffectOutcome fail(String reason) {
    return new EffectOutcome(false, reason);
  }
}
