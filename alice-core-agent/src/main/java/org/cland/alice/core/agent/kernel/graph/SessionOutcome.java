package org.cland.alice.core.agent.kernel.graph;

import java.util.List;
import java.util.Map;

/** 会话执行结果（R0 解释器产出）。 */
public record SessionOutcome(
    Status status, String message, int steps, List<String> trace, Map<String, Object> artifacts) {

  public enum Status {
    /** 会话正常闭环（仅经最外层会话级 terminal 到达）。 */
    FINISHED,

    /** 会话在安全点被取消（取消信号生效；剩余链路不再执行、不产生副作用）。 */
    CANCELLED,

    /** 结构性错误 / 注解预算耗尽。 */
    FAILED
  }

  public SessionOutcome {
    trace = trace != null ? List.copyOf(trace) : List.of();
    artifacts = artifacts != null ? Map.copyOf(artifacts) : Map.of();
  }

  /** 会话产物（如最终回答 answer）。 */
  public String answer() {
    Object a = artifacts.get("answer");
    return a != null ? a.toString() : "";
  }
}
