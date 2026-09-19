package org.cland.alice.agent.proto.port;

/**
 * 分发忙 — 同一会话已有一轮在执行（一轮一锁）。
 *
 * <p>协议面映射：HTTP {@code 409 Conflict}；调用方可等待当前轮，或把消息改走 steer 通道 （人工插话 / {@code
 * streamingBehavior=steer} 语义）。
 */
public class DispatcherBusyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message 可读原因（建议含 sessionId 与当前轮信息）
   */
  public DispatcherBusyException(String message) {
    super(message);
  }
}
