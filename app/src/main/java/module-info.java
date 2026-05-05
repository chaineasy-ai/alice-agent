module alice.agent.app.main {
    exports org.cland.alice.agent;

    requires alice.agent.alice.core.agent.main;
    requires alice.agent.alice.model.main;
    requires alice.agent.facade.cmd.main;
    requires alice.agent.facade.tui.main;
}
