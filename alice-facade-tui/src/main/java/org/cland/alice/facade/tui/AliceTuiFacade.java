/*
 * Alice Agent — TUI Facade SPI Implementation
 *
 * Implements AliceFacade to allow bootstrap to discover and launch the TUI facade via ServiceLoader.
 */
package org.cland.alice.facade.tui;

import org.cland.alice.agent.spi.AliceFacade;

/**
 * AliceTuiFacade — TUI Facade 的 SPI 实现。
 *
 * <p>通过 {@code META-INF/services/org.cland.alice.agent.spi.AliceFacade} 注册， 由 bootstrap 模块的 {@link
 * java.util.ServiceLoader} 在运行时发现。
 */
public class AliceTuiFacade implements AliceFacade {

  @Override
  public String name() {
    return "tui";
  }

  @Override
  public int launch(String[] args) {
    return AliceTuiLauncher.launch(args);
  }
}
