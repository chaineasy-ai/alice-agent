module alice.agent.alice.core.agent.main {
  exports org.cland.alice.core.agent;
  exports org.cland.alice.core.agent.lifecycle;
  exports org.cland.alice.core.agent.result;
  exports org.cland.alice.core.agent.executor;
  exports org.cland.alice.core.agent.prompt;
  exports org.cland.alice.core.agent.memory;
  exports org.cland.alice.core.agent.wal;
  exports org.cland.alice.agent.subagent;
  exports org.cland.alice.core.agent.guardrail;

  requires alice.agent.alice.model.main;
  requires alice.agent.alice.core.planner.main;
  requires alice.agent.alice.guardrail.main;
  requires alice.agent.alice.tool.gateway.main;
  requires alice.agent.alice.env.adapter.main;
  requires io.vertx.core;
  requires com.google.common;
  requires org.jgrapht.core;
  requires freemarker;
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.datatype.jsr310;
  requires org.slf4j;
  requires ch.qos.logback.classic;
}
