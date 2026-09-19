/*
 * Alice Agent — 引擎事件源（runtime 自有抽象）
 *
 * 隔离 core：宿主/传输只认本接口，不直接依赖 core.agent.kernel.EventStream；
 * 适配（core.EventStream → 本接口）只在 CoreAgentEngine 内发生。
 */
package org.cland.alice.runtime.engine;

import java.util.Map;

/**
 * 引擎事件源 —— 决策循环的 thought/action/observe 事件（宿主映射为 StepEvent）。
 *
 * <p>线程安全：实现应允许并发注册/注销（如 CopyOnWriteArrayList）。
 */
public interface EngineEvents {

  /** 注册监听器。 */
  void subscribe(Listener listener);

  /** 注销监听器。 */
  void unsubscribe(Listener listener);

  /** 引擎执行事件监听器（default 空方法：只覆盖感兴趣的事件）。 */
  interface Listener {

    /** LLM 推理/思考。 */
    default void onThought(String reasoning) {}

    /** 工具调用/效果入口。 */
    default void onAction(String target, Map<String, Object> params) {}

    /** 工具执行结果回流。 */
    default void onObserve(String rawData, String summary, long elapsedMs) {}
  }
}
