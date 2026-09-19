/*
 * Alice Agent — 宿主驱动引擎端口（runtime 内部抽象）
 *
 * 对 core.Agent 的最小抽象：便于单测打桩、把"会话语义"与"引擎实现"解耦。
 */
package org.cland.alice.runtime.engine;

import org.cland.alice.agent.proto.event.StepEvent;

/**
 * 引擎端口 — 宿主（{@code AgentHost}）驱动一轮执行所需的最小能力面。
 *
 * <p>实现：{@link CoreAgentEngine}（适配 {@code Agent}）；测试可用假引擎。
 *
 * <p>线程口径：{@link #ask(String)} **阻塞**到一轮结束；宿主负责把它放到工作线程，并用 {@link #cancel()} 处理超时/中止。
 */
public interface AgentEngine {

  /** 会话标识（与命令的 sessionId 必须一致，宿主会校验）。 */
  String sessionId();

  /**
   * 执行一轮任务（阻塞）。
   *
   * @param prompt 任务文本
   * @return 最终文本（面向用户的答复）
   * @throws Exception 执行失败（宿主转成 {@code ERROR} 帧）
   */
  String ask(String prompt) throws Exception;

  /** 中止当前轮（**会话保留**：上下文/记忆/排期不变）。 */
  void cancel();

  /** 人工插话：投递到当前轮（对应 {@code SteerCmd}）。 */
  void injectFeedback(String message);

  /** 清上下文（对应 {@code ResetSessionCmd} / {@code /new}）。 */
  void clearMemory();

  /**
   * 压缩上下文（对应 {@code CompactContextCmd}）。
   *
   * @return 可读结果文本
   */
  String compactContext();

  /** 上下文快照（对应 {@code ViewContextCmd}）。 */
  String currentContext();

  /** 内核事件源（thought/action/observe；**runtime 自有抽象**，不泄露 core 类型 ✓）。 */
  EngineEvents events();

  /** 最近一轮用量（引擎不提供时返回 {@link StepEvent.Usage#zero()}）。 */
  StepEvent.Usage lastUsage();
}
