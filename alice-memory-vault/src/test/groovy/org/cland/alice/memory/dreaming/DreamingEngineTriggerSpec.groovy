package org.cland.alice.memory.dreaming

import org.cland.alice.core.agent.wal.InMemoryWalStore
import org.cland.alice.core.agent.wal.RawMessage
import org.cland.alice.memory.vault.InMemoryEpisodicVault
import org.cland.alice.memory.vault.InMemorySemanticVault
import org.cland.alice.memory.vault.InMemoryProceduralVault
import spock.lang.Specification

class DreamingEngineTriggerSpec extends Specification {

  InMemoryWalStore walStore
  InMemoryEpisodicVault episodicVault
  InMemorySemanticVault semanticVault
  InMemoryProceduralVault proceduralVault
  SessionStateManager stateManager
  DreamingEngine engine

  def setup() {
    walStore = new InMemoryWalStore()
    episodicVault = new InMemoryEpisodicVault()
    semanticVault = new InMemorySemanticVault()
    proceduralVault = new InMemoryProceduralVault()
    stateManager = new SessionStateManager(walStore)
    engine = new DreamingEngine(
        walStore, episodicVault, semanticVault, proceduralVault,
        DreamingTriggerConfig.defaults()
    )
    engine.setSessionStateManager(stateManager)
  }

  def "processAll processes all COMPLETED sessions"() {
    given: "multiple completed sessions with data"
    def s1 = "trigger-s1"
    def s2 = "trigger-s2"
    walStore.appendMessage(RawMessage.system(1, s1, "Session 1 init"))
    walStore.appendMessage(RawMessage.user(2, s1, "Query 1"))
    walStore.appendMessage(RawMessage.system(3, s2, "Session 2 init"))
    walStore.appendMessage(RawMessage.user(4, s2, "Query 2"))
    stateManager.setInitialState(s1, SessionState.COMPLETED)
    stateManager.setInitialState(s2, SessionState.COMPLETED)

    when:
    def results = engine.processAll()

    then:
    results.size() == 2
    results.every { it.outcome() == DreamingSession.DreamingOutcome.SUCCESS }
  }

  def "processAll skips non-dreamable sessions"() {
    given: "one COMPLETED and one RUNNING session"
    def s1 = "good-session"
    def s2 = "bad-session"
    walStore.appendMessage(RawMessage.system(1, s1, "good"))
    walStore.appendMessage(RawMessage.system(2, s2, "bad"))
    stateManager.setInitialState(s1, SessionState.COMPLETED)
    stateManager.setInitialState(s2, SessionState.RUNNING)

    when:
    def results = engine.processAll()

    then:
    results.size() == 1
    results[0].sessionId() == "good-session"
    results[0].outcome() == DreamingSession.DreamingOutcome.SUCCESS
  }

  def "recentSessions returns correct number of records"() {
    given:
    def s1 = "recent-1"
    def s2 = "recent-2"
    walStore.appendMessage(RawMessage.system(1, s1, "init 1"))
    walStore.appendMessage(RawMessage.system(2, s2, "init 2"))
    stateManager.setInitialState(s1, SessionState.COMPLETED)
    stateManager.setInitialState(s2, SessionState.COMPLETED)

    when:
    engine.process(s1)
    engine.process(s2)
    def recent = engine.recentSessions(2)

    then:
    recent.size() == 2
    recent[0].sessionId() != null
    recent[1].sessionId() != null
  }

  def "recentSessions respects limit"() {
    given:
    def s1 = "limit-1"
    walStore.appendMessage(RawMessage.system(1, s1, "init"))
    stateManager.setInitialState(s1, SessionState.COMPLETED)

    when:
    engine.process(s1)
    def recent = engine.recentSessions(0)
    def recentNegative = engine.recentSessions(-1)

    then:
    recent.isEmpty()
    recentNegative.isEmpty()
  }

  def "pendingSessionCount returns correct count"() {
    given:
    def s1 = "pending-1"
    def s2 = "pending-2"
    walStore.appendMessage(RawMessage.system(1, s1, "init"))
    walStore.appendMessage(RawMessage.system(2, s2, "init"))
    stateManager.setInitialState(s1, SessionState.COMPLETED)
    stateManager.setInitialState(s2, SessionState.RUNNING)

    expect:
    engine.pendingSessionCount() == 1
  }
}
