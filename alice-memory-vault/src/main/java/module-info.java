module alice.agent.alice.memory.vault.main {
  exports org.cland.alice.memory.agent;
  exports org.cland.alice.memory.core;
  exports org.cland.alice.memory.vault;
  exports org.cland.alice.memory.storage;
  exports org.cland.alice.memory.router;
  exports org.cland.alice.memory.controller;

  requires org.slf4j;
  requires ch.qos.logback.classic;
}
