package org.cland.alice.agent.proto.port;

/**
 * 命令校验失败 — 字段缺失/非法/协议版本不受支持。
 *
 * <p>协议面映射：HTTP {@code 400 Bad Request}；stdio 返回错误帧（不执行命令）。
 */
public class CommandValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message 可读原因（面向调用方/运维）
   */
  public CommandValidationException(String message) {
    super(message);
  }

  /**
   * @param message 可读原因
   * @param cause 底层原因（各类解析异常）
   */
  public CommandValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
