package org.cland.alice.facade.tui;

import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.agent.executor.AgentEventListener;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.facade.tui.bridge.EventBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TUI 实现的 Agent 执行流事件监听器。
 *
 * <p>将 AgentExecutor 的 PPAO 执行流（thought → action → observe）实时转发到 TUI EventBridge， 遵循 TAO
 * 四段式布局的渲染序列。每个事件按 PPAO 顺序投递到对应的区域组件：
 *
 * <ul>
 *   <li>{@code onThought} → ThinkBlock（含步骤计数）
 *   <li>{@code onAction} → ActionBlock
 *   <li>{@code onObserve} → ObserveBlock（含 action 命令前缀 + 耗时）
 * </ul>
 */
public class TuiAgentListener implements AgentEventListener {

  private static final Logger logger = LoggerFactory.getLogger(TuiAgentListener.class);

  private final EventBridge eventBridge;
  private final java.util.concurrent.atomic.AtomicInteger thoughtStep;
  private final java.util.concurrent.atomic.AtomicReference<String> lastAction;
  private final java.util.concurrent.atomic.AtomicLong actionStartNanos;

  /** 当前 traceId（对应一次 user say），由 AliceTuiLauncher 在提交任务时设置 */
  private volatile String currentTraceId;

  /**
   * @param eventBridge TUI EventBridge 实例
   */
  public TuiAgentListener(EventBridge eventBridge) {
    this.eventBridge = Objects.requireNonNull(eventBridge, "eventBridge must not be null");
    this.thoughtStep = new java.util.concurrent.atomic.AtomicInteger(0);
    this.lastAction = new java.util.concurrent.atomic.AtomicReference<>();
    this.actionStartNanos = new java.util.concurrent.atomic.AtomicLong(0L);
    this.currentTraceId = null;
  }

  /** 重置步骤计数器（会话重置时调用）。 */
  public void reset() {
    thoughtStep.set(0);
    lastAction.set(null);
    actionStartNanos.set(0L);
    currentTraceId = null;
  }

  /**
   * 开始新的 trace（对应一次 user say）。
   *
   * <p>重置步数计数器，设置 traceId，后续所有 t/a/o 事件携带此 traceId。
   *
   * @param traceId 新 trace 的 ID
   */
  public void newTrace(String traceId) {
    this.currentTraceId = traceId;
    thoughtStep.set(0);
    lastAction.set(null);
    actionStartNanos.set(0L);
  }

  @Override
  public void onThought(String reasoningContent) {
    if (reasoningContent == null || reasoningContent.isBlank() || reasoningContent.length() < 10) {
      return;
    }
    eventBridge.onNewThought(reasoningContent, thoughtStep.incrementAndGet(), currentTraceId);
  }

  @Override
  public void onAction(String target, Map<String, Object> params) {
    if (target == null || target.isBlank()) return;

    // 记录 action 命令文本（用于 observe 配对），不影响计时
    String actionText = target + "(" + params + ")";
    lastAction.set(actionText);
    actionStartNanos.set(System.nanoTime());

    // 构建 Action 对象并投递到 ActionBlock
    var ac = Action.builder().type(Action.Type.TOOL_CALL).target(actionText).build();
    eventBridge.onActionExecuting(ac, currentTraceId);
  }

  @Override
  public void onObserve(String rawData, String summary, long elapsedMs) {
    if (rawData == null || rawData.isBlank()) {
      // 回退到 summary
      if (summary == null || summary.isBlank()) return;
      rawData = summary;
    }

    // 计算实际耗时
    long startNanos = actionStartNanos.getAndSet(0L);
    double seconds;
    if (elapsedMs > 0) {
      seconds = elapsedMs / 1000.0;
    } else if (startNanos > 0) {
      seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
    } else {
      seconds = 0.0;
    }

    // 在观察输出前插入对应的 action 命令，形成完整的 PAO 执行流记录
    var action = lastAction.getAndSet(null);
    var observeContent = action != null ? "$ " + action + "\n" + rawData : rawData;

    // 将包含 action 命令前缀的观察内容 + 实际耗时一起投递
    eventBridge.onObserved(observeContent, seconds, currentTraceId);
  }
}
