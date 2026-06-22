module alice.agent.facade.cmd.main {
  exports org.cland.alice.facade.cmd;
  exports org.cland.alice.facade.cmd.config;
  exports org.cland.alice.facade.cmd.render;

  opens org.cland.alice.facade.cmd.config;

  // SPI: provide AliceFacade implementation for bootstrap
  provides org.cland.alice.agent.spi.AliceFacade with
      org.cland.alice.facade.cmd.AliceCliFacade;

  requires alice.agent.app.main;
  requires alice.agent.alice.core.agent.main;
  requires alice.agent.alice.model.main;
  requires alice.agent.command.main;
  requires alice.agent.alice.tool.gateway.main;
  requires alice.agent.alice.memory.vault.main;
  requires info.picocli;
  requires org.jline.reader;
  requires org.jline.terminal;
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.datatype.jsr310;
  requires com.google.common;
  requires io.vertx.core;
  requires org.slf4j;
  requires ch.qos.logback.classic;
  requires org.jline.builtins;
}
