package org.cland.alice.memory.wal;

import java.util.Map;
import java.util.Objects;

/**
 * 工具调用 (Tool Call) 子实体 — 对应 OpenAI Chat Completions 的 assistant.tool_calls 数组元素。
 *
 * <p>当 Agent 决定调用工具时，assistant 消息的 content 设为 null， 通过 tool_calls 下发调用指令。工具执行结果通过 {@link
 * RawMessage#toolCallId()} 配对。
 *
 * @param id 调用唯一标识（用于消息链路配对，如 "call_abc123"）
 * @param type 固定值 "function"
 * @param function 函数调用详情：name + arguments（JSON 字符串）
 */
public record ToolCall(String id, String type, Function function) {

  private static final String VALID_TYPE = "function";

  public ToolCall {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(function, "function must not be null");
    if (!VALID_TYPE.equals(type)) {
      throw new IllegalArgumentException("type must be '" + VALID_TYPE + "', got: " + type);
    }
  }

  /**
   * 函数调用详情。
   *
   * @param name 工具/函数名称
   * @param arguments 参数 JSON 字符串（如 "{\"location\":\"Beijing\"}"）
   */
  public record Function(String name, String arguments) {

    public Function {
      Objects.requireNonNull(name, "name must not be null");
      Objects.requireNonNull(arguments, "arguments must not be null");
    }
  }

  // ========== 工厂方法 ==========

  /** 快速创建 ToolCall */
  public static ToolCall of(String id, String toolName, Map<String, Object> arguments) {
    String jsonArgs = arguments == null || arguments.isEmpty() ? "{}" : toJsonString(arguments);
    return new ToolCall(id, VALID_TYPE, new Function(toolName, jsonArgs));
  }

  /** 使用原始 JSON 字符串创建 ToolCall */
  public static ToolCall ofJson(String id, String toolName, String jsonArguments) {
    return new ToolCall(id, VALID_TYPE, new Function(toolName, jsonArguments));
  }

  @Override
  public String toString() {
    return "ToolCall{id='%s', function='%s'}".formatted(id, function.name());
  }

  // ========== 内部工具 ==========

  /** 极简 JSON 序列化，避免引入外部依赖 */
  private static String toJsonString(Map<String, Object> map) {
    var sb = new StringBuilder("{");
    var it = map.entrySet().iterator();
    while (it.hasNext()) {
      var entry = it.next();
      sb.append('"').append(escape(entry.getKey())).append('"').append(':');
      Object value = entry.getValue();
      if (value instanceof String s) {
        sb.append('"').append(escape(s)).append('"');
      } else if (value instanceof Number || value instanceof Boolean) {
        sb.append(value);
      } else {
        sb.append('"').append(escape(String.valueOf(value))).append('"');
      }
      if (it.hasNext()) sb.append(',');
    }
    sb.append('}');
    return sb.toString();
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
