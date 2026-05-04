package org.cland.alice.core.agent;

import java.util.Objects;

/**
 * Agent 运行时配置。
 * <p>
 * 控制 PPAO 循环的行为参数，包括：
 * <ul>
 *   <li>默认模型 ID</li>
 *   <li>最大迭代次数</li>
 *   <li>超时时间（毫秒）</li>
 *   <li>是否启用 Pre/Post verify</li>
 *   <li>调试模式开关</li>
 * </ul>
 */
public final class AgentConfig {

    /** 默认模型 ID */
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    /** 默认最大迭代次数 */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    /** 默认 Action 超时（30 秒） */
    public static final long DEFAULT_ACTION_TIMEOUT_MS = 30_000;

    private final String defaultModelId;
    private final int maxIterations;
    private final long actionTimeoutMs;
    private final boolean preVerifyEnabled;
    private final boolean postVerifyEnabled;
    private final boolean debug;

    private AgentConfig(Builder builder) {
        this.defaultModelId = builder.defaultModelId != null
            ? builder.defaultModelId
            : DEFAULT_MODEL;
        this.maxIterations = builder.maxIterations > 0
            ? builder.maxIterations
            : DEFAULT_MAX_ITERATIONS;
        this.actionTimeoutMs = builder.actionTimeoutMs > 0
            ? builder.actionTimeoutMs
            : DEFAULT_ACTION_TIMEOUT_MS;
        this.preVerifyEnabled = builder.preVerifyEnabled;
        this.postVerifyEnabled = builder.postVerifyEnabled;
        this.debug = builder.debug;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 使用默认配置 */
    public static AgentConfig defaults() {
        return builder().build();
    }

    // ========== Getters ==========

    public String defaultModelId()          { return defaultModelId; }
    public int maxIterations()              { return maxIterations; }
    public long actionTimeoutMs()           { return actionTimeoutMs; }
    public boolean preVerifyEnabled()       { return preVerifyEnabled; }
    public boolean postVerifyEnabled()      { return postVerifyEnabled; }
    public boolean debug()                  { return debug; }

    // ========== Builder ==========

    public static final class Builder {
        private String defaultModelId;
        private int maxIterations;
        private long actionTimeoutMs;
        private boolean preVerifyEnabled = true;
        private boolean postVerifyEnabled = true;
        private boolean debug;

        private Builder() {}

        public Builder defaultModelId(String defaultModelId) {
            this.defaultModelId = defaultModelId;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder actionTimeoutMs(long actionTimeoutMs) {
            this.actionTimeoutMs = actionTimeoutMs;
            return this;
        }

        public Builder preVerifyEnabled(boolean preVerifyEnabled) {
            this.preVerifyEnabled = preVerifyEnabled;
            return this;
        }

        public Builder postVerifyEnabled(boolean postVerifyEnabled) {
            this.postVerifyEnabled = postVerifyEnabled;
            return this;
        }

        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(this);
        }
    }
}
