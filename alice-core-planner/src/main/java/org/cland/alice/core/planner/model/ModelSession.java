package org.cland.alice.core.planner.model;

import java.util.Map;
import java.util.Objects;

/**
 * 模型会话抽象，对应设计文档中的 {@code ModelSession}。
 * <p>
 * 封装一次模型调用的完整上下文，包括请求负载、参数和结果。
 * Planner 内部策略通过此接口调用模型，不直接持有模型客户端。
 */
public final class ModelSession {

    private final String modelId;
    private final String prompt;
    private final Map<String, Object> parameters;
    private volatile String response;
    private volatile Throwable error;
    private volatile boolean completed;

    private ModelSession(Builder builder) {
        this.modelId = Objects.requireNonNull(builder.modelId, "modelId must not be null");
        this.prompt = Objects.requireNonNull(builder.prompt, "prompt must not be null");
        this.parameters = builder.parameters != null ? Map.copyOf(builder.parameters) : Map.of();
        this.completed = false;
    }

    public static Builder builder() { return new Builder(); }

    /** 快速创建会话 */
    public static ModelSession of(String modelId, String prompt) {
        return builder().modelId(modelId).prompt(prompt).build();
    }

    public static ModelSession of(String modelId, String prompt, Map<String, Object> parameters) {
        return builder().modelId(modelId).prompt(prompt).parameters(parameters).build();
    }

    // ========== Getters ==========

    public String modelId()                    { return modelId; }
    public String prompt()                     { return prompt; }
    public Map<String, Object> parameters()    { return parameters; }
    public String response()                   { return response; }
    public Throwable error()                   { return error; }
    public boolean completed()                 { return completed; }

    /** 标记会话完成并设置响应 */
    public ModelSession complete(String response) {
        this.response = response;
        this.completed = true;
        return this;
    }

    /** 标记会话失败 */
    public ModelSession fail(Throwable error) {
        this.error = error;
        this.completed = true;
        return this;
    }

    // ========== Builder ==========

    public static final class Builder {
        private String modelId;
        private String prompt;
        private Map<String, Object> parameters;

        private Builder() {}

        public Builder modelId(String modelId)         { this.modelId = modelId; return this; }
        public Builder prompt(String prompt)           { this.prompt = prompt; return this; }
        public Builder parameters(Map<String, Object> p) { this.parameters = p; return this; }

        public ModelSession build() { return new ModelSession(this); }
    }
}
