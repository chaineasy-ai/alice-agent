module alice.agent.alice.core.agent.main {
    exports org.cland.alice.core.agent;
    exports org.cland.alice.core.agent.lifecycle;
    exports org.cland.alice.core.agent.result;
    exports org.cland.alice.core.agent.executor;

    requires alice.agent.alice.model.main;
    requires alice.agent.alice.core.planner.main;
    requires alice.agent.alice.guardrail.main;
    requires alice.agent.alice.tool.gateway.main;
    requires alice.agent.alice.memory.vault.main;
    requires alice.agent.alice.env.adapter.main;

    requires io.vertx.core;
    requires com.google.common;
    requires org.slf4j;

    requires ch.qos.logback.classic;

}
