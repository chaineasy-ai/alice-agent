package org.cland.alice.core.agent.kernel;

import java.util.Map;

/**
 * 内核执行事件流订阅入口（thought → action → observe）。
 *
 * <p>事件由内核在决策循环的对应执行点发出（decision/effect/observe 原语的事件形态）， L2 Agent 负责把内核事件翻译为 UI 事件（§3.3 关系表：事件翻译是
 * Agent 层职责）。 监听器以 default 空方法提供，实现类仅需覆盖感兴趣的事件。
 *
 * <p>线程安全：实现应允许并发注册/注销（如 CopyOnWriteArrayList）。
 */
public interface EventStream {

  /** 注册事件监听器。 */
  void subscribe(Listener listener);

  /** 注销事件监听器。 */
  void unsubscribe(Listener listener);

  /** 内核执行事件监听器。 */
  interface Listener {

    /** 决策循环的 Thought 事件（LLM 推理/思考内容）。 */
    default void onThought(String reasoning) {}

    /** 决策循环的 Action 事件（工具调用/效果入口）。 */
    default void onAction(String target, Map<String, Object> params) {}

    /** 决策循环的 Observe 事件（工具执行结果回流）。 */
    default void onObserve(String rawData, String summary, long elapsedMs) {}
  }
}
