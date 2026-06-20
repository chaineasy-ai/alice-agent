package org.cland.alice.tool.gateway;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.cland.alice.tool.gateway.metadata.ToolMetadata;

/**
 * 工具注册中心 — Agent 能力的统一目录。
 *
 * <p>对应设计文档类图中 {@code ToolRegistry} 的角色，负责：
 *
 * <ul>
 *   <li>存储所有已注册工具的 {@link ToolMetadata} 元数据
 *   <li>按名称查找工具
 *   <li>提供工具列表查询（{@link #toolNames()} / {@link #allTools()}）
 * </ul>
 *
 * <p>设计上对 alice-core-agent 无业务依赖，是一种纯粹的 <b>能力目录</b>。 方便将来将工具部署为独立微服务，通过 MCP 协议挂载。
 */
public class ToolRegistry {

  private final Map<String, ToolMetadata> toolMap = new ConcurrentHashMap<>();

  /**
   * 注册一个工具。如果名称重复则跳过（幂等注册）。
   *
   * @param metadata 工具的完整元数据
   */
  public void register(ToolMetadata metadata) {
    Objects.requireNonNull(metadata, "metadata must not be null");
    ToolMetadata previous = toolMap.putIfAbsent(metadata.name(), metadata);
    if (previous != null) {
      // 幂等：重复注册时跳过，方便测试和重启式注册
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

  /** 移除一个工具注册。 */
  public void unregister(String name) {
    toolMap.remove(name);
  }

  /** 获取已注册工具的数量。 */
  public int size() {
    return toolMap.size();
  }
}
