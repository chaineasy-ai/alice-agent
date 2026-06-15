/*
 * Alice Agent — AcpConnection
 *
 * ACP 连接状态 — 跟踪与外部 ACP 协议兼容 Agent 的连接。
 */
package org.cland.alice.agent.internal.acp;

import java.net.URI;
import java.util.Objects;

/**
 * ACP 连接状态记录。
 *
 * <p>表示与外部 ACP 协议兼容 Agent 的单个连接，包括端点 URL 和连接状态。
 *
 * @param name 连接名称/别名
 * @param endpoint ACP 端点 URL
 * @param connected 是否已成功连接
 * @param sessionId 当前 ACP 会话 ID（初始化后设置）
 */
public record AcpConnection(String name, URI endpoint, boolean connected, String sessionId) {

  /** ACP 协议端点路径后缀 */
  public static final String DEFAULT_ACP_PATH = "/acp";

  /**
   * 创建一个连接。
   *
   * @param name 连接名称/别名
   * @param endpoint ACP 端点 URL
   */
  public AcpConnection {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(endpoint, "endpoint must not be null");
  }

  /**
   * 创建一个状态为已连接的记录。
   *
   * @param name 连接名称
   * @param endpoint ACP 端点
   * @param sessionId ACP 会话 ID
   * @return 已连接状态的 AcpConnection
   */
  public static AcpConnection connected(String name, URI endpoint, String sessionId) {
    return new AcpConnection(name, endpoint, true, sessionId);
  }

  /**
   * 创建一个状态为未连接的记录。
   *
   * @param name 连接名称
   * @param endpoint ACP 端点
   * @return 未连接状态的 AcpConnection
   */
  public static AcpConnection disconnected(String name, URI endpoint) {
    return new AcpConnection(name, endpoint, false, null);
  }
}
