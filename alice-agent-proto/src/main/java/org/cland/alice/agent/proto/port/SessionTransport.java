/*
 * Alice Agent — 会话传输端口（协议契约）
 *
 * transport 把外部帧翻译成 AgentCommand / StepEvent 的通道；实现见 alice-agent-runtime
 * （InProcess / StdioJsonl / HttpSse / ACP）。
 */
package org.cland.alice.agent.proto.port;

/**
 * 会话传输端口 — transport 生命周期契约。
 *
 * <p>实现纪律（详见 docs/alice-agent-proto/PROTOCOL.md）：
 *
 * <ul>
 *   <li>编解码**只准**用 proto 的 {@code CommandCodec}/{@code EventCodec}，不得自定义 JSON
 *   <li>一轮一锁 / 单写者由宿主（{@link AgentCommandDispatcher} 实现）负责，transport 不抢写
 *   <li>错误帧与状态码映射遵守协议 §4（400/409/503…）
 * </ul>
 */
public interface SessionTransport extends AutoCloseable {

  /**
   * 传输名。
   *
   * @return 短名（{@code inprocess} / {@code stdio} / {@code http} / {@code acp}）
   */
  String name();

  /**
   * 绑定宿主并开始服务。
   *
   * <p>实现可同步阻塞（如 stdio 读循环）或后台线程（如 HTTP server）；返回后视为"已就绪"。
   *
   * @param dispatcher 宿主分发端口（不得为 null）
   * @throws Exception 启动失败（端口占用等）——调用方应给出可读报错并退出
   */
  void start(AgentCommandDispatcher dispatcher) throws Exception;

  /** 优雅停止（幂等；不得抛异常）。 */
  @Override
  void close();
}
