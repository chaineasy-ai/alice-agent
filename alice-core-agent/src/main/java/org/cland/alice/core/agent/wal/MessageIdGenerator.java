package org.cland.alice.core.agent.wal;

/**
 * Strategy interface for distributed, session-wide globally ordered message ID generation.
 *
 * <p>Per the WAL specification (§2.2), local in-memory auto-increment is strictly forbidden in
 * distributed clusters. All ID generation must use a globally ordered scheme (e.g., Snowflake,
 * Leaf, UID Generator).
 *
 * <p>Implementations must be thread-safe.
 *
 * @see SnowflakeIdGenerator
 */
@FunctionalInterface
public interface MessageIdGenerator {

  /**
   * Generates the next globally ordered ID.
   *
   * @return a positive, globally unique, monotonically increasing ID
   */
  long nextId();

  /**
   * Returns a default in-memory generator for development/testing. Uses a simple AtomicLong for
   * single-JVM environments.
   *
   * @return a simple sequential ID generator
   */
  static MessageIdGenerator simple() {
    return new MessageIdGenerator() {
      private final java.util.concurrent.atomic.AtomicLong seq =
          new java.util.concurrent.atomic.AtomicLong(1);

      @Override
      public long nextId() {
        return seq.getAndIncrement();
      }
    };
  }

  /**
   * Returns a Snowflake-based generator for production environments.
   *
   * @param shardId the shard/node ID (0-1023)
   * @return a Snowflake ID generator
   */
  static MessageIdGenerator snowflake(int shardId) {
    return new SnowflakeIdGenerator(shardId);
  }
}
