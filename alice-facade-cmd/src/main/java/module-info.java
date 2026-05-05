module alice.agent.facade.cmd.main {
    exports org.cland.alice.facade.cmd;
    exports org.cland.alice.facade.cmd.config;
    exports org.cland.alice.facade.cmd.render;

    requires alice.agent.alice.core.agent.main;
    requires alice.agent.alice.model.main;

    requires info.picocli;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires com.google.common;
    requires io.vertx.core;
    requires org.slf4j;

    requires ch.qos.logback.classic;

}
