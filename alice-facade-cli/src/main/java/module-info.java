module alice.agent.facade.cli.main {
  exports org.cland.alice.facade.cli;
  exports org.cland.alice.facade.cli.config;
  exports org.cland.alice.facade.cli.render;

  opens org.cland.alice.facade.cli.config;

  // SPI: provide AliceFacade implementation for bootstrap
  provides org.cland.alice.agent.spi.AliceFacade with
      org.cland.alice.facade.cli.AliceCliFacade;

  requires alice.agent.app.main;
  requires alice.agent.alice.core.agent.main;
  requires alice.agent.alice.guardrail.main;
  requires alice.agent.alice.model.main;
  requires alice.agent.proto.main;
  requires alice.agent.runtime.main;
  requires alice.agent.alice.tool.gateway.main;
  requires alice.agent.alice.core.planner.main;
  requires alice.agent.alice.memory.vault.main;
  requires info.picocli;
  requires org.jline;
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.datatype.jsr310;
  requires com.google.common;
  requires io.vertx.core;
  requires org.slf4j;
  requires ch.qos.logback.classic;
}
