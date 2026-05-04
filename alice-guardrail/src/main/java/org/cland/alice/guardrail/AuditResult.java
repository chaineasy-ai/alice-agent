package org.cland.alice.guardrail;

import java.util.Objects;

/**
 * 审计结果，对应设计文档中的 AuditResult 领域对象。
 * <p>
 * 包含验证是否通过、原因、风险等级以及修正建议。
 * {@link GuardrailService} 中的每一步验证都会返回此结果。
 */
public final class AuditResult {

    /** 审计结果状态 */
    public enum Status {
        /** 验证通过，允许执行 */
        ALLOW,
        /** 验证拒绝，请求修正 */
        REJECT,
        /** 数据无效或检测到幻觉，需要重新规划 */
        INVALID,
        /** 需要人工确认 (Human-in-the-loop) */
        MANUAL_CONFIRM
    }

    private final Status status;
    private final String reason;
    private final RiskLevel risk;
    private final CorrectionSuggestion suggestion;

    private AuditResult(Builder builder) {
        this.status = Objects.requireNonNull(builder.status, "status must not be null");
        this.reason = builder.reason;
        this.risk = builder.risk != null ? builder.risk : RiskLevel.LOW;
        this.suggestion = builder.suggestion;
    }

    public static Builder builder() { return new Builder(); }

    /** 快速创建 ALLOW 结果 */
    public static AuditResult allow() {
        return builder().status(Status.ALLOW).risk(RiskLevel.LOW).build();
    }

    /** 快速创建 ALLOW 结果，附带中等风险标记 */
    public static AuditResult allowWithWarning(String reason) {
        return builder()
            .status(Status.ALLOW)
            .risk(RiskLevel.MEDIUM)
            .reason(reason)
            .build();
    }

    /** 快速创建 REJECT 结果 */
    public static AuditResult reject(String reason, CorrectionSuggestion suggestion) {
        return builder()
            .status(Status.REJECT)
            .risk(RiskLevel.CRITICAL)
            .reason(reason)
            .suggestion(suggestion)
            .build();
    }

    /** 快速创建 INVALID 结果（用于 Post-Exec 的幻觉检测失败） */
    public static AuditResult invalid(String reason, CorrectionSuggestion suggestion) {
        return builder()
            .status(Status.INVALID)
            .risk(RiskLevel.HIGH)
            .reason(reason)
            .suggestion(suggestion)
            .build();
    }

    /** 快速创建 MANUAL_CONFIRM 结果 */
    public static AuditResult manualConfirm(String reason) {
        return builder()
            .status(Status.MANUAL_CONFIRM)
            .risk(RiskLevel.HIGH)
            .reason(reason)
            .suggestion(CorrectionSuggestion.manualConfirm(reason))
            .build();
    }

    // ========== Query Methods ==========

    /** 审计是否通过（ALLOW 状态） */
    public boolean isPassed() {
        return status == Status.ALLOW;
    }

    /** 是否需要修正（REJECT 或 INVALID） */
    public boolean needsCorrection() {
        return status == Status.REJECT || status == Status.INVALID;
    }

    /** 是否需要人工确认 */
    public boolean needsManualConfirm() {
        return status == Status.MANUAL_CONFIRM;
    }

    // ========== Getters ==========

    public Status status()                       { return status; }
    public String reason()                       { return reason; }
    public RiskLevel risk()                      { return risk; }
    public CorrectionSuggestion suggestion()     { return suggestion; }

    @Override
    public String toString() {
        return "AuditResult{status=" + status
            + ", risk=" + risk
            + ", reason='" + reason + "'}";
    }

    // ========== Builder ==========

    public static final class Builder {
        private Status status;
        private String reason;
        private RiskLevel risk;
        private CorrectionSuggestion suggestion;

        private Builder() {}

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder risk(RiskLevel risk) {
            this.risk = risk;
            return this;
        }

        public Builder suggestion(CorrectionSuggestion suggestion) {
            this.suggestion = suggestion;
            return this;
        }

        public AuditResult build() {
            return new AuditResult(this);
        }
    }
}
