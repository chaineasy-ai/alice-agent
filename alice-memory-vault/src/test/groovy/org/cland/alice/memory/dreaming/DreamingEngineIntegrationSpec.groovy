package org.cland.alice.memory.dreaming

import org.cland.alice.memory.wal.InMemoryWalStore
import org.cland.alice.memory.wal.RawMessage
import org.cland.alice.memory.wal.ToolCall
import org.cland.alice.memory.vault.InMemoryEpisodicVault
import org.cland.alice.memory.vault.InMemorySemanticVault
import org.cland.alice.memory.vault.InMemoryProceduralVault
import spock.lang.Specification

/**
 * 端到端集成测试 — 验证完整 Dreaming 管道在所有三个 Vault 中的输出。
 */
class DreamingEngineIntegrationSpec extends Specification {

  InMemoryWalStore walStore
  InMemoryEpisodicVault episodicVault
  InMemorySemanticVault semanticVault
  InMemoryProceduralVault proceduralVault
  SessionStateManager stateManager
  ConflictResolver conflictResolver
  DreamingEngine engine

  def setup() {
    walStore = new InMemoryWalStore()
    episodicVault = new InMemoryEpisodicVault()
    semanticVault = new InMemorySemanticVault()
    proceduralVault = new InMemoryProceduralVault()
    stateManager = new SessionStateManager(walStore)
    conflictResolver = new ConflictResolver(semanticVault)
    engine = new DreamingEngine(
        walStore, episodicVault, semanticVault, proceduralVault,
        DreamingTriggerConfig.defaults()
    )
    engine.setSessionStateManager(stateManager)
    engine.setConflictResolver(conflictResolver)
  }

  def "full end-to-end test: WAL -> all three vaults"() {
    given: "a session with mixed content: system instructions, user queries, and repeated tool patterns"
    def sessionId = "integration-test"

    // System message with high-confidence knowledge
    walStore.appendMessage(new RawMessage(1, sessionId, "system",
        "The database timeout is set to 60 seconds. The API rate limit is 100 requests per minute.",
        null, null, null, 1000L, [:]))

    // User query
    walStore.appendMessage(new RawMessage(2, sessionId, "user",
        "Show me the current configuration", null, null, null, 1100L, [:]))

    // Single tool call (not repeated, won't crystallize)
    walStore.appendMessage(RawMessage.assistantWithToolCalls(3, sessionId,
        [ToolCall.of("call-a", "read_config", [:] as Map)]))

    // Repeated pattern: list_files + read_file (x3)
    for (int i = 0; i < 3; i++) {
      long base = 4 + (i * 4)
      walStore.appendMessage(new RawMessage(base, sessionId, "user",
          "Check path " + i, null, null, null, 2000L + (i * 500L), [:]))
      walStore.appendMessage(RawMessage.assistantWithToolCalls(base + 1, sessionId,
          [ToolCall.of("call-" + i + "-1", "list_files", ["path": "/tmp/" + i] as Map),
           ToolCall.of("call-" + i + "-2", "read_file", ["path": "/tmp/" + i + "/file.txt"] as Map)]))
      walStore.appendMessage(new RawMessage(base + 2, sessionId, "tool",
          "file list: a.txt, b.txt", null, "call-" + i + "-1", null, 2100L + (i * 500L), [:]))
      walStore.appendMessage(new RawMessage(base + 3, sessionId, "tool",
          "content: hello world", null, "call-" + i + "-2", null, 2200L + (i * 500L), [:]))
    }

    // More user query
    walStore.appendMessage(new RawMessage(16, sessionId, "user",
        "That is all, thank you!", null, null, null, 5000L, [:]))

    stateManager.setInitialState(sessionId, SessionState.COMPLETED)

    when: "running the full Dreaming pipeline"
    def result = engine.process(sessionId)

    then: "1. DreamingSession outcome is SUCCESS"
    result.outcome() == DreamingSession.DreamingOutcome.SUCCESS
    result.sessionId() == sessionId
    result.patternsCrystallized() >= 1

    and: "2. EpisodicVault contains a dreaming_summary"
    def trace = episodicVault.getTrace(sessionId)
    !trace.isEmpty()
    trace.any { it.action() == "dreaming_summary" }

    and: "3. SemanticVault contains extracted facts in _dreaming_facts"
    semanticVault.count("_dreaming_facts") > 0

    and: "4. ProceduralVault contains crystallized SOPs"
    proceduralVault.count() >= 1

    and: "5. Session state is ARCHIVED"
    stateManager.getState(sessionId) == SessionState.ARCHIVED

    and: "6. Recent sessions tracking works"
    def recent = engine.recentSessions(5)
    recent.size() >= 1
    recent[0].sessionId() == sessionId
  }

  def "end-to-end with conflict resolution"() {
    given: "pre-seeded knowledge in SemanticVault"
    def oldKnowledge = org.cland.alice.memory.core.Knowledge.builder()
        .knowledgeId("existing-fact-1")
        .content("The database timeout is set to 60 seconds") // no trailing period — matches fact extractor output
        .source("previous-session")
        .collection("_dreaming_facts")
        .createdAt(500L)
        .build()
    semanticVault.store("_dreaming_facts", oldKnowledge)

    and: "a session with conflicting knowledge"
    def sessionId = "conflict-integration"
    walStore.appendMessage(new RawMessage(1, sessionId, "system",
        "The database timeout is set to 60 seconds.",
        null, null, null, 5000L, [:]))
    stateManager.setInitialState(sessionId, SessionState.COMPLETED)

    when: "running Dreaming pipeline with ConflictResolver injected"
    def result = engine.process(sessionId)

    then: "old fact was deprecated"
    result.outcome() == DreamingSession.DreamingOutcome.SUCCESS
    result.conflictCount == 1 // 1 fact was deprecated

    def allFacts = semanticVault.getAll("_dreaming_facts")
    allFacts.any { it.content().contains("(DEPRECATED)") }
  }

  def "end-to-end with session locking"() {
    given: "a session ready for dreaming"
    def sessionId = "locking-integration"
    walStore.appendMessage(RawMessage.system(1, sessionId, "init config"))
    stateManager.setInitialState(sessionId, SessionState.COMPLETED)

    when:
    def result = engine.process(sessionId)

    then: "session is archived after processing"
    result.outcome() == DreamingSession.DreamingOutcome.SUCCESS
    stateManager.getState(sessionId) == SessionState.ARCHIVED

    and: "second attempt returns SKIPPED"
    def second = engine.process(sessionId)
    second.outcome() == DreamingSession.DreamingOutcome.SKIPPED
  }
}
