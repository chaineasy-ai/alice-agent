package org.cland.alice.facade.tui.bridge;

import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.env.adapter.EnvEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * TUI 事件类型，用于 EventBridge 在 Agent 与 UI 组件之间传递消息。
 * <p>
 * 对应设计文档 §3 时序图中的事件流：
 * START_THINKING, NEW_THOUGHT, ACTION_EXECUTE, TASK_COMPLETE 等。
 */
public sealed abstract class TuiEvent {

    private final Instant timestamp;

    protected TuiEvent() {
        this.timestamp = Instant.now();
    }

    protected TuiEvent(Instant timestamp) {
        this.timestamp = Objects.requireNonNullElseGet(timestamp, Instant::now);
    }

    public Instant timestamp() { return timestamp; }

    // ========== 事件子类型 ==========

    /** Agent 开始思考 */
    public static final class StartThinking extends TuiEvent {
        private final String prompt;

        public StartThinking(String prompt) {
            this.prompt = prompt;
        }

        public String prompt() { return prompt; }
    }

    /** Agent 产生新的思考片段 */
    public static final class NewThought extends TuiEvent {
        private final String thought;
        private final int step;

        public NewThought(String thought, int step) {
            this.thought = thought;
            this.step = step;
        }

        public String thought() { return thought; }
        public int step()       { return step; }
    }

    /** Agent 正在执行某个 Action */
    public static final class ActionExecuting extends TuiEvent {
        private final Action action;

        public ActionExecuting(Action action) {
            this.action = action;
        }

        public Action action() { return action; }
    }

    /** 产生一条聊天消息（用户或 Agent） */
    public static final class ChatMessage extends TuiEvent {
        private final String sender;
        private final String content;

        public ChatMessage(String sender, String content) {
            this.sender = sender;
            this.content = content;
        }

        public String sender()  { return sender; }
        public String content() { return content; }
    }

    /** 观测结果（Action 执行后的反馈） */
    public static final class ObservationResult extends TuiEvent {
        private final String summary;

        public ObservationResult(String summary) {
            this.summary = summary;
        }

        public String summary() { return summary; }
    }

    /** 任务完成 */
    public static final class TaskComplete extends TuiEvent {
        private final String result;
        private final String summary;

        public TaskComplete(String result, String summary) {
            this.result = result;
            this.summary = summary;
        }

        public String result()  { return result; }
        public String summary() { return summary; }
    }

    /** 任务出错 */
    public static final class TaskError extends TuiEvent {
        private final String errorMessage;

        public TaskError(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public String errorMessage() { return errorMessage; }
    }

    /** 来自 EnvEvent 的通用事件（桥接） */
    public static final class EnvBridgeEvent extends TuiEvent {
        private final EnvEvent envEvent;

        public EnvBridgeEvent(EnvEvent envEvent) {
            this.envEvent = envEvent;
        }

        public EnvEvent envEvent() { return envEvent; }
    }

    /** Token 使用统计更新 */
    public static final class TokenUpdate extends TuiEvent {
        private final int tokenCount;
        private final String status;

        public TokenUpdate(int tokenCount, String status) {
            this.tokenCount = tokenCount;
            this.status = status;
        }

        public int tokenCount()     { return tokenCount; }
        public String status()      { return status; }
    }
}
