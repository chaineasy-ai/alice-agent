/*
 * Alice Agent — AcpClientException
 *
 * ACP 客户端异常 — 包装 ACP SDK 异常为用户友好的运行时异常。
 */
package org.cland.alice.agent.internal.acp;

/**
 * ACP 客户端运行时异常。
 *
 * <p>包装 ACP SDK 的受检异常，提供清晰的错误消息和原因追踪。
 */
public class AcpClientException extends RuntimeException {

  /**
   * 创建 ACP 客户端异常。
   *
   * @param message 错误描述
   * @param cause 原始异常原因
   */
  public AcpClientException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * 创建 ACP 客户端异常（无原因）。
   *
   * @param message 错误描述
   */
  public AcpClientException(String message) {
    super(message);
  }
}
