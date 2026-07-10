package org.cland.alice.core.agent.executor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 执行流事件总线（Observer 模式）。
 *
 * <p>集中管理 PPAO 执行流中的事件分发，解耦事件生产者和消费者。 遵循单一职责原则（SRP）：仅负责事件的注册、注销与广播。
 *
 * <p>事件序列（PPAO 序列）：
 *
 * <pre>
 *   onThought → onAction → onObserve → (repeat)
 * </pre>
 *
 * <p>线程安全：内部使用 {@link CopyOnWriteArrayList} 保证并发安全。
 */
public class AgentEventBus {

  private static final Logger logger = LoggerFactory.getLogger(AgentEventBus.class);

  private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();

  /**
   * 注册事件监听器。
   *
   * @param listener 监听器实例（不可为 null）
   */
  public void register(AgentEventListener listener) {
    if (listener != null) {
      listeners.add(listener);
      logger.debug("[EventBus] Listener registered: {}", listener.getClass().getSimpleName());
    }
  }

  /**
   * 注销事件监听器。
   *
   * @param listener 要注销的监听器
   * @return 如果成功移除则返回 true
   */
  public boolean unregister(AgentEventListener listener) {
    boolean removed = listeners.remove(listener);
    if (removed) {
      logger.debug("[EventBus] Listener unregistered: {}", listener.getClass().getSimpleName());
    }
    return removed;
  }

  /** 清空所有监听器。 */
  public void clear() {
    listeners.clear();
    logger.debug("[EventBus] All listeners cleared");
  }

  /**
   * 获取当前注册的监听器数量。
   *
   * @return 监听器数量
   */
  public int listenerCount() {
    return listeners.size();
  }

  /**
   * 广播推理事件（Micro-ReAct Reason 阶段）。
   *
   * @param reasoningContent LLM 返回的推理链文本
   */
  public void fireOnThought(String reasoningContent) {
    for (var listener : listeners) {
      try {
        listener.onThought(reasoningContent);
      } catch (Exception e) {
        logger.warn("[EventBus] onThought listener threw exception", e);
      }
    }
  }

  /**
   * 广播动作事件（Micro-ReAct Act/Dispatch 阶段）。
   *
   * @param target 工具名称
   * @param params 工具参数字典
   */
  public void fireOnAction(String target, Map<String, Object> params) {
    for (var listener : listeners) {
      try {
        listener.onAction(target, params);
      } catch (Exception e) {
        logger.warn("[EventBus] onAction listener threw exception", e);
      }
    }
  }

  /**
   * 广播观察事件（Micro-ReAct Observe 阶段）。
   *
   * @param rawData 工具返回的原始数据
   * @param summary 工具执行摘要
   * @param elapsedMs 执行耗时（毫秒）
   */
  public void fireOnObserve(String rawData, String summary, long elapsedMs) {
    for (var listener : listeners) {
      try {
        listener.onObserve(rawData, summary, elapsedMs);
      } catch (Exception e) {
        logger.warn("[EventBus] onObserve listener threw exception", e);
      }
    }
  }
}
