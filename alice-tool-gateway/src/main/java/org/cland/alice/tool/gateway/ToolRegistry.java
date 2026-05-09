package org.cland.alice.tool.gateway;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.cland.alice.tool.gateway.metadata.ToolMetadata;

/**
 * 工具注册中心 — Agent 能力的统一目录。
 *
 * <p>对应设计文档类图中 {@code ToolRegistry} 的角色，负责：
 *
 * <ul>
 *   <li>存储所有已注册工具的 {@link ToolMetadata} 元数据
 *   <li>按名称查找工具
 *   <li>导出工具列表供 Planner 生成 function calling schema
 * </ul>
 *
 * <p>设计上对 alice-core-agent 无业务依赖，是一种纯粹的 <b>能力目录</b>。 方便将来将工具部署为独立微服务，通过 MCP 协议挂载。
 */
public class ToolRegistry {

  private final Map<String, ToolMetadata> toolMap = new ConcurrentHashMap<>();

  /**
   * 注册一个工具。
   *
   * @param metadata 工具的完整元数据
   * @throws IllegalArgumentException 如果名称重复
   */
  public void register(ToolMetadata metadata) {
    Objects.requireNonNull(metadata, "metadata must not be null");
    ToolMetadata previous = toolMap.putIfAbsent(metadata.name(), metadata);
    if (previous != null) {
      throw new IllegalArgumentException(
          "Tool already registered: "
              + metadata.name()
              + " (existing: "
              + previous.description()
              + ", new: "
              + metadata.description()
              + ")");
    }
  }

  /**
   * 按名称查找工具元数据。
   *
   * @param toolName 工具名称
   * @return 工具元数据
   * @throws IllegalArgumentException 如果工具未注册
   */
  public ToolMetadata lookup(String toolName) {
    ToolMetadata meta = toolMap.get(toolName);
    if (meta == null) {
      throw new IllegalArgumentException("Tool not registered: " + toolName);
    }
    return meta;
  }

  /** 检查工具是否已注册。 */
  public boolean hasTool(String name) {
    return toolMap.containsKey(name);
  }

  /** 获取所有已注册的工具名称。 */
  public Set<String> toolNames() {
    return toolMap.keySet();
  }

  /** 获取所有已注册的工具元数据（不可修改视图）。 */
  public Collection<ToolMetadata> allTools() {
    return Collections.unmodifiableCollection(toolMap.values());
  }

  /**
   * 将工具元数据转换为 LLM function calling 格式的列表。
   *
   * <p>返回的 Map 列表可直接拼接到 OpenAI/Anthropic 的 tools 参数中。
   *
   * @return List of Map，每项包含 type, function.name, function.description, function.parameters
   */
  public List<Map<String, Object>> toFunctionCallingSchema() {
    return toolMap.values().stream()
        .map(
            meta -> {
              Map<String, Object> function = new LinkedHashMap<>();
              function.put("name", meta.name());
              function.put("description", meta.description());
              function.put("parameters", meta.inputSchema());

              Map<String, Object> tool = new LinkedHashMap<>();
              tool.put("type", "function");
              tool.put("function", function);
              return tool;
            })
        .collect(Collectors.toList());
  }

  /** 移除一个工具注册。 */
  public void unregister(String name) {
    toolMap.remove(name);
  }

  /** 获取已注册工具的数量。 */
  public int size() {
    return toolMap.size();
  }

  // ========== Legacy API（向后兼容） ==========

  /**
   * 简单执行一个工具（兼容旧版 AgentExecutor）。
   *
   * <p>查找工具元数据并通过 MethodHandle 直接调用。 无沙箱保护，无超时控制。新代码请使用 {@link
   * org.cland.alice.tool.gateway.engine.ExecutionEngine}。
   *
   * @deprecated 请使用 {@link org.cland.alice.tool.gateway.engine.ExecutionEngine#invoke(String, Map)}
   */
  @Deprecated
  public boolean execute(String name, Map<String, Object> params) {
    ToolMetadata meta = lookup(name);
    try {
      meta.invoke(params != null ? params : Map.of());
      return true;
    } catch (Throwable e) {
      return false;
    }
  }
}
