package org.cland.alice.core.agent.wal;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Snowflake ID generator matching the standard SnowflakeIdWorker algorithm.
 *
 * <p>ID layout (64-bit):
 *
 * <pre>
 *   | 1 bit sign (0) | 41 bits timestamp (ms offset from epoch) | 10 bits workerId | 12 bits sequence |
 * </pre>
 *
 * <p>This generator can produce two kinds of IDs:
 *
 * <ul>
 *   <li><b>Message IDs</b> — via {@link #nextId()} (implements {@link MessageIdGenerator})
 *   <li><b>Session IDs</b> — via {@link #nextSessionId()}, which uses a random workerId per call to
 *       avoid collision across sessions
 * </ul>
 *
 * <p>Thread-safe. Clock-backward-safe (throws {@link IllegalStateException}).
 */
public final class SnowflakeIdGenerator implements MessageIdGenerator {

  // ── Constants ────────────────────────────────────────────────────────────

  /** Epoch: 2020-01-01T00:00:00Z (matches the provided SnowflakeIdWorker code) */
  private static final long TWEPOCH = 1577836800000L;

  /** Worker ID bits */
  private static final long WORKER_ID_BITS = 10L;

  /** Sequence bits */
  private static final long SEQUENCE_BITS = 12L;

  /** Max worker ID */
  private static final long MAX_WORKER_ID = -1L ^ (-1L << WORKER_ID_BITS);

  /** Worker ID shift */
  private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;

  /** Timestamp shift */
  private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

  /** Sequence mask */
  private static final long SEQUENCE_MASK = -1L ^ (-1L << SEQUENCE_BITS);

  // ── State ────────────────────────────────────────────────────────────────

  private final long workerId;
  private long sequence = 0L;
  private long lastTimestamp = -1L;

  /** Singleton instance for global use. */
  private static final SnowflakeIdGenerator INSTANCE = new SnowflakeIdGenerator();

  // ── Constructors ─────────────────────────────────────────────────────────

  /** Creates a SnowflakeIdGenerator with worker ID 0 and epoch 2020-01-01. */
  public SnowflakeIdGenerator() {
    this(0L);
  }

  /**
   * Creates a SnowflakeIdGenerator with the given worker ID.
   *
   * @param workerId the worker/node ID (0-1023)
   */
  public SnowflakeIdGenerator(long workerId) {
    if (workerId < 0 || workerId > MAX_WORKER_ID) {
      throw new IllegalArgumentException(
          "workerId must be between 0 and " + MAX_WORKER_ID + ", got " + workerId);
    }
    this.workerId = workerId;
  }

  // ── Singleton accessor ─────────────────────────────────────────────────

  /**
   * Returns the global singleton instance (worker ID 0).
   *
   * @return the singleton SnowflakeIdGenerator
   */
  public static SnowflakeIdGenerator getInstance() {
    return INSTANCE;
  }

  // ── Message ID generation (MessageIdGenerator) ──────────────────────────

  @Override
  public synchronized long nextId() {
    long timestamp = System.currentTimeMillis();

    // Clock rollback check
    if (timestamp < lastTimestamp) {
      throw new IllegalStateException(
          "Clock moved backwards. Refusing to generate ID for "
              + (lastTimestamp - timestamp)
              + "ms");
    }

    if (lastTimestamp == timestamp) {
      sequence = (sequence + 1) & SEQUENCE_MASK;
      if (sequence == 0) {
        // Sequence exhausted in this ms — wait for next millisecond
        timestamp = tilNextMillis(lastTimestamp);
      }
    } else {
      sequence = 0L;
    }

    lastTimestamp = timestamp;
    return ((timestamp - TWEPOCH) << TIMESTAMP_SHIFT) | (workerId << WORKER_ID_SHIFT) | sequence;
  }

  // ── Session ID generation ───────────────────────────────────────────────

  /**
   * Generates a unique session ID string using a random worker ID.
   *
   * <p>Format: hex string of a Snowflake ID using a random workerId per call. This ensures
   * uniqueness across sessions even on the same JVM.
   *
   * @return a hex session ID string (lowercase)
   */
  public String nextSessionId() {
    // Generate ID with a random worker ID to avoid session ID collisions
    long timestamp = System.currentTimeMillis();
    long randomWorker = ThreadLocalRandom.current().nextLong(MAX_WORKER_ID + 1);
    long seq = ThreadLocalRandom.current().nextLong(SEQUENCE_MASK + 1);
    long id = ((timestamp - TWEPOCH) << TIMESTAMP_SHIFT) | (randomWorker << WORKER_ID_SHIFT) | seq;
    return Long.toHexString(id);
  }

  /**
   * Generates a session ID string with the fixed worker ID of this generator.
   *
   * @return a hex session ID string (lowercase)
   */
  public String nextSessionIdWithWorker() {
    return Long.toHexString(nextId());
  }

  /**
   * Static convenience method to generate a unique session ID without creating an instance.
   *
   * <p>Uses a random worker ID per call, ensuring uniqueness across sessions.
   *
   * @return a hex session ID string (lowercase), e.g. "1a2b3c4d5e6f"
   */
  public static String generateSessionId() {
    long timestamp = System.currentTimeMillis();
    long randomWorker = ThreadLocalRandom.current().nextLong(MAX_WORKER_ID + 1);
    long seq = ThreadLocalRandom.current().nextLong(SEQUENCE_MASK + 1);
    long id = ((timestamp - TWEPOCH) << TIMESTAMP_SHIFT) | (randomWorker << WORKER_ID_SHIFT) | seq;
    return Long.toHexString(id);
  }

  // ── Clock rollback helper ────────────────────────────────────────────────

  private long tilNextMillis(long lastTimestamp) {
    long timestamp = System.currentTimeMillis();
    while (timestamp <= lastTimestamp) {
      timestamp = System.currentTimeMillis();
    }
    return timestamp;
  }

  // ── ID extraction helpers ───────────────────────────────────────────────

  /**
   * Extracts the timestamp (ms since epoch) from a Snowflake ID.
   *
   * @param id the Snowflake ID
   * @return the timestamp in milliseconds
   */
  public static long extractTimestamp(long id) {
    return (id >>> TIMESTAMP_SHIFT) + TWEPOCH;
  }

  /**
   * Extracts the worker ID from a Snowflake ID.
   *
   * @param id the Snowflake ID
   * @return the worker ID (0-1023)
   */
  public static long extractWorkerId(long id) {
    return (id >>> WORKER_ID_SHIFT) & MAX_WORKER_ID;
  }

  /**
   * Extracts the sequence number from a Snowflake ID.
   *
   * @param id the Snowflake ID
   * @return the sequence number (0-4095)
   */
  public static long extractSequence(long id) {
    return id & SEQUENCE_MASK;
  }

  @Override
  public String toString() {
    return "SnowflakeIdGenerator{workerId=" + workerId + "}";
  }
}
