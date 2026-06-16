/*
 * Alice Agent — ToolRegistryHolder
 *
 * 全局单例持有 ToolRegistry，供 CLI/TUI facade 查询已注册工具列表。
 */
package org.cland.alice.tool.gateway;

/**
 * 工具注册中心全局持有者。
 *
 * <p>提供静态 {@link #INSTANCE} 单例访问 {@link ToolRegistry}， 供 {@code alice tools} 命令和 {@code /skill
 * list} 命令查询已注册工具。
 */
public final class ToolRegistryHolder {

  /** 全局单例 */
  public static final ToolRegistryHolder INSTANCE = new ToolRegistryHolder();

  private final ToolRegistry registry;

  private ToolRegistryHolder() {
    this.registry = new ToolRegistry();
  }

  /** 获取 ToolRegistry 实例。 */
  public ToolRegistry registry() {
    return registry;
  }

  /** 获取所有已注册的工具列表（快捷方法）。 */
  public java.util.Collection<org.cland.alice.tool.gateway.metadata.ToolMetadata> allTools() {
    return registry.allTools();
  }
}
