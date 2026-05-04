package org.cland.alice.core.planner.sop;

import org.cland.alice.core.planner.Plan;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SOP 注册表，对应设计文档中的 {@code SopRegistry}。
 * <p>
 * 用于存储和管理标准的操作流程（Standard Operating Procedure）。
 * 每个 SOP 包含：
 * <ul>
 *   <li>名称 / ID — 唯一标识</li>
 *   <li>描述 — 该流程适用场景</li>
 *   <li>关键词列表 — 用于语义匹配</li>
 *   <li>步骤列表 — 预定义的 Action 序列</li>
 * </ul>
 * <p>
 * 未来可以配合 {@code alice-memory-vault} 中的向量索引实现语义检索。
 */
public final class SopRegistry {

    private static final System.Logger logger = System.getLogger(SopRegistry.class.getName());

    /** SOP 模板存储 */
    private final Map<String, SopTemplate> templates = new ConcurrentHashMap<>();

    /** 关键词索引：keyword -> List<SopTemplateId> */
    private final Map<String, List<String>> keywordIndex = new ConcurrentHashMap<>();

    /**
     * 注册一个 SOP 模板。
     */
    public SopRegistry register(SopTemplate template) {
        Objects.requireNonNull(template, "template must not be null");
        templates.put(template.id(), template);

        // 建立关键词索引
        for (String keyword : template.keywords()) {
            String lowerKey = keyword.toLowerCase();
            keywordIndex.computeIfAbsent(lowerKey, k -> new CopyOnWriteArrayList<>())
                .add(template.id());
        }

        logger.log(System.Logger.Level.INFO, "Registered SOP: {0} ({1} steps, {2} keywords)",
            template.id(), template.steps().size(), template.keywords().size());
        return this;
    }

    /**
     * 根据名称查找 SOP。
     */
    public SopTemplate get(String id) {
        return templates.get(id);
    }

    /**
     * 根据 prompt 匹配最合适的 SOP。
     * <p>
     * 使用关键词匹配 + 简单得分排序。
     * 后续可替换为向量检索。
     *
     * @param prompt 用户输入或任务描述
     * @return 匹配得分最高的 SOP，如果没有匹配返回 null
     */
    public SopTemplate match(String prompt) {
        if (prompt == null || prompt.isBlank()) return null;

        String lowerPrompt = prompt.toLowerCase();
        Map<String, Integer> scores = new HashMap<>();

        // 遍历关键词索引，计算每个 SOP 的匹配得分
        for (var entry : keywordIndex.entrySet()) {
            String keyword = entry.getKey();
            if (lowerPrompt.contains(keyword)) {
                for (String templateId : entry.getValue()) {
                    scores.merge(templateId, 1, Integer::sum);
                }
            }
        }

        if (scores.isEmpty()) return null;

        // 返回得分最高的 SOP
        return scores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(entry -> templates.get(entry.getKey()))
            .orElse(null);
    }

    /**
     * 获取所有已注册的 SOP ID。
     */
    public Set<String> ids() {
        return Set.copyOf(templates.keySet());
    }

    /**
     * 获取所有 SOP 模板的不可变视图。
     */
    public Collection<SopTemplate> all() {
        return List.copyOf(templates.values());
    }

    /**
     * 清空注册表。
     */
    public void clear() {
        templates.clear();
        keywordIndex.clear();
    }

    // ========== SOP Template ==========

    /**
     * SOP 模板，描述一个标准操作流程。
     */
    public static final class SopTemplate {

        private final String id;
        private final String description;
        private final List<String> keywords;
        private final List<Plan.Step> steps;

        private SopTemplate(Builder builder) {
            this.id = Objects.requireNonNull(builder.id, "id must not be null");
            this.description = builder.description;
            this.keywords = builder.keywords != null ? List.copyOf(builder.keywords) : List.of();
            this.steps = builder.steps != null ? List.copyOf(builder.steps) : List.of();
        }

        public static Builder builder() { return new Builder(); }

        public String id()              { return id; }
        public String description()     { return description; }
        public List<String> keywords()  { return keywords; }
        public List<Plan.Step> steps()  { return steps; }

        @Override
        public String toString() {
            return "SopTemplate{id='" + id + "', steps=" + steps.size() + "}";
        }

        // ========== Builder ==========

        public static final class Builder {
            private String id;
            private String description;
            private List<String> keywords;
            private List<Plan.Step> steps;

            private Builder() {}

            public Builder id(String id)                       { this.id = id; return this; }
            public Builder description(String description)     { this.description = description; return this; }
            public Builder keywords(List<String> keywords)     { this.keywords = keywords; return this; }
            public Builder steps(List<Plan.Step> steps)        { this.steps = steps; return this; }

            public Builder addKeyword(String keyword) {
                if (this.keywords == null) this.keywords = new ArrayList<>();
                this.keywords.add(keyword);
                return this;
            }

            public Builder addStep(Plan.Step step) {
                if (this.steps == null) this.steps = new ArrayList<>();
                this.steps.add(step);
                return this;
            }

            public Builder addStep(String actionType, String target) {
                return addStep(Plan.Step.of(actionType, target));
            }

            public SopTemplate build() { return new SopTemplate(this); }
        }
    }
}
