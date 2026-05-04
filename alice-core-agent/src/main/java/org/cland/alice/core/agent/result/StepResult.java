package org.cland.alice.core.agent.result;

import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.Observation;

/**
 * PPAO 循环中每一步的密封结果类型。
 * <p>
 * 对应设计文档中 StepResult 密封类，支持模式匹配：
 * <ul>
 *   <li>{@link Continue} — 继续执行下一步 Action</li>
 *   <li>{@link Finish} — 循环结束，返回最终答案</li>
 *   <li>{@link Failure} — 发生不可恢复错误</li>
 * </ul>
 */
public sealed abstract class StepResult
    permits StepResult.Continue, StepResult.Finish, StepResult.Failure {

    private StepResult() {}

    // ========== 子类型 ==========

    /**
     * 继续执行，携带下一步 Action。
     */
    public static final class Continue extends StepResult {
        private final Action nextAction;
        private final Observation observation;

        public Continue(Action nextAction) {
            this.nextAction = nextAction;
            this.observation = null;
        }

        public Continue(Action nextAction, Observation observation) {
            this.nextAction = nextAction;
            this.observation = observation;
        }

        public Action nextAction()          { return nextAction; }
        public Observation observation()    { return observation; }

        @Override
        public String toString() {
            return "Continue{action=" + nextAction + "}";
        }
    }

    /**
     * 循环结束，返回最终答案。
     */
    public static final class Finish extends StepResult {
        private final String answer;
        private final String summary;

        public Finish(String answer) {
            this.answer = answer;
            this.summary = null;
        }

        public Finish(String answer, String summary) {
            this.answer = answer;
            this.summary = summary;
        }

        public String answer()              { return answer; }
        public String summary()             { return summary; }

        @Override
        public String toString() {
            return "Finish{answer='" + answer + "'}";
        }
    }

    /**
     * 不可恢复错误。
     */
    public static final class Failure extends StepResult {
        private final String errorMessage;
        private final Throwable cause;

        public Failure(String errorMessage) {
            this.errorMessage = errorMessage;
            this.cause = null;
        }

        public Failure(String errorMessage, Throwable cause) {
            this.errorMessage = errorMessage;
            this.cause = cause;
        }

        public String errorMessage()        { return errorMessage; }
        public Throwable cause()            { return cause; }

        @Override
        public String toString() {
            return "Failure{error='" + errorMessage + "'}";
        }
    }

    // ========== 工厂方法 ==========

    /** 创建 Continue 结果 */
    public static Continue cont(Action next) {
        return new Continue(next);
    }

    /** 创建 Finish 结果 */
    public static Finish finish(String answer) {
        return new Finish(answer);
    }

    /** 创建 Failure 结果 */
    public static Failure fail(String message) {
        return new Failure(message);
    }

    /** 创建 Failure 结果（带异常） */
    public static Failure fail(String message, Throwable cause) {
        return new Failure(message, cause);
    }
}
