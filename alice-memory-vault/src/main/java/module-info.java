module alice.agent.alice.memory.vault.main {
  exports org.cland.alice.memory.agent;
  exports org.cland.alice.memory.core;
  exports org.cland.alice.memory.vault;
  exports org.cland.alice.memory.storage;
  exports org.cland.alice.memory.router;
  exports org.cland.alice.memory.controller;
  exports org.cland.alice.memory.sop;

  requires alice.agent.alice.core.agent.main;
  requires alice.agent.alice.core.planner.main;
  requires org.jgrapht.core;
  requires org.jgrapht.io;
  requires org.slf4j;
  requires ch.qos.logback.classic;
}
