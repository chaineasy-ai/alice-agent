package org.cland.alice.core.agent.kernel.graph;

import java.util.Map;

/** 效果请求（工具调用，语义化）。 */
public record ToolCallReq(String name, Map<String, Object> arguments) {

  public ToolCallReq {
    arguments = arguments != null ? Map.copyOf(arguments) : Map.of();
  }

  public static ToolCallReq of(String name) {
    return new ToolCallReq(name, Map.of());
  }

  public static ToolCallReq of(String name, Map<String, Object> arguments) {
    return new ToolCallReq(name, arguments);
  }
}
