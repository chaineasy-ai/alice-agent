package org.cland.alice.env.adapter.snapshot

import spock.lang.Specification
import java.time.Instant

class SnapshotManagerSpec extends Specification {

  def "should save and retrieve snapshots"() {
    given:
    def mgr = new SnapshotManager()
    def snap = EnvSnapshot.builder()
      .snapshotId("s1")
      .timestamp(Instant.now())
      .build()

    when:
    mgr.save(snap)

    then:
    mgr.historySize() == 1
    mgr.latestSnapshot().isPresent()
    mgr.latestSnapshot().get().snapshotId() == "s1"
  }

  def "should rollback to latest snapshot"() {
    given:
    def mgr = new SnapshotManager()
    def snap = EnvSnapshot.builder()
      .snapshotId("s1")
      .build()
    mgr.save(snap)

    when:
    def rolledBack = mgr.rollback()

    then:
    rolledBack.isPresent()
    rolledBack.get().snapshotId() == "s1"
    // Snapshot is still in history
    mgr.historySize() == 1
  }

  def "should return empty on rollback when history is empty"() {
    given:
    def mgr = new SnapshotManager()

    expect:
    mgr.rollback().isEmpty()
  }

  def "should rollback to specific snapshot"() {
    given:
    def mgr = new SnapshotManager()
    def snap1 = EnvSnapshot.builder().snapshotId("s1").build()
    def snap2 = EnvSnapshot.builder().snapshotId("s2").build()
    def snap3 = EnvSnapshot.builder().snapshotId("s3").build()
    mgr.save(snap1)
    mgr.save(snap2)
    mgr.save(snap3)

    when:
    def found = mgr.rollbackTo("s1")

    then:
    found.isPresent()
    found.get().snapshotId() == "s1"
  }

  def "should return empty when rolling back to unknown snapshot"() {
    given:
    def mgr = new SnapshotManager()
    mgr.save(EnvSnapshot.builder().snapshotId("s1").build())

    expect:
    mgr.rollbackTo("unknown").isEmpty()
    mgr.rollbackTo(null).isEmpty()
  }

  def "should commit the latest snapshot"() {
    given:
    def mgr = new SnapshotManager()
    def snap = EnvSnapshot.builder().snapshotId("committed").build()
    mgr.save(snap)
    assert !mgr.committedSnapshot().isPresent()

    when:
    mgr.commit()

    then:
    mgr.committedSnapshot().isPresent()
    mgr.committedSnapshot().get().snapshotId() == "committed"
  }

  def "should not fail when committing empty history"() {
    given:
    def mgr = new SnapshotManager()

    when:
    mgr.commit()

    then:
    !mgr.committedSnapshot().isPresent()
  }

  def "should enforce max history size"() {
    given:
    def mgr = new SnapshotManager(3)

    when:
    mgr.save(EnvSnapshot.builder().snapshotId("s1").build())
    mgr.save(EnvSnapshot.builder().snapshotId("s2").build())
    mgr.save(EnvSnapshot.builder().snapshotId("s3").build())
    mgr.save(EnvSnapshot.builder().snapshotId("s4").build())  // evicts s1

    then:
    mgr.historySize() == 3
    mgr.rollbackTo("s1").isEmpty()  // s1 was evicted
    mgr.rollbackTo("s4").isPresent() // s4 is newest
  }

  def "should throw on invalid max history size"() {
    when:
    new SnapshotManager(0)

    then:
    thrown(IllegalArgumentException)
  }

  def "should fail on null snapshot save"() {
    given:
    def mgr = new SnapshotManager()

    when:
    mgr.save(null)

    then:
    thrown(IllegalArgumentException)
  }

  def "should clear all history"() {
    given:
    def mgr = new SnapshotManager()
    mgr.save(EnvSnapshot.builder().snapshotId("s1").build())
    mgr.save(EnvSnapshot.builder().snapshotId("s2").build())
    mgr.commit()

    when:
    mgr.clear()

    then:
    mgr.historySize() == 0
    // commit also cleared
    !mgr.committedSnapshot().isPresent()
  }

  def "should keep committed snapshot after eviction"() {
    given:
    def mgr = new SnapshotManager(2)
    def snap = EnvSnapshot.builder().snapshotId("important").build()
    mgr.save(snap)
    mgr.commit()

    when:
    mgr.save(EnvSnapshot.builder().snapshotId("s2").build())
    mgr.save(EnvSnapshot.builder().snapshotId("s3").build())  // evicts "important" from history

    then:
    mgr.historySize() == 2
    // committed snapshot is still accessible even though evicted from history
    mgr.committedSnapshot().isPresent()
    mgr.committedSnapshot().get().snapshotId() == "important"
    mgr.rollbackTo("important").isEmpty()  // not in history anymore
  }

  // ========== Diff ==========

  def "diff should detect resource changes"() {
    given:
    def before = EnvSnapshot.builder()
      .snapshotId("b")
      .resourceVersions([uriA: "v1", uriB: "v1"])
      .build()
    def after = EnvSnapshot.builder()
      .snapshotId("a")
      .resourceVersions([uriA: "v2", uriC: "v1"])
      .build()

    when:
    def diff = SnapshotManager.diff(before, after)

    then:
    diff.hasChanges()
    diff.entries().size() >= 3  // uriA changed, uriB removed, uriC added
    diff.entries().any { it.type() == "resource_changed" && it.key() == "uriA" }
    diff.entries().any { it.type() == "resource_removed" && it.key() == "uriB" }
    diff.entries().any { it.type() == "resource_added" && it.key() == "uriC" }
  }

  def "diff should detect file changes"() {
    given:
    def before = EnvSnapshot.builder()
      .snapshotId("b")
      .workingDirectoryState([file1: "data1", file2: "data2"])
      .build()
    def after = EnvSnapshot.builder()
      .snapshotId("a")
      .workingDirectoryState([file2: "data2", file3: "data3"])
      .build()

    when:
    def diff = SnapshotManager.diff(before, after)

    then:
    diff.hasChanges()
    diff.entries().any { it.type() == "file_added" && it.key() == "file3" }
    diff.entries().any { it.type() == "file_removed" && it.key() == "file1" }
  }

  def "diff should detect irreversible effects"() {
    given:
    def before = EnvSnapshot.builder().snapshotId("b").build()
    def after = EnvSnapshot.builder()
      .snapshotId("a")
      .addIrreversibleEffect(new EnvSnapshot.IrreversibleSideEffect(
        "send_email", "Sent welcome email", "Send retraction", Instant.now()))
      .build()

    when:
    def diff = SnapshotManager.diff(before, after)

    then:
    diff.hasChanges()
    diff.entries().any { it.type() == "irreversible_effect" }
  }

  def "diff should report no changes for identical snapshots"() {
    given:
    def before = EnvSnapshot.builder()
      .snapshotId("s")
      .resourceVersions([uri: "v"])
      .build()
    def after = EnvSnapshot.builder()
      .snapshotId("s")
      .resourceVersions([uri: "v"])
      .build()

    when:
    def diff = SnapshotManager.diff(before, after)

    then:
    !diff.hasChanges()
    diff.summary() == "No changes detected."
  }

  def "diff should handle null input"() {
    when:
    def diff = SnapshotManager.diff(null, EnvSnapshot.empty())

    then:
    !diff.hasChanges()
    diff.summary().contains("Cannot diff")
  }
}
