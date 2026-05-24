package org.cland.alice.env.adapter

import spock.lang.Specification
import java.time.Instant

class EnvEventSpec extends Specification {

  def "should create event with all fields"() {
    given:
    def now = Instant.now()

    when:
    def event = new EnvEvent(
      EnvEvent.Type.CLIENT_CONNECTED,
      "server-1",
      [tools: 5, resources: 3],
      now
    )

    then:
    event.type() == EnvEvent.Type.CLIENT_CONNECTED
    event.source() == "server-1"
    event.data() == [tools: 5, resources: 3]
    event.timestamp() == now
  }

  def "should use default timestamp when null"() {
    when:
    def event = new EnvEvent(EnvEvent.Type.ACTION_EXECUTED, "tool", [:], null)

    then:
    event.timestamp() != null
  }

  def "should use empty map when data is null"() {
    when:
    def event = new EnvEvent(EnvEvent.Type.RESOURCE_CHANGED, "src", null, Instant.now())

    then:
    event.data().isEmpty()
  }

  def "should return immutable data"() {
    given:
    def event = new EnvEvent(EnvEvent.Type.SNAPSHOT_CAPTURED, "env", [key: "value"], Instant.now())

    when:
    event.data().put("newKey", "newValue")

    then:
    thrown(UnsupportedOperationException)
  }

  def "should enforce non-null type"() {
    when:
    new EnvEvent(null, "src", [:], Instant.now())

    then:
    thrown(NullPointerException)
  }

  def "toString should contain type and source"() {
    expect:
    new EnvEvent(EnvEvent.Type.CLIENT_CONNECTED, "svr", [:], Instant.now())
      .toString()
      .contains("CLIENT_CONNECTED")
    new EnvEvent(EnvEvent.Type.CLIENT_CONNECTED, "svr", [:], Instant.now())
      .toString()
      .contains("svr")
  }

  def "should have all expected event types"() {
    expect:
    EnvEvent.Type.values() as Set == [
      EnvEvent.Type.CLIENT_CONNECTED,
      EnvEvent.Type.CLIENT_DISCONNECTED,
      EnvEvent.Type.RESOURCE_CHANGED,
      EnvEvent.Type.ACTION_EXECUTED,
      EnvEvent.Type.ACTION_FAILED,
      EnvEvent.Type.SNAPSHOT_CAPTURED,
      EnvEvent.Type.ROLLBACK_PERFORMED,
      EnvEvent.Type.STATE_COMMITTED
    ] as Set
  }
}
