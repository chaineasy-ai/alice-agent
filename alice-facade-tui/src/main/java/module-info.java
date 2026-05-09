module alice.agent.facade.tui.main {
  exports org.cland.alice.facade.tui;
  exports org.cland.alice.facade.tui.bridge;
  exports org.cland.alice.facade.tui.command;
  exports org.cland.alice.facade.tui.component;
  exports org.cland.alice.facade.tui.layout;
  exports org.cland.alice.facade.tui.state;

  requires alice.agent.alice.core.agent.main;
  requires alice.agent.alice.env.adapter.main;
  requires alice.agent.alice.model.main;

  // Lanterna (automatic module)
  requires com.googlecode.lanterna;
  // JLine3 (automatic modules)
  requires org.jline.reader;
  requires org.jline.terminal;
  // Guava (automatic module)
  requires com.google.common;

  // Jackson
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.datatype.jsr310;
  requires org.slf4j;
  requires ch.qos.logback.classic;
}
