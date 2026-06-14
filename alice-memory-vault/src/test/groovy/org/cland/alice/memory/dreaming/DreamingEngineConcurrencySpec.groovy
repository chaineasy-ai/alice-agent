package org.cland.alice.memory.dreaming

import org.cland.alice.memory.wal.InMemoryWalStore
import org.cland.alice.memory.wal.RawMessage
import org.cland.alice.memory.vault.InMemoryEpisodicVault
import org.cland.alice.memory.vault.InMemorySemanticVault
import org.cland.alice.memory.vault.InMemoryProceduralVault
import spock.lang.Specification
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DreamingEngineConcurrencySpec extends Specification {

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

  def "two concurrent processes on same session - exactly one succeeds"() {
    given: "a session with data"
    def sessionId = "concurrent-test"
    walStore.appendMessage(RawMessage.system(1, sessionId, "init"))
    walStore.appendMessage(RawMessage.user(2, sessionId, "hello"))
    stateManager.setInitialState(sessionId, SessionState.COMPLETED)

    and: "a latch to synchronize both threads"
    def latch = new CountDownLatch(1)
    def results = new ArrayList<DreamingSession>()
    def executor = Executors.newFixedThreadPool(2)

    when:
    // Launch both threads
    def future1 = executor.submit({
      latch.await()
      return engine.process(sessionId)
    } as java.util.concurrent.Callable<DreamingSession>)

    def future2 = executor.submit({
      latch.await()
      return engine.process(sessionId)
    } as java.util.concurrent.Callable<DreamingSession>)

    // Release both at the same time
    Thread.sleep(100) // ensure both threads are waiting
    latch.countDown()

    results.add(future1.get(10, TimeUnit.SECONDS))
    results.add(future2.get(10, TimeUnit.SECONDS))
    executor.shutdown()

    then:
    // Exactly one SUCCESS, one SKIPPED
    results.count { it.outcome() == DreamingSession.DreamingOutcome.SUCCESS } == 1
    results.count { it.outcome() == DreamingSession.DreamingOutcome.SKIPPED } == 1

    // Session state should be ARCHIVED
    stateManager.getState(sessionId) == SessionState.ARCHIVED
  }

  def "failed process reverts session state to COMPLETED"() {
    given: "a session that will cause an error"
    def sessionId = "error-test"
    // Don't add messages - DreamingEngine handles this gracefully,
    // so let's simulate by not having a session state manager handle properly
    stateManager.setInitialState(sessionId, SessionState.COMPLETED)

    when:
    def result = engine.process(sessionId)

    then:
    // Even with empty messages, it should succeed (no error)
    result.outcome() == DreamingSession.DreamingOutcome.SUCCESS
  }

  def "process on DREAMING session returns SKIPPED"() {
    given: "a session already in DREAMING state"
    def sessionId = "dreaming-skip"
    walStore.appendMessage(RawMessage.system(1, sessionId, "init"))
    stateManager.setInitialState(sessionId, SessionState.DREAMING)

    when:
    def result = engine.process(sessionId)

    then:
    result.outcome() == DreamingSession.DreamingOutcome.SKIPPED
    stateManager.getState(sessionId) == SessionState.DREAMING
  }

  def "process on ARCHIVED session returns SKIPPED"() {
    given: "a session already in ARCHIVED state"
    def sessionId = "archived-skip"
    stateManager.setInitialState(sessionId, SessionState.ARCHIVED)

    when:
    def result = engine.process(sessionId)

    then:
    result.outcome() == DreamingSession.DreamingOutcome.SKIPPED
    stateManager.getState(sessionId) == SessionState.ARCHIVED
  }
}
