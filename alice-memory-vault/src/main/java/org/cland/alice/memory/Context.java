package org.cland.alice.memory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 表示一次记忆检索的查询上下文。
 * <p>
 * 包含原始查询文本、可选的 sessionId、标签、时间范围等，
 * 由 MemoryRouter 分析并路由到合适的记忆 vault。
 */
public final class Context {

    private final String query;
    private final String sessionId;
    private final Map<String, Object> metadata;

    private Context(Builder builder) {
        this.query = Objects.requireNonNull(builder.query, "query must not be null");
        this.sessionId = builder.sessionId;
        this.metadata = builder.metadata != null
                ? Map.copyOf(builder.metadata)
                : Map.of();
    }

    public String query() { return query; }
    public String sessionId() { return sessionId; }
    public Map<String, Object> metadata() { return metadata; }

    @SuppressWarnings("unchecked")
    public <T> T metadata(String key) {
        return (T) metadata.get(key);
    }

    /**
     * 快速判断上下文是否涉及"刚刚做了什么"（应路由到 EpisodicVault）。
     */
    public boolean isEpisodicQuery() {
        String q = query.toLowerCase();
        return q.contains("刚才") || q.contains("之前") || q.contains("just did")
                || q.contains("previous") || q.contains("last step")
                || q.contains("recent") || q.contains("最近")
                || q.contains("会话") || q.contains("session");
    }

    /**
     * 快速判断上下文是否涉及"这是什么技术/概念"（应路由到 SemanticVault）。
     */
    public boolean isSemanticQuery() {
        String q = query.toLowerCase();
        return q.contains("什么是") || q.contains("what is") || q.contains("解释")
                || q.contains("explain") || q.contains("介绍") || q.contains("describe")
                || q.contains("概念") || q.contains("concept") || q.contains("技术")
                || q.contains("technology") || q.contains("文档") || q.contains("documentation");
    }

    /**
     * 快速判断上下文是否涉及"如何执行"（应路由到 ProceduralVault）。
     */
    public boolean isProceduralQuery() {
        String q = query.toLowerCase();
        return q.contains("如何") || q.contains("how to") || q.contains("步骤")
                || q.contains("step") || q.contains("工具") || q.contains("tool")
                || q.contains("sop") || q.contains("流程") || q.contains("procedure")
                || q.contains("执行") || q.contains("execute") || q.contains("用法")
                || q.contains("usage");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Context context)) return false;
        return query.equals(context.query)
                && Objects.equals(sessionId, context.sessionId)
                && metadata.equals(context.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(query, sessionId, metadata);
    }

    @Override
    public String toString() {
        return "Context{query='%s', sessionId='%s', metadata=%s}"
                .formatted(query, sessionId, metadata);
    }

    // ---------------------------------------------------------------

    public static final class Builder {
        private String query;
        private String sessionId;
        private Map<String, Object> metadata;

        private Builder() {}

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder metadata(String key, Object value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.putAll(metadata);
            return this;
        }

        public Context build() {
            return new Context(this);
        }
    }
}
