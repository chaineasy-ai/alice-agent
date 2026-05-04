package org.cland.alice.guardrail;

import java.util.Objects;

/**
 * 修正建议，对应设计文档 AuditResult 的 suggestion 字段。
 * <p>
 * 当审计失败时，提供具体的修正指导，供 Planner 重新规划使用。
 * 包含建议类型、详细描述和可选的结构化修正数据。
 */
public final class CorrectionSuggestion {

    /** 建议类型 */
    public enum Type {
        /** 重新规划整体方案 */
        REPLAN,
        /** 修改当前步骤的输入参数 */
        MODIFY_PARAMETERS,
        /** 更换工具或目标 */
        CHANGE_TARGET,
        /** 获取更多上下文信息后再继续 */
        GATHER_CONTEXT,
        /** 请求人工确认 */
        MANUAL_CONFIRM,
        /** 终止当前执行 */
        ABORT
    }

    private final Type type;
    private final String description;
    private final Object detail;

    private CorrectionSuggestion(Builder builder) {
        this.type = Objects.requireNonNull(builder.type, "type must not be null");
        this.description = builder.description;
        this.detail = builder.detail;
    }

    public static Builder builder() { return new Builder(); }

    /** 快速创建 REPLAN 建议 */
    public static CorrectionSuggestion replan(String reason) {
        return builder()
            .type(Type.REPLAN)
            .description(reason)
            .build();
    }

    /** 快速创建 MODIFY_PARAMETERS 建议 */
    public static CorrectionSuggestion modifyParameters(String reason, Object detail) {
        return builder()
            .type(Type.MODIFY_PARAMETERS)
            .description(reason)
            .detail(detail)
            .build();
    }

    /** 快速创建 CHANGE_TARGET 建议 */
    public static CorrectionSuggestion changeTarget(String reason) {
        return builder()
            .type(Type.CHANGE_TARGET)
            .description(reason)
            .build();
    }

    /** 快速创建 GATHER_CONTEXT 建议 */
    public static CorrectionSuggestion gatherContext(String reason) {
        return builder()
            .type(Type.GATHER_CONTEXT)
            .description(reason)
            .build();
    }

    /** 快速创建 MANUAL_CONFIRM 建议 */
    public static CorrectionSuggestion manualConfirm(String reason) {
        return builder()
            .type(Type.MANUAL_CONFIRM)
            .description(reason)
            .build();
    }

    /** 快速创建 ABORT 建议 */
    public static CorrectionSuggestion abort(String reason) {
        return builder()
            .type(Type.ABORT)
            .description(reason)
            .build();
    }

    // ========== Getters ==========

    public Type type()                   { return type; }
    public String description()          { return description; }
    public Object detail()               { return detail; }

    @Override
    public String toString() {
        return "CorrectionSuggestion{type=" + type + ", description='" + description + "'}";
    }

    // ========== Builder ==========

    public static final class Builder {
        private Type type;
        private String description;
        private Object detail;

        private Builder() {}

        public Builder type(Type type) {
            this.type = type;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder detail(Object detail) {
            this.detail = detail;
            return this;
        }

        public CorrectionSuggestion build() {
            return new CorrectionSuggestion(this);
        }
    }
}
