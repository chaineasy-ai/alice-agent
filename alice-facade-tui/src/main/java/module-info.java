module alice.agent.facade.tui.main {
  // SPI: provide AliceFacade implementation for bootstrap
  provides org.cland.alice.agent.spi.AliceFacade with
      org.cland.alice.facade.tui.AliceTuiFacade;

  exports org.cland.alice.facade.tui;
  exports org.cland.alice.facade.tui.bridge;
  exports org.cland.alice.facade.tui.command;
  exports org.cland.alice.facade.tui.component;
  exports org.cland.alice.facade.tui.layout;
  exports org.cland.alice.facade.tui.state;

  requires alice.agent.app.main;
  requires alice.agent.alice.core.agent.main;
  requires alice.agent.alice.env.adapter.main;
  requires alice.agent.alice.model.main;
  requires alice.agent.command.main;
  requires alice.agent.alice.memory.vault.main;

  // JLine 4: 三层单线分割布局 + 原生向上顶出补全（AUTO_MENU）
  // 4.2.1 fat bundle 自动模块名 org.jline
  requires org.jline;

  // JUL: 用于抑制 JLine 内部 org.jline 日志输出
  requires java.logging;

  // Guava (automatic module)
  requires com.google.common;

  // Jackson
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.datatype.jsr310;
  requires org.slf4j;
  requires ch.qos.logback.classic;
}
