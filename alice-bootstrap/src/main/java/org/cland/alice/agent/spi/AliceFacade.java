/*
 * Alice Agent — Facade SPI
 *
 * Service Provider Interface for facade modules.
 * Bootstrap discovers facades via ServiceLoader, removing compile-time coupling.
 */
package org.cland.alice.agent.spi;

/**
 * AliceFacade — 外观 SPI 接口。
 *
 * <p>每个 facade 模块（CLI, TUI, Web 等）需实现此接口，并通过 {@link java.util.ServiceLoader} 注册。bootstrap
 * 模块在运行时发现并启动对应的 facade。
 *
 * <p>实现类必须在 {@code META-INF/services/org.cland.alice.agent.spi.AliceFacade} 中声明。
 */
public interface AliceFacade {

  /**
   * @return 外观名称，用于参数匹配（如 "cli", "tui", "web"）
   */
  String name();

  /**
   * 启动外观。
   *
   * @param args 原始命令行参数
   * @return 退出码（0 成功，非 0 失败）
   */
  int launch(String[] args);
}
