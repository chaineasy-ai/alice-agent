package org.cland.alice.memory.dreaming

import org.cland.alice.memory.wal.InMemoryWalStore
import spock.lang.Specification

class SessionStateManagerSpec extends Specification {

  InMemoryWalStore walStore
  SessionStateManager manager

  def setup() {
    walStore = new InMemoryWalStore()
    manager = new SessionStateManager(walStore)
  }

  def "default state for unknown session is CREATED"() {
    expect:
    manager.getState("unknown") == SessionState.CREATED
  }

  def "legal transition CREATED -> RUNNING"() {
    when:
    manager.setInitialState("s1", SessionState.CREATED)
    def result = manager.transition("s1", SessionState.CREATED, SessionState.RUNNING)

    then:
    result == true
    manager.getState("s1") == SessionState.RUNNING
  }

  def "legal transition RUNNING -> COMPLETED"() {
    given:
    manager.setInitialState("s1", SessionState.RUNNING)

    when:
    def result = manager.transition("s1", SessionState.RUNNING, SessionState.COMPLETED)

    then:
    result == true
    manager.getState("s1") == SessionState.COMPLETED
  }

  def "legal transition RUNNING -> CRASHED"() {
    given:
    manager.setInitialState("s1", SessionState.RUNNING)

    when:
    def result = manager.transition("s1", SessionState.RUNNING, SessionState.CRASHED)

    then:
    result == true
    manager.getState("s1") == SessionState.CRASHED
  }

  def "legal transition COMPLETED -> DREAMING"() {
    given:
    manager.setInitialState("s1", SessionState.COMPLETED)

    when:
    def result = manager.transition("s1", SessionState.COMPLETED, SessionState.DREAMING)

    then:
    result == true
    manager.getState("s1") == SessionState.DREAMING
  }

  def "legal transition DREAMING -> ARCHIVED"() {
    given:
    manager.setInitialState("s1", SessionState.DREAMING)

    when:
    def result = manager.transition("s1", SessionState.DREAMING, SessionState.ARCHIVED)

    then:
    result == true
    manager.getState("s1") == SessionState.ARCHIVED
  }

  def "legal transition DREAMING -> COMPLETED (failure rollback)"() {
    given:
    manager.setInitialState("s1", SessionState.DREAMING)

    when:
    def result = manager.transition("s1", SessionState.DREAMING, SessionState.COMPLETED)

    then:
    result == true
    manager.getState("s1") == SessionState.COMPLETED
  }

  def "invalid transition RUNNING -> DREAMING throws StateTransitionException"() {
    given:
    manager.setInitialState("s1", SessionState.RUNNING)

    when:
    manager.transition("s1", SessionState.RUNNING, SessionState.DREAMING)

    then:
    thrown(StateTransitionException)
    manager.getState("s1") == SessionState.RUNNING
  }

  def "invalid transition ARCHIVED -> DREAMING throws StateTransitionException"() {
    given:
    manager.setInitialState("s1", SessionState.ARCHIVED)

    when:
    manager.transition("s1", SessionState.ARCHIVED, SessionState.DREAMING)

    then:
    thrown(StateTransitionException)
    manager.getState("s1") == SessionState.ARCHIVED
  }

  def "isDreamable returns true for COMPLETED"() {
    given:
    manager.setInitialState("s1", SessionState.COMPLETED)

    expect:
    manager.isDreamable("s1") == true
  }

  def "isDreamable returns true for CRASHED"() {
    given:
    manager.setInitialState("s1", SessionState.CRASHED)

    expect:
    manager.isDreamable("s1") == true
  }

  def "isDreamable returns false for RUNNING"() {
    given:
    manager.setInitialState("s1", SessionState.RUNNING)

    expect:
    manager.isDreamable("s1") == false
  }

  def "isDreamable returns false for DREAMING"() {
    given:
    manager.setInitialState("s1", SessionState.DREAMING)

    expect:
    manager.isDreamable("s1") == false
  }

  def "isDreamable returns false for ARCHIVED"() {
    given:
    manager.setInitialState("s1", SessionState.ARCHIVED)

    expect:
    manager.isDreamable("s1") == false
  }

  def "tryLockForDreaming succeeds for COMPLETED session"() {
    given:
    manager.setInitialState("s1", SessionState.COMPLETED)

    when:
    def locked = manager.tryLockForDreaming("s1")

    then:
    locked == true
    manager.getState("s1") == SessionState.DREAMING
  }

  def "tryLockForDreaming succeeds for CRASHED session"() {
    given:
    manager.setInitialState("s1", SessionState.CRASHED)

    when:
    def locked = manager.tryLockForDreaming("s1")

    then:
    locked == true
    manager.getState("s1") == SessionState.DREAMING
  }

  def "tryLockForDreaming fails for RUNNING session"() {
    given:
    manager.setInitialState("s1", SessionState.RUNNING)

    expect:
    manager.tryLockForDreaming("s1") == false
    manager.getState("s1") == SessionState.RUNNING
  }

  def "tryLockForDreaming fails for DREAMING session"() {
    given:
    manager.setInitialState("s1", SessionState.DREAMING)

    expect:
    manager.tryLockForDreaming("s1") == false
    manager.getState("s1") == SessionState.DREAMING
  }

  def "tryLockForDreaming fails for ARCHIVED session"() {
    given:
    manager.setInitialState("s1", SessionState.ARCHIVED)

    expect:
    manager.tryLockForDreaming("s1") == false
    manager.getState("s1") == SessionState.ARCHIVED
  }

  def "tryLockForDreaming is atomic - second call returns false"() {
    given:
    manager.setInitialState("s1", SessionState.COMPLETED)
    def firstLock = manager.tryLockForDreaming("s1")

    when:
    def secondLock = manager.tryLockForDreaming("s1")

    then:
    firstLock == true
    secondLock == false
    manager.getState("s1") == SessionState.DREAMING
  }
}
