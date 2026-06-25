package org.cland.alice.memory.dreaming

import org.cland.alice.core.agent.wal.InMemoryWalStore
import org.cland.alice.core.agent.wal.RawMessage
import spock.lang.Specification

class WalSessionReadGuardSpec extends Specification {

  InMemoryWalStore delegate
  SessionStateManager stateManager
  WalSessionReadGuard guard

  def setup() {
    delegate = new InMemoryWalStore()
    stateManager = new SessionStateManager(delegate)
    guard = new WalSessionReadGuard(delegate, stateManager)
  }

  def "read from DREAMING session is blocked"() {
    given: "a session in DREAMING state"
    def sessionId = "dreaming-session"
    delegate.appendMessage(RawMessage.user(1, sessionId, "hello"))
    stateManager.setInitialState(sessionId, SessionState.DREAMING)

    when:
    guard.getAllMessages(sessionId)

    then:
    thrown(IllegalStateException)
  }

  def "read from COMPLETED session is allowed"() {
    given: "a session in COMPLETED state"
    def sessionId = "completed-session"
    delegate.appendMessage(RawMessage.user(1, sessionId, "hello"))
    stateManager.setInitialState(sessionId, SessionState.COMPLETED)

    when:
    def messages = guard.getAllMessages(sessionId)

    then:
    messages.size() == 1
    messages[0].content() == "hello"
  }

  def "read from RUNNING session is allowed"() {
    given: "a session in RUNNING state"
    def sessionId = "running-session"
    delegate.appendMessage(RawMessage.user(1, sessionId, "test"))
    stateManager.setInitialState(sessionId, SessionState.RUNNING)

    when:
    def messages = guard.getAllMessages(sessionId)

    then:
    messages.size() == 1
  }

  def "read from non-existent session returns empty"() {
    when:
    def messages = guard.getAllMessages("nonexistent")

    then:
    messages.isEmpty()
  }

  def "messageCount on DREAMING session is blocked"() {
    given:
    stateManager.setInitialState("dreaming", SessionState.DREAMING)

    when:
    guard.messageCount("dreaming")

    then:
    thrown(IllegalStateException)
  }

  def "write operations are allowed on DREAMING session"() {
    given: "a session in DREAMING state"
    def sessionId = "dreaming-write"
    stateManager.setInitialState(sessionId, SessionState.DREAMING)

    when:
    def id = guard.appendMessage(RawMessage.system(1, sessionId, "allowed"))

    then:
    id > 0
    delegate.messageCount(sessionId) == 1
  }

  def "getMessagesAfter on DREAMING session is blocked"() {
    given:
    delegate.appendMessage(RawMessage.user(1, "dreaming", "test"))
    stateManager.setInitialState("dreaming", SessionState.DREAMING)

    when:
    guard.getMessagesAfter("dreaming", 0, 10)

    then:
    thrown(IllegalStateException)
  }

  def "checkpoint reads on DREAMING session are blocked"() {
    given:
    stateManager.setInitialState("dreaming", SessionState.DREAMING)

    when:
    guard.getLatestCheckpoint("dreaming")

    then:
    thrown(IllegalStateException)
  }
}
