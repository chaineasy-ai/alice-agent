module alice.agent.alice.model.main {
  exports org.cland.alice.model;
  exports org.cland.alice.model.common;
  exports org.cland.alice.model.supplier;

  requires java.net.http;
  requires org.slf4j;
  requires ch.qos.logback.classic;
}
