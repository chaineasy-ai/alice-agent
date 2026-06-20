package org.cland.alice.tool.gateway.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import org.cland.alice.tool.gateway.annotation.RiskLevel;
import org.cland.alice.tool.gateway.model.McpTool;

/**
 * 适配器 — 将 {@link McpTool} 转换为 {@link ToolMetadata} 以便存入 {@link
 * org.cland.alice.tool.gateway.ToolRegistry}。
 *
 * <p>MCP 工具没有 Java 方法可反射，因此在适配时构建一个 MethodHandle 闭包， invoke 时通过 {@link McpTool#invoke(Map)} 转发给对应的
 * MCP Server。
 *
 * <p>使用方式：
 *
 * <pre>{@code
 * ToolMetadata metadata = McpToolAdapter.toToolMetadata(mcpTool);
 * ToolRegistryHolder.INSTANCE.registry().register(metadata);
 * }</pre>
 */
public final class McpToolAdapter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private McpToolAdapter() {}

  /**
   * 将 McpTool 转换为 ToolMetadata。
   *
   * @param mcpTool MCP 工具描述
   * @return 可用于 ToolRegistry 注册的元数据
   */
  public static ToolMetadata toToolMetadata(McpTool mcpTool) {
    JsonNode schema = toJsonNode(mcpTool.inputSchema());
    MethodHandle handle = buildInvokeHandle(mcpTool);

    return ToolMetadata.builder()
        .name(mcpTool.qualifiedName())
        .description(mcpTool.description())
        .inputSchema(schema)
        .targetMethod(handle)
        .targetBean(null) // McpTool 作为被调者可使用 targetBean 或直接 bind
        .riskLevel(RiskLevel.MEDIUM)
        .returnType(String.class) // McpTool.invoke() 返回 String
        .paramNames(extractParamNames(schema))
        .build();
  }

  /**
   * 批量转换多个 MCP 工具。
   *
   * @param mcpTools MCP 工具列表
   * @return ToolMetadata 数组
   */
  public static ToolMetadata[] toToolMetadataArray(java.util.Collection<McpTool> mcpTools) {
    return mcpTools.stream().map(McpToolAdapter::toToolMetadata).toArray(ToolMetadata[]::new);
  }

  // ========== Internal ==========

  /**
   * 构建 MethodHandle，签名 (McpTool, Map) → String。 以 mcpTool 实例为 targetBean，ToolMetadata.invoke(Map)
   * 会自动绑定 receiver。
   */
  private static MethodHandle buildInvokeHandle(McpTool mcpTool) {
    try {
      MethodHandle target =
          MethodHandles.lookup()
              .findVirtual(McpTool.class, "invoke", MethodType.methodType(String.class, Map.class));

      // bind mcpTool 实例到 receiver 位，剩下 (Map) → String
      return target.bindTo(mcpTool);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new RuntimeException(
          "Failed to build MCP invoke handle for: " + mcpTool.qualifiedName(), e);
    }
  }

  private static JsonNode toJsonNode(Map<String, Object> inputSchema) {
    if (inputSchema == null || inputSchema.isEmpty()) {
      return MAPPER.createObjectNode();
    }
    try {
      return MAPPER.valueToTree(inputSchema);
    } catch (Exception e) {
      return MAPPER.createObjectNode();
    }
  }

  private static String[] extractParamNames(JsonNode schema) {
    if (schema == null || !schema.has("properties")) return new String[0];
    JsonNode props = schema.get("properties");
    if (!props.isObject()) return new String[0];
    String[] names = new String[props.size()];
    int i = 0;
    var it = props.fields();
    while (it.hasNext()) {
      names[i++] = it.next().getKey();
    }
    return names;
  }
}
