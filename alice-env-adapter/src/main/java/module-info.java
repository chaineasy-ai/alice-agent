module alice.agent.alice.env.adapter.main {
  exports org.cland.alice.env.adapter;
  exports org.cland.alice.env.adapter.snapshot;
  exports org.cland.alice.env.adapter.state;
  exports org.cland.alice.env.adapter.transport;

  requires alice.agent.alice.tool.gateway.main;
  requires com.google.gson;
  requires com.google.common;
  requires org.slf4j;
  requires ch.qos.logback.classic;
}
