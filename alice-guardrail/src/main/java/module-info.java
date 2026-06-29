module alice.agent.alice.guardrail.main {
  requires alice.agent.alice.core.planner.main;
  requires alice.agent.alice.tool.gateway.main;
  requires org.slf4j;
  requires ch.qos.logback.classic;

  exports org.cland.alice.guardrail;
  exports org.cland.alice.guardrail.validators;
}
