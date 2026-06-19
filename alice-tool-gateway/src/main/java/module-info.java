module alice.agent.alice.tool.gateway.main {
  requires com.fasterxml.jackson.databind;
  requires com.google.common;
  requires org.slf4j;
  requires ch.qos.logback.classic;

  exports org.cland.alice.tool.gateway;
  exports org.cland.alice.tool.gateway.annotation;
  exports org.cland.alice.tool.gateway.metadata;
  exports org.cland.alice.tool.gateway.model;
  exports org.cland.alice.tool.gateway.sandbox;
  exports org.cland.alice.tool.gateway.schema;
  exports org.cland.alice.tool.gateway.engine;
  exports org.cland.alice.tool.gateway.builtin;

  // 打开包以便测试代码通过反射访问注解和方法
  opens org.cland.alice.tool.gateway;
  opens org.cland.alice.tool.gateway.annotation;
  opens org.cland.alice.tool.gateway.metadata;
  opens org.cland.alice.tool.gateway.model;
  opens org.cland.alice.tool.gateway.sandbox;
  opens org.cland.alice.tool.gateway.schema;
  opens org.cland.alice.tool.gateway.engine;
}
