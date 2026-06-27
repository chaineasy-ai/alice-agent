package org.cland.alice.facade.tui.bridge;

import java.time.Instant;
import java.util.Objects;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.env.adapter.EnvEvent;

/**
 * TUI 事件类型，用于 EventBridge 在 Agent 与 UI 组件之间传递消息。
 *
 * <p>对应设计文档 §3 时序图中的事件流： START_THINKING, NEW_THOUGHT, ACTION_EXECUTE, TASK_COMPLETE 等。
 */
public abstract sealed class TuiEvent {

  private final Instant timestamp;

  protected TuiEvent() {
    this.timestamp = Instant.now();
  }

  protected TuiEvent(Instant timestamp) {
    this.timestamp = Objects.requireNonNullElseGet(timestamp, Instant::now);
  }

  public Instant timestamp() {
    return timestamp;
  }

  // ========== 事件子类型 ==========

  /** Agent 开始思考 */
  public static final class StartThinking extends TuiEvent {
    private final String prompt;

    public StartThinking(String prompt) {
      this.prompt = prompt;
    }

    public String prompt() {
      return prompt;
    }
  }

  /** Agent 产生新的思考片段 */
  public static final class NewThought extends TuiEvent {
    private final String thought;
    private final int step;
    private final String traceId;

    public NewThought(String thought, int step) {
      this(thought, step, null);
    }

    public NewThought(String thought, int step, String traceId) {
      this.thought = thought;
      this.step = step;
      this.traceId = traceId;
    }

    public String thought() {
      return thought;
    }

    public int step() {
      return step;
    }

    public String traceId() {
      return traceId;
    }
  }

  /** Agent 正在执行某个 Action */
  public static final class ActionExecuting extends TuiEvent {
    private final Action action;
    private final String traceId;

    public ActionExecuting(Action action) {
      this(action, null);
    }

    public ActionExecuting(Action action, String traceId) {
      this.action = action;
      this.traceId = traceId;
    }

    public Action action() {
      return action;
    }

    public String traceId() {
      return traceId;
    }
  }

  /** 产生一条聊天消息（用户或 Agent） */
  public static final class ChatMessage extends TuiEvent {
    private final String sender;
    private final String content;

    public ChatMessage(String sender, String content) {
      this.sender = sender;
      this.content = content;
    }

    public String sender() {
      return sender;
    }

    public String content() {
      return content;
    }
  }

  /** 观测结果（Action 执行后的反馈） */
  public static final class ObservationResult extends TuiEvent {
    private final String summary;
    private final double elapsedSec;
    private final String traceId;

    public ObservationResult(String summary) {
      this(summary, 0.0, null);
    }

    public ObservationResult(String summary, double elapsedSec) {
      this(summary, elapsedSec, null);
    }

    public ObservationResult(String summary, double elapsedSec, String traceId) {
      this.summary = summary;
      this.elapsedSec = elapsedSec;
      this.traceId = traceId;
    }

    public String summary() {
      return summary;
    }

    /** 工具执行耗时（秒），由 AgentExecutor 或 TuiAgentListener 计时提供。 */
    public double elapsedSec() {
      return elapsedSec;
    }

    public String traceId() {
      return traceId;
    }
  }

  /** 任务完成 */
  public static final class TaskComplete extends TuiEvent {
    private final String result;
    private final String summary;

    public TaskComplete(String result, String summary) {
      this.result = result;
      this.summary = summary;
    }

    public String result() {
      return result;
    }

    public String summary() {
      return summary;
    }
  }

  /** 任务出错 */
  public static final class TaskError extends TuiEvent {
    private final String errorMessage;

    public TaskError(String errorMessage) {
      this.errorMessage = errorMessage;
    }

    public String errorMessage() {
      return errorMessage;
    }
  }

  /** 来自 EnvEvent 的通用事件（桥接） */
  public static final class EnvBridgeEvent extends TuiEvent {
    private final EnvEvent envEvent;

    public EnvBridgeEvent(EnvEvent envEvent) {
      this.envEvent = envEvent;
    }

    public EnvEvent envEvent() {
      return envEvent;
    }
  }

  /** 终端尺寸变更（来自 WINCH 信号或轮询检测） */
  public static final class TerminalResize extends TuiEvent {
    private final int width;
    private final int height;

    public TerminalResize(int width, int height) {
      this.width = width;
      this.height = height;
    }

    public int width() {
      return width;
    }

    public int height() {
      return height;
    }
  }

  /** Token 使用统计更新 */
  public static final class TokenUpdate extends TuiEvent {
    private final int tokenCount;
    private final String status;

    public TokenUpdate(int tokenCount, String status) {
      this.tokenCount = tokenCount;
      this.status = status;
    }

    public int tokenCount() {
      return tokenCount;
    }

    public String status() {
      return status;
    }
  }
}
