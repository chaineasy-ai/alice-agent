package org.cland.alice.core.agent.lifecycle;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 动作定义，对应设计文档中的 Action Intent。
 * <p>
 * 每个 Action 包含：
 * <ul>
 *   <li>动作类型（如 TOOL_CALL、LLM_INFERENCE、OBSERVE）</li>
 *   <li>目标（工具名、模型 ID 等）</li>
 *   <li>参数键值对</li>
 *   <li>关联的 Thought（LLM 的推理过程）</li>
 * </ul>
 */
public final class Action {

    /** 动作类型枚举 */
    public enum Type {
        /** 调用外部工具 */
        TOOL_CALL,
        /** LLM 推理（模型调用） */
        LLM_INFERENCE,
        /** 感知环境状态 */
        OBSERVE,
        /** 等待/睡眠 */
        WAIT,
        /** 终止 Agent 循环 */
        FINISH,
        /** 重新规划 */
        REVISION
    }

    private final Type type;
    private final String target;
    private final Map<String, Object> parameters;
    private final String thought;
    private final String actionId;

    private Action(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type must not be null");
        this.target = builder.target;
        this.parameters = builder.parameters != null
            ? Map.copyOf(builder.parameters)
            : Map.of();
        this.thought = builder.thought;
        this.actionId = builder.actionId != null
            ? builder.actionId
            : java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 快速创建 FINISH 动作 */
    public static Action finish() {
        return builder().type(Type.FINISH).target("FINISH").build();
    }

    /** 快速创建 TOOL_CALL 动作 */
    public static Action toolCall(String toolName, Map<String, Object> params) {
        return builder().type(Type.TOOL_CALL).target(toolName).parameters(params).build();
    }

    /** 快速创建 LLM_INFERENCE 动作 */
    public static Action llmInference(String modelId, String prompt) {
        return builder()
            .type(Type.LLM_INFERENCE)
            .target(modelId)
            .parameter("prompt", prompt)
            .build();
    }

    /** 快速创建 REVISION 动作 */
    public static Action revision(String feedback) {
        return builder()
            .type(Type.REVISION)
            .target("REVISION")
            .parameter("feedback", feedback)
            .build();
    }

    // ========== Getters ==========

    public Type type()                         { return type; }
    public String target()                     { return target; }
    public Map<String, Object> parameters()   { return parameters; }
    public String thought()                    { return thought; }
    public String actionId()                   { return actionId; }

    @Override
    public String toString() {
        return "Action{id='" + actionId + "', type=" + type
            + ", target='" + target + "'}";
    }

    // ========== Builder ==========

    public static final class Builder {
        private Type type;
        private String target;
        private Map<String, Object> parameters;
        private String thought;
        private String actionId;

        private Builder() {}

        public Builder type(Type type) {
            this.type = type;
            return this;
        }

        public Builder target(String target) {
            this.target = target;
            return this;
        }

        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder parameter(String key, Object value) {
            if (this.parameters == null) {
                this.parameters = new ConcurrentHashMap<>();
            }
            this.parameters.put(key, value);
            return this;
        }

        public Builder thought(String thought) {
            this.thought = thought;
            return this;
        }

        public Builder actionId(String actionId) {
            this.actionId = actionId;
            return this;
        }

        public Action build() {
            return new Action(this);
        }
    }
}
