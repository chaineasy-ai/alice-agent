package org.cland.alice.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * 一组记忆检索结果的集合，包含来自不同 Vault 的混合记忆片段。
 * <p>
 * MemorySet 是不可变集合，支持不同类型的记忆条目，
 * 供 Planner 融合到 Context Window 中。
 */
public final class MemorySet implements Iterable<MemorySet.Entry> {

    private final List<Entry> entries;

    private MemorySet(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public Iterator<Entry> iterator() {
        return entries.iterator();
    }

    @Override
    public String toString() {
        return "MemorySet{entries=%d}".formatted(entries.size());
    }

    // ---------------------------------------------------------------
    // 单个记忆条目
    // ---------------------------------------------------------------

    public sealed interface Entry permits EpisodicEntry, SemanticEntry, ProceduralEntry {
        /** 条目来源 vault 类型 */
        VaultType vaultType();
        /** 相关度评分 (0.0 ~ 1.0) */
        double score();
    }

    public enum VaultType {
        EPISODIC, SEMANTIC, PROCEDURAL
    }

    public record EpisodicEntry(String sessionId, String content, long timestamp, double score)
            implements Entry {
        @Override
        public VaultType vaultType() {
            return VaultType.EPISODIC;
        }
    }

    public record SemanticEntry(String knowledgeId, String content, double score)
            implements Entry {
        @Override
        public VaultType vaultType() {
            return VaultType.SEMANTIC;
        }
    }

    public record ProceduralEntry(String sopId, String pattern, String procedure, double score)
            implements Entry {
        @Override
        public VaultType vaultType() {
            return VaultType.PROCEDURAL;
        }
    }

    // ---------------------------------------------------------------
    // Builder
    // ---------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Entry> entries = new ArrayList<>();

        private Builder() {}

        public Builder add(Entry entry) {
            Objects.requireNonNull(entry);
            entries.add(entry);
            return this;
        }

        public Builder addAll(List<? extends Entry> entries) {
            this.entries.addAll(entries);
            return this;
        }

        public Builder addEpisodic(String sessionId, String content, long timestamp, double score) {
            return add(new EpisodicEntry(sessionId, content, timestamp, score));
        }

        public Builder addSemantic(String knowledgeId, String content, double score) {
            return add(new SemanticEntry(knowledgeId, content, score));
        }

        public Builder addProcedural(String sopId, String pattern, String procedure, double score) {
            return add(new ProceduralEntry(sopId, pattern, procedure, score));
        }

        public MemorySet build() {
            return new MemorySet(entries);
        }

        public static MemorySet empty() {
            return new MemorySet(Collections.emptyList());
        }
    }
}
