package org.cland.alice.env.adapter.snapshot

import spock.lang.Specification
import java.time.Instant

class EnvSnapshotSpec extends Specification {

  def "should create empty snapshot"() {
    when:
    def snap = EnvSnapshot.empty()

    then:
    snap.snapshotId() != null
    snap.timestamp() != null
    snap.resourceVersions().isEmpty()
    snap.workingDirectoryState().isEmpty()
    snap.environmentVariables().isEmpty()
    snap.irreversibleEffects().isEmpty()
    !snap.hasIrreversibleEffects()
  }

  def "should build snapshot with all fields"() {
    given:
    def now = Instant.now()

    when:
    def snap = EnvSnapshot.builder()
      .snapshotId("snap-001")
      .timestamp(now)
      .resourceVersions([uri1: "v1", uri2: "v2"])
      .workingDirectoryState([file1: [size: 100], file2: [size: 200]])
      .environmentVariables([HOME: "/root", PATH: "/usr/bin"])
      .irreversibleEffects([
        new EnvSnapshot.IrreversibleSideEffect("email", "Sent email"),
        new EnvSnapshot.IrreversibleSideEffect("tweet", "Posted tweet")
      ])
      .build()

    then:
    snap.snapshotId() == "snap-001"
    snap.timestamp() == now
    snap.resourceVersions() == [uri1: "v1", uri2: "v2"]
    snap.workingDirectoryState() == [file1: [size: 100], file2: [size: 200]]
    snap.environmentVariables() == [HOME: "/root", PATH: "/usr/bin"]
    snap.irreversibleEffects().size() == 2
    snap.hasIrreversibleEffects()
  }

  def "should build with addIrreversibleEffect"() {
    when:
    def snap = EnvSnapshot.builder()
      .snapshotId("test")
      .addIrreversibleEffect(new EnvSnapshot.IrreversibleSideEffect(
        "deploy", "Deployed to production", "Rollback deployment", Instant.now()))
      .build()

    then:
    snap.irreversibleEffects().size() == 1
    snap.irreversibleEffects()[0].action() == "deploy"
    snap.irreversibleEffects()[0].compensationSuggestion() == "Rollback deployment"
  }

  def "should enforce non-null snapshotId"() {
    when:
    EnvSnapshot.builder().build()

    then:
    thrown(NullPointerException)
  }

  def "should return immutable maps"() {
    given:
    def snap = EnvSnapshot.empty()

    when:
    snap.resourceVersions().put("new", "value")

    then:
    thrown(UnsupportedOperationException)
  }

  def "should create irreversible side effect with default timestamp"() {
    when:
    def effect = new EnvSnapshot.IrreversibleSideEffect("action", "desc")

    then:
    effect.action() == "action"
    effect.description() == "desc"
    effect.compensationSuggestion() == null
    effect.occurredAt() != null
  }

  def "should create irreversible side effect with compensation"() {
    given:
    def now = Instant.now()

    when:
    def effect = new EnvSnapshot.IrreversibleSideEffect(
      "send_email", "Sent welcome", "Send retraction", now)

    then:
    effect.action() == "send_email"
    effect.description() == "Sent welcome"
    effect.compensationSuggestion() == "Send retraction"
    effect.occurredAt() == now
  }

  def "irreversible side effect should enforce non-null action"() {
    when:
    new EnvSnapshot.IrreversibleSideEffect(null, "desc")

    then:
    thrown(NullPointerException)
  }

  def "toString should contain snapshot id and counts"() {
    given:
    def snap = EnvSnapshot.builder()
      .snapshotId("my-snap")
      .resourceVersions([r1: "v1"])
      .workingDirectoryState([f1: [:], f2: [:]])
      .addIrreversibleEffect(new EnvSnapshot.IrreversibleSideEffect("act", "desc"))
      .build()

    expect:
    snap.toString().contains("my-snap")
    snap.toString().contains("resources=1")
    snap.toString().contains("files=2")
    snap.toString().contains("irreversible=1")
  }
}
