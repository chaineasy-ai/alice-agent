package org.cland.alice.memory.dreaming;

/** 当尝试进行无效的会话状态转换时抛出的运行时异常。 */
public class StateTransitionException extends RuntimeException {

  private final String sessionId;
  private final SessionState from;
  private final SessionState to;

  /**
   * @param sessionId 涉及的会话 ID
   * @param from 当前状态
   * @param to 尝试转换的目标状态
   */
  public StateTransitionException(String sessionId, SessionState from, SessionState to) {
    super("Invalid state transition: " + from + " → " + to + " for session=" + sessionId);
    this.sessionId = sessionId;
    this.from = from;
    this.to = to;
  }

  public StateTransitionException(
      String sessionId, SessionState from, SessionState to, String message) {
    super(
        "Invalid state transition: "
            + from
            + " → "
            + to
            + " for session="
            + sessionId
            + ": "
            + message);
    this.sessionId = sessionId;
    this.from = from;
    this.to = to;
  }

  public StateTransitionException(
      String sessionId, SessionState from, SessionState to, Throwable cause) {
    super("Invalid state transition: " + from + " → " + to + " for session=" + sessionId, cause);
    this.sessionId = sessionId;
    this.from = from;
    this.to = to;
  }

  /** 获取涉及的会话 ID。 */
  public String getSessionId() {
    return sessionId;
  }

  /** 获取当前状态。 */
  public SessionState getFrom() {
    return from;
  }

  /** 获取尝试转换的目标状态。 */
  public SessionState getTo() {
    return to;
  }
}
