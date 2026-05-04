package org.cland.alice.core.agent.lifecycle;

import java.util.Map;
import java.util.Objects;

/**
 * 执行 Action 后从环境或工具返回的原始观测结果。
 * <p>
 * 对应设计文档中 PPAO 循环的 Observe 阶段的输出。
 * 经过 Verify (Post) 审计后，会成为 {@link org.cland.alice.core.agent.result.StepResult} 的一部分。
 */
public final class Observation {

    /** 观测结果状态 */
    public enum Status {
        /** 执行成功 */
        SUCCESS,
        /** 执行失败 */
        FAILURE,
        /** 部分成功 */
        PARTIAL,
        /** 超时 */
        TIMEOUT,
        /** 被拦截（Pre-Verify 未通过） */
        BLOCKED
    }

    private final Status status;
    private final String summary;
    private final String rawData;
    private final Map<String, Object> metadata;
    private final long timestampMs;

    private Observation(Builder builder) {
        this.status = Objects.requireNonNull(builder.status, "status must not be null");
        this.summary = builder.summary;
        this.rawData = builder.rawData;
        this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : Map.of();
        this.timestampMs = builder.timestampMs > 0
            ? builder.timestampMs
            : System.currentTimeMillis();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 快速创建成功观测 */
    public static Observation success(String summary) {
        return builder().status(Status.SUCCESS).summary(summary).build();
    }

    /** 快速创建失败观测 */
    public static Observation failure(String summary) {
        return builder().status(Status.FAILURE).summary(summary).build();
    }

    /** 快速创建被拦截的观测 */
    public static Observation blocked(String reason) {
        return builder().status(Status.BLOCKED).summary("Blocked: " + reason).build();
    }

    /** 快速创建超时观测 */
    public static Observation timeout(String target) {
        return builder().status(Status.TIMEOUT).summary("Timeout on: " + target).build();
    }

    // ========== Getters ==========

    public Status status()                     { return status; }
    public String summary()                    { return summary; }
    public String rawData()                    { return rawData; }
    public Map<String, Object> metadata()     { return metadata; }
    public long timestampMs()                  { return timestampMs; }

    /** 判断本次观测是否为成功状态 */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    /** 判断本次观测是否表示需要重新规划 */
    public boolean needsRevision() {
        return status == Status.FAILURE || status == Status.TIMEOUT || status == Status.BLOCKED;
    }

    @Override
    public String toString() {
        return "Observation{status=" + status + ", summary='" + summary + "'}";
    }

    // ========== Builder ==========

    public static final class Builder {
        private Status status;
        private String summary;
        private String rawData;
        private Map<String, Object> metadata;
        private long timestampMs;

        private Builder() {}

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder rawData(String rawData) {
            this.rawData = rawData;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder timestampMs(long timestampMs) {
            this.timestampMs = timestampMs;
            return this;
        }

        public Observation build() {
            return new Observation(this);
        }
    }
}
