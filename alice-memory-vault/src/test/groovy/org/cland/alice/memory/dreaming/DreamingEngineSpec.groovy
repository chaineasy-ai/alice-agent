package org.cland.alice.memory.dreaming

import org.cland.alice.core.agent.wal.InMemoryWalStore
import org.cland.alice.core.agent.wal.RawMessage
import org.cland.alice.core.agent.wal.ToolCall
import org.cland.alice.memory.vault.InMemoryEpisodicVault
import org.cland.alice.memory.vault.InMemorySemanticVault
import org.cland.alice.memory.vault.InMemoryProceduralVault
import spock.lang.Specification

class DreamingEngineSpec extends Specification {

  InMemoryWalStore walStore
  InMemoryEpisodicVault episodicVault
  InMemorySemanticVault semanticVault
  InMemoryProceduralVault proceduralVault
  DreamingEngine engine

  def setup() {
    walStore = new InMemoryWalStore()
    episodicVault = new InMemoryEpisodicVault()
    semanticVault = new InMemorySemanticVault()
    proceduralVault = new InMemoryProceduralVault()
    engine = new DreamingEngine(
        walStore, episodicVault, semanticVault, proceduralVault,
        DreamingTriggerConfig.defaults()
    )
  }

  def "should process a session with 5+ WAL entries and produce episodic summary"() {
    given: "a session with 5+ WAL entries"
    def sessionId = "test-session-1"
    walStore.appendMessage(RawMessage.system(1, sessionId, "System initialized"))
    walStore.appendMessage(RawMessage.user(2, sessionId, "Hello, what files do I have?"))
    walStore.appendMessage(RawMessage.assistant(3, sessionId, "Let me check your files."))
    walStore.appendMessage(RawMessage.user(4, sessionId, "Show me the config"))
    walStore.appendMessage(RawMessage.assistant(5, sessionId, "Here is the config file content."))

    when:
    def result = engine.process(sessionId)

    then:
    result.outcome() == DreamingSession.DreamingOutcome.SUCCESS
    result.sessionId() == sessionId
    result.durationMs() >= 0
    result.episodicSummaryId() != null

    // EpisodicVault should contain a dreaming_summary step
    def trace = episodicVault.getTrace(sessionId)
    !trace.isEmpty()
    trace.any { it.action() == "dreaming_summary" }
  }

  def "should write facts to SemanticVault collection _dreaming_facts"() {
    given:
    def sessionId = "test-session-2"
    walStore.appendMessage(RawMessage.system(1, sessionId, "API key is abc123"))
    walStore.appendMessage(RawMessage.user(2, sessionId, "Database timeout is 30 seconds"))

    when:
    def result = engine.process(sessionId)

    then:
    result.outcome() == DreamingSession.DreamingOutcome.SUCCESS
    semanticVault.count("_dreaming_facts") > 0
  }

  def "should crystallize repeated tool patterns into SOPs"() {
    given: "a session with 3+ repeated tool call sequences"
    def sessionId = "test-session-3"
    // Simulate 4 runs of the same 2-tool pattern
    def toolCalls1 = [ToolCall.of("call-1", "list_files", ["path": "/tmp"]),
                      ToolCall.of("call-2", "read_file", ["path": "/tmp/test.txt"])]
    def toolCalls2 = [ToolCall.of("call-3", "list_files", ["path": "/etc"]),
                      ToolCall.of("call-4", "read_file", ["path": "/etc/config.yml"])]
    def toolCalls3 = [ToolCall.of("call-5", "list_files", ["path": "/home"]),
                      ToolCall.of("call-6", "read_file", ["path": "/home/user.txt"])]
    def toolCalls4 = [ToolCall.of("call-7", "list_files", ["path": "/var"]),
                      ToolCall.of("call-8", "read_file", ["path": "/var/log/syslog"])]

    walStore.appendMessage(RawMessage.user(1, sessionId, "Show me files"))
    walStore.appendMessage(RawMessage.assistantWithToolCalls(2, sessionId, toolCalls1))
    walStore.appendMessage(RawMessage.toolResult(3, sessionId, "call-1", "file1.txt"))
    walStore.appendMessage(RawMessage.toolResult(4, sessionId, "call-2", "content1"))

    walStore.appendMessage(RawMessage.user(5, sessionId, "Show me more files"))
    walStore.appendMessage(RawMessage.assistantWithToolCalls(6, sessionId, toolCalls2))
    walStore.appendMessage(RawMessage.toolResult(7, sessionId, "call-3", "file2.txt"))
    walStore.appendMessage(RawMessage.toolResult(8, sessionId, "call-4", "content2"))

    walStore.appendMessage(RawMessage.user(9, sessionId, "Show me other files"))
    walStore.appendMessage(RawMessage.assistantWithToolCalls(10, sessionId, toolCalls3))
    walStore.appendMessage(RawMessage.toolResult(11, sessionId, "call-5", "file3.txt"))
    walStore.appendMessage(RawMessage.toolResult(12, sessionId, "call-6", "content3"))

    walStore.appendMessage(RawMessage.user(13, sessionId, "Show me log files"))
    walStore.appendMessage(RawMessage.assistantWithToolCalls(14, sessionId, toolCalls4))
    walStore.appendMessage(RawMessage.toolResult(15, sessionId, "call-7", "file4.txt"))
    walStore.appendMessage(RawMessage.toolResult(16, sessionId, "call-8", "content4"))

    when:
    def result = engine.process(sessionId)

    then:
    result.outcome() == DreamingSession.DreamingOutcome.SUCCESS
    result.patternsCrystallized() >= 1

    // ProceduralVault should contain SOP entries
    proceduralVault.count() >= 1
  }

  def "should handle session with zero entries gracefully"() {
    given: "a session with no WAL entries"
    def sessionId = "empty-session"

    when:
    def result = engine.process(sessionId)

    then:
    // Should still return a result without error
    result.outcome() == DreamingSession.DreamingOutcome.SUCCESS
    result.episodicSummaryId() != null
  }

  def "should process multiple sessions via processAll"() {
    given: "multiple sessions with data"
    def s1 = "multi-1"
    def s2 = "multi-2"
    walStore.appendMessage(RawMessage.system(1, s1, "Session 1 init"))
    walStore.appendMessage(RawMessage.user(2, s1, "Query 1"))
    walStore.appendMessage(RawMessage.system(3, s2, "Session 2 init"))
    walStore.appendMessage(RawMessage.user(4, s2, "Query 2"))

    when:
    def results = engine.processAll()

    then:
    results.size() == 2
    results.every { it.outcome() == DreamingSession.DreamingOutcome.SUCCESS }
  }

  def "should return recent sessions list"() {
    given:
    def sessionId = "recent-test"
    walStore.appendMessage(RawMessage.system(1, sessionId, "init"))

    when:
    engine.process(sessionId)
    def recent = engine.recentSessions(5)

    then:
    recent.size() == 1
    recent[0].sessionId() == sessionId
    recent[0].outcome() == DreamingSession.DreamingOutcome.SUCCESS
  }
}
