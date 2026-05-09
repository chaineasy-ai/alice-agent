package org.cland.alice.facade.tui.bridge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.cland.alice.core.agent.AgentCore;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.env.adapter.EnvEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EventBridge：Agent 核心事件与 TUI 界面之间的消息桥梁。
 *
 * <p>对应设计文档 §5 数据流图中的 EventBridge 组件。 负责：
 *
 * <ul>
 *   <li>监听 AgentCore 产生的 PPAO 事件
 *   <li>将事件转换为 {@link TuiEvent} 并分发给已注册的监听器
 *   <li>在独立线程中消费事件，不阻塞 UI 渲染循环
 * </ul>
 */
public class EventBridge implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(EventBridge.class);

  private final List<Consumer<TuiEvent>> listeners;
  private final ExecutorService eventThread;
  private volatile boolean closed;

  /** AgentCore 引用（可选，用于提交任务） */
  private AgentCore agentCore;

  public EventBridge() {
    this.listeners = new CopyOnWriteArrayList<>();
    this.eventThread =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "alice-event-bridge");
              t.setDaemon(true);
              return t;
            });
    this.closed = false;
  }

  /** 绑定 AgentCore 实例，此后可以通过 bridge 提交任务。 */
  public EventBridge bind(AgentCore agentCore) {
    this.agentCore = agentCore;
    return this;
  }

  // ========== 事件发送 ==========

  /** 发送事件到所有监听器（异步） */
  public void emit(TuiEvent event) {
    if (closed) return;
    eventThread.submit(
        () -> {
          for (Consumer<TuiEvent> listener : listeners) {
            try {
              listener.accept(event);
            } catch (Exception e) {
              logger.warn("Event listener threw exception", e);
            }
          }
        });
  }

  /**
   * @deprecated 使用 {@link #emit(TuiEvent)}
   */
  @Deprecated
  public void fire(TuiEvent event) {
    emit(event);
  }

  // ========== 监听器管理 ==========

  public void addListener(Consumer<TuiEvent> listener) {
    listeners.add(listener);
  }

  public void removeListener(Consumer<TuiEvent> listener) {
    listeners.remove(listener);
  }

  // ========== 便捷方法：从 Agent 事件构造 TUI 事件 ==========

  /** 当 Agent 开始思考时调用 */
  public void onStartThinking(String prompt) {
    emit(new TuiEvent.StartThinking(prompt));
  }

  /** 当 Agent 产生新的思考时调用 */
  public void onNewThought(String thought, int step) {
    emit(new TuiEvent.NewThought(thought, step));
  }

  /** 当 Agent 执行 Action 时调用 */
  public void onActionExecuting(Action action) {
    emit(new TuiEvent.ActionExecuting(action));
  }

  /** 当观测结果返回时调用 */
  public void onObserved(String summary) {
    emit(new TuiEvent.ObservationResult(summary));
  }

  /** 任务完成时调用 */
  public void onTaskComplete(String result, String summary) {
    emit(new TuiEvent.TaskComplete(result, summary));
  }

  /** 任务出错时调用 */
  public void onTaskError(String errorMessage) {
    emit(new TuiEvent.TaskError(errorMessage));
  }

  /** 添加聊天消息 */
  public void onChatMessage(String sender, String content) {
    emit(new TuiEvent.ChatMessage(sender, content));
  }

  /** Token 使用更新 */
  public void onTokenUpdate(int tokenCount, String status) {
    emit(new TuiEvent.TokenUpdate(tokenCount, status));
  }

  /** 桥接 EnvEvent */
  public void onEnvEvent(EnvEvent envEvent) {
    emit(new TuiEvent.EnvBridgeEvent(envEvent));
  }

  // ========== 终止 ==========

  @Override
  public void close() {
    this.closed = true;
    eventThread.shutdown();
  }
}
