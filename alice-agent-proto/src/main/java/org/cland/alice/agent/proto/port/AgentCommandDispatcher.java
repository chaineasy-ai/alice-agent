/*
 * Alice Agent — 命令分发端口（协议契约）
 *
 * 实现归属 alice-agent-runtime（会话语义：单写者/一轮一锁/超时 abort/new-resume）；
 * 调用者：所有 transport（InProcess/stdio/HTTP+SSE/ACP）与门面。
 */
package org.cland.alice.agent.proto.port;

import java.util.concurrent.Flow;
import org.cland.alice.agent.proto.AgentCommand;
import org.cland.alice.agent.proto.event.StepEvent;

/**
 * 命令分发端口 — 契约层唯一的"执行入口"。
 *
 * <p>实现约定（详见 docs/alice-agent-proto/PROTOCOL.md）：
 *
 * <ul>
 *   <li><b>一轮一锁</b>：同一会话同时只允许一轮；冲突时抛 {@link DispatcherBusyException}（协议面 → 409）
 *   <li><b>超时中止</b>：单轮预算耗尽 ⇒ 内部以 {@code AbortCmd} 语义中止，会话保留
 *   <li><b>字段校验</b>：非法/缺失字段抛 {@link CommandValidationException}（协议面 → 400）
 *   <li><b>失败隔离</b>：单轮失败以 {@link StepEvent} 的 {@code ERROR} 帧表达，不让调用方仅凭异常猜
 * </ul>
 *
 * <p>返回的 {@link Flow.Publisher} 允许同步（发送完即 complete）或异步（流式）。订阅者按 {@code StepEvent.seq} 顺序消费；{@code
 * DONE} 表示一轮**完全落定**（可能没有后续帧）。
 */
public interface AgentCommandDispatcher {

  /**
   * 分发一条命令并返回事件流。
   *
   * @param cmd 命令（不得为 null）
   * @return 事件发布者（订阅即开始执行；每帧满足 StepEvent v1 契约）
   * @throws CommandValidationException 命令字段非法/不受支持
   * @throws DispatcherBusyException 同一会话已有轮次在执行
   */
  Flow.Publisher<StepEvent> dispatch(AgentCommand cmd);
}
