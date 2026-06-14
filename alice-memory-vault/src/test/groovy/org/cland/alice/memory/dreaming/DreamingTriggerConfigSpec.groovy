package org.cland.alice.memory.dreaming

import spock.lang.Specification

class DreamingTriggerConfigSpec extends Specification {

  def "should create default config without error"() {
    when:
    def config = DreamingTriggerConfig.defaults()

    then:
    config.idleCooldownMs() == DreamingTriggerConfig.DEFAULT_IDLE_COOLDOWN_MS
    config.walThresholdEntries() == DreamingTriggerConfig.DEFAULT_WAL_THRESHOLD_ENTRIES
    config.walThresholdBytes() == DreamingTriggerConfig.DEFAULT_WAL_THRESHOLD_BYTES
    config.pollingIntervalMs() == DreamingTriggerConfig.DEFAULT_POLLING_INTERVAL_MS
    config.maxConcurrency() == DreamingTriggerConfig.DEFAULT_MAX_CONCURRENCY
    config.maxStepsPerCycle() == DreamingTriggerConfig.DEFAULT_MAX_STEPS_PER_CYCLE
  }

  def "should create config with custom values"() {
    when:
    def config = new DreamingTriggerConfig(5000, 100, 1_048_576, 10_000, 2, 50)

    then:
    config.idleCooldownMs() == 5000
    config.walThresholdEntries() == 100
    config.walThresholdBytes() == 1_048_576
    config.pollingIntervalMs() == 10_000
    config.maxConcurrency() == 2
    config.maxStepsPerCycle() == 50
  }

  def "should reject idleCooldownMs < 1000"() {
    when:
    new DreamingTriggerConfig(999, 500, 10_485_760, 30_000, 1, 1000)

    then:
    thrown(IllegalArgumentException)
  }

  def "should accept idleCooldownMs == 1000"() {
    when:
    def config = new DreamingTriggerConfig(1000, 500, 10_485_760, 30_000, 1, 1000)

    then:
    config.idleCooldownMs() == 1000
  }

  def "should reject walThresholdEntries < 10"() {
    when:
    new DreamingTriggerConfig(60_000, 9, 10_485_760, 30_000, 1, 1000)

    then:
    thrown(IllegalArgumentException)
  }

  def "should accept walThresholdEntries == 10"() {
    when:
    def config = new DreamingTriggerConfig(60_000, 10, 10_485_760, 30_000, 1, 1000)

    then:
    config.walThresholdEntries() == 10
  }

  def "should reject walThresholdBytes < 1024"() {
    when:
    new DreamingTriggerConfig(60_000, 500, 1023, 30_000, 1, 1000)

    then:
    thrown(IllegalArgumentException)
  }

  def "should reject pollingIntervalMs < 1000"() {
    when:
    new DreamingTriggerConfig(60_000, 500, 10_485_760, 999, 1, 1000)

    then:
    thrown(IllegalArgumentException)
  }

  def "should reject maxConcurrency < 1"() {
    when:
    new DreamingTriggerConfig(60_000, 500, 10_485_760, 30_000, 0, 1000)

    then:
    thrown(IllegalArgumentException)
  }

  def "should reject maxStepsPerCycle < 10"() {
    when:
    new DreamingTriggerConfig(60_000, 500, 10_485_760, 30_000, 1, 9)

    then:
    thrown(IllegalArgumentException)
  }

  def "should accept maxStepsPerCycle == 10"() {
    when:
    def config = new DreamingTriggerConfig(60_000, 500, 10_485_760, 30_000, 1, 10)

    then:
    config.maxStepsPerCycle() == 10
  }

  def "should be immutable"() {
    given:
    def config = DreamingTriggerConfig.defaults()

    expect:
    config instanceof Record
  }
}
