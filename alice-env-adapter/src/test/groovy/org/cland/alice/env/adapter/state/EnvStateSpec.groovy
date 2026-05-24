package org.cland.alice.env.adapter.state

import spock.lang.Specification

class EnvStateSpec extends Specification {

  def "READY should be the only executable state"() {
    expect:
    state.canExecute() == expected

    where:
    state                        | expected
    EnvState.READY               | true
    EnvState.DISCONNECTED        | false
    EnvState.INITIALIZING        | false
    EnvState.CAPTURING_SNAPSHOT  | false
    EnvState.EXECUTING           | false
    EnvState.AUDITING            | false
    EnvState.COMMITTED           | false
    EnvState.ROLLING_BACK        | false
  }

  def "DISCONNECTED and COMMITTED should be terminal states"() {
    expect:
    state.isTerminal() == expected

    where:
    state                        | expected
    EnvState.DISCONNECTED        | true
    EnvState.COMMITTED           | true
    EnvState.READY               | false
    EnvState.INITIALIZING        | false
    EnvState.CAPTURING_SNAPSHOT  | false
    EnvState.EXECUTING           | false
    EnvState.AUDITING            | false
    EnvState.ROLLING_BACK        | false
  }

  def "INITIALIZING, CAPTURING, EXECUTING, AUDITING, ROLLING_BACK should be transitional"() {
    expect:
    state.isTransitional() == expected

    where:
    state                        | expected
    EnvState.INITIALIZING        | true
    EnvState.CAPTURING_SNAPSHOT  | true
    EnvState.EXECUTING           | true
    EnvState.AUDITING            | true
    EnvState.ROLLING_BACK        | true
    EnvState.READY               | false
    EnvState.DISCONNECTED        | false
    EnvState.COMMITTED           | false
  }

  def "should have all expected enum values"() {
    expect:
    EnvState.values() as Set == [
      EnvState.DISCONNECTED,
      EnvState.INITIALIZING,
      EnvState.READY,
      EnvState.CAPTURING_SNAPSHOT,
      EnvState.EXECUTING,
      EnvState.AUDITING,
      EnvState.COMMITTED,
      EnvState.ROLLING_BACK
    ] as Set
  }
}
