package org.cland.alice.memory.dreaming

import spock.lang.Specification
import org.cland.alice.memory.dreaming.DreamingSession.DreamingOutcome

class DreamingSessionSpec extends Specification {

  def "should create SUCCESS session"() {
    when:
    def session = new DreamingSession(
        "session-1", 1000L, 2000L, 1000L,
        "summary-1", 2, 1, DreamingOutcome.SUCCESS
    )

    then:
    session.sessionId() == "session-1"
    session.startTime() == 1000L
    session.endTime() == 2000L
    session.durationMs() == 1000L
    session.episodicSummaryId() == "summary-1"
    session.conflictCount() == 2
    session.patternsCrystallized() == 1
    session.outcome() == DreamingOutcome.SUCCESS
  }

  def "should create FAILURE session with nullable fields"() {
    when:
    def session = new DreamingSession(
        "session-2", 1000L, null, null,
        null, 0, 0, DreamingOutcome.FAILURE
    )

    then:
    session.sessionId() == "session-2"
    session.endTime() == null
    session.durationMs() == null
    session.episodicSummaryId() == null
    session.outcome() == DreamingOutcome.FAILURE
  }

  def "should create SKIPPED session"() {
    when:
    def session = new DreamingSession(
        "session-3", 1000L, 1000L, 0L,
        null, 0, 0, DreamingOutcome.SKIPPED
    )

    then:
    session.outcome() == DreamingOutcome.SKIPPED
  }

  def "should reject null sessionId"() {
    when:
    new DreamingSession(null, 0, null, null, null, 0, 0, DreamingOutcome.SUCCESS)

    then:
    thrown(NullPointerException)
  }

  def "should reject blank sessionId"() {
    when:
    new DreamingSession("  ", 0, null, null, null, 0, 0, DreamingOutcome.SUCCESS)

    then:
    thrown(IllegalArgumentException)
  }

  def "should reject null outcome"() {
    when:
    new DreamingSession("s1", 0, null, null, null, 0, 0, null)

    then:
    thrown(NullPointerException)
  }

  def "should support all DreamingOutcome values"() {
    expect:
    DreamingOutcome.values() as Set == [DreamingOutcome.SUCCESS, DreamingOutcome.FAILURE, DreamingOutcome.SKIPPED] as Set
  }

  def "should be immutable"() {
    given:
    def session = new DreamingSession(
        "s1", 1000L, 2000L, 1000L,
        "sum-1", 1, 1, DreamingOutcome.SUCCESS
    )

    expect:
    session instanceof Record
  }

  def "should implement equals and hashCode"() {
    given:
    def s1 = new DreamingSession("s1", 1000L, 2000L, 1000L, "sum-1", 1, 1, DreamingOutcome.SUCCESS)
    def s2 = new DreamingSession("s1", 1000L, 2000L, 1000L, "sum-1", 1, 1, DreamingOutcome.SUCCESS)

    expect:
    s1 == s2
    s1.hashCode() == s2.hashCode()
  }
}
