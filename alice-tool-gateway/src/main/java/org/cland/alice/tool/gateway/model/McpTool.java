package org.cland.alice.tool.gateway.model;

import java.util.Map;
import java.util.Objects;

/**
 * MCP 工具描述 — 由 MCP Server 通过 {@code tools/list} 发现的远程工具。
 *
 * <p>包含工具元数据（名称、描述、输入 schema）和执行能力（{@link #invoke(Map)} 返回文本结果）， 以及所属的 {@code serverId} 用于生命周期管理。
 *
 * <p>env-adapter 从 MCP Server 发现工具后创建此对象， 并通过 {@link
 * org.cland.alice.tool.gateway.metadata.McpToolAdapter} 转换为 {@link
 * org.cland.alice.tool.gateway.metadata.ToolMetadata} 注册到全局 {@link
 * org.cland.alice.tool.gateway.ToolRegistry}。
 *
 * <p>创建方式：
 *
 * <pre>{@code
 * // env-adapter side
 * McpTool tool = McpTool.builder()
 *     .serverId("filesystem")
 *     .toolName("read")
 *     .description("Read file contents")
 *     .inputSchema(schema)
 *     .invoker((params) -> {
 *         ToolResult r = client.callTool("read", params).get(30, SECONDS);
 *         return r.isError() ? "Error: " + r.error() : r.text();
 *     })
 *     .build();
 *
 * // 转换并注册
 * ToolRegistryHolder.INSTANCE.registry()
 *     .register(McpToolAdapter.toToolMetadata(tool));
 * }</pre>
 */
public final class McpTool {

  private final String serverId;
  private final String toolName;
  private final String description;
  private final Map<String, Object> inputSchema;
  private final ToolInvoker invoker;

  private McpTool(Builder builder) {
    this.serverId = Objects.requireNonNull(builder.serverId, "serverId");
    this.toolName = Objects.requireNonNull(builder.toolName, "toolName");
    this.description = builder.description;
    this.inputSchema = builder.inputSchema != null ? Map.copyOf(builder.inputSchema) : Map.of();
    this.invoker = Objects.requireNonNull(builder.invoker, "invoker");
  }

  /** 工具全名（含 serverId 前缀），用作 ToolRegistry 中的注册键。 */
  public String qualifiedName() {
    return serverId + ":" + toolName;
  }

  public String serverId() {
    return serverId;
  }

  public String toolName() {
    return toolName;
  }

  public String description() {
    return description;
  }

  public Map<String, Object> inputSchema() {
    return inputSchema;
  }

  /**
   * 执行此 MCP 工具。
   *
   * @param params 工具参数（由 LLM 生成，与 inputSchema 匹配）
   * @return 工具执行的文本结果
   */
  public String invoke(Map<String, Object> params) {
    try {
      return invoker.invoke(params != null ? params : Map.of());
    } catch (Exception e) {
      return "MCP tool [" + qualifiedName() + "] failed: " + e.getMessage();
    }
  }

  /** 工具调用函数接口 — env-adapter 实现此接口来调用对应的 MCP Server。 */
  @FunctionalInterface
  public interface ToolInvoker {
    /**
     * 发起一次 MCP 工具调用。
     *
     * @param params 工具参数
     * @return 工具执行结果的文本描述
     * @throws Exception 调用过程中的任何异常
     */
    String invoke(Map<String, Object> params) throws Exception;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String serverId;
    private String toolName;
    private String description;
    private Map<String, Object> inputSchema;
    private ToolInvoker invoker;

    private Builder() {}

    public Builder serverId(String serverId) {
      this.serverId = serverId;
      return this;
    }

    public Builder toolName(String toolName) {
      this.toolName = toolName;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder inputSchema(Map<String, Object> inputSchema) {
      this.inputSchema = inputSchema;
      return this;
    }

    public Builder invoker(ToolInvoker invoker) {
      this.invoker = invoker;
      return this;
    }

    public McpTool build() {
      return new McpTool(this);
    }
  }

  @Override
  public String toString() {
    return "McpTool{serverId='" + serverId + "', toolName='" + toolName + "'}";
  }
}
