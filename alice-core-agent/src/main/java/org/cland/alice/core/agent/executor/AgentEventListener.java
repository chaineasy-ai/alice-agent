package org.cland.alice.core.agent.executor;

import java.util.Map;

/**
 * Agent 执行流事件监听器（Observer 模式）。
 *
 * <p>监听 AgentExecutor Micro-ReAct 循环中的 PPAO 执行流事件，用于 TUI/CLI 实时渲染、日志记录、 性能监控等扩展场景。每个方法均为 default
 * 空实现，实现类仅需覆盖感兴趣的事件。
 *
 * <p>事件顺序（PPAO 序列）：
 *
 * <pre>
 *   onThought  → onAction → onObserve  → (repeat for next tool call)
 *   onThought  → (final answer)
 * </pre>
 *
 * @see AgentExecutor#addListener(AgentEventListener)
 */
public interface AgentEventListener {

  /**
   * Micro-ReAct Reason 阶段的推理内容。
   *
   * @param reasoningContent LLM 返回的推理链文本（可能为空）
   */
  default void onThought(String reasoningContent) {}

  /**
   * Micro-ReAct Dispatch 阶段的工具调用。
   *
   * @param target 工具名称
   * @param params 工具参数字典
   */
  default void onAction(String target, Map<String, Object> params) {}

  /**
   * Micro-ReAct Observe 阶段的工具执行结果。
   *
   * @param rawData 工具返回的原始数据
   * @param summary 工具执行摘要
   * @param elapsedMs 执行耗时（毫秒）
   */
  default void onObserve(String rawData, String summary, long elapsedMs) {}
}
