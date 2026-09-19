/*
 * Alice Agent — 统一标识生成（会话/链路）
 *
 * 各门面不再各自造 ID（曾出现 traceId 口径不一：UUID[0:12] vs Snowflake ✗）。
 */
package org.cland.alice.runtime.compose;

import java.util.UUID;
import org.cland.alice.core.agent.wal.SnowflakeIdGenerator;

/** 会话/链路标识的统一生成口（门面与协议适配器共用）。 */
public final class Ids {

  private Ids() {}

  /**
   * 新会话标识（雪花 ID）。
   *
   * @return 会话 ID
   */
  public static String newSessionId() {
    return SnowflakeIdGenerator.generateSessionId();
  }

  /**
   * 新链路标识（12 位短 UUID）。
   *
   * @return traceId
   */
  public static String newTraceId() {
    return UUID.randomUUID().toString().substring(0, 12);
  }
}
