package org.cland.alice.core.agent.kernel.graph;

/** 账本 goal 槽位条目（goal 图素材的落账形态）。 */
public record GoalRef(String id, String summary) {

  public static GoalRef of(String id, String summary) {
    return new GoalRef(id, summary);
  }
}
