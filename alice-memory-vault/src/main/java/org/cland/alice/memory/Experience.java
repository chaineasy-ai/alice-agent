package org.cland.alice.memory;

import java.util.List;
import java.util.Objects;

/**
 * 一次经验/交互的记录，由 Agent 与环境的交互产生。
 * <p>
 * 是记忆摄入（memorize）的基本单元，对应设计文档中 Interaction 数据流。
 */
public final class Experience {

    private final String sessionId;
    private final String action;
    private final String observation;
    private final String result;
    private final long timestamp;

    private Experience(Builder builder) {
        this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId must not be null");
        this.action = Objects.requireNonNull(builder.action, "action must not be null");
        this.observation = Objects.requireNonNull(builder.observation, "observation must not be null");
        this.result = builder.result;
        this.timestamp = builder.timestamp > 0 ? builder.timestamp : System.currentTimeMillis();
    }

    public String sessionId() { return sessionId; }
    public String action() { return action; }
    public String observation() { return observation; }
    public String result() { return result; }
    public long timestamp() { return timestamp; }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Experience that)) return false;
        return timestamp == that.timestamp
                && sessionId.equals(that.sessionId)
                && action.equals(that.action)
                && observation.equals(that.observation)
                && Objects.equals(result, that.result);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, action, observation, result, timestamp);
    }

    @Override
    public String toString() {
        return "Experience{sessionId='%s', action='%s', timestamp=%d}"
                .formatted(sessionId, action, timestamp);
    }

    // ---------------------------------------------------------------

    public static final class Builder {
        private String sessionId;
        private String action;
        private String observation;
        private String result;
        private long timestamp;

        private Builder() {}

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder observation(String observation) {
            this.observation = observation;
            return this;
        }

        public Builder result(String result) {
            this.result = result;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Experience build() {
            return new Experience(this);
        }
    }
}
