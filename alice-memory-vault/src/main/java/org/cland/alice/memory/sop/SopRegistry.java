package org.cland.alice.memory.sop;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SOP 注册表 — 程序性记忆 (Procedural Memory) 的存储中心。
 *
 * <p>存储和管理标准操作流程（Standard Operating Procedure）。 支持两种表示形式：
 *
 * <ul>
 *   <li>{@link SopTemplate} — 平铺的步骤列表（向后兼容）
 *   <li>{@link SopGraph} — 基于 JGrapht DAG 的有向无环图（支持条件分支、并行任务）
 * </ul>
 *
 * <p>通过关键词索引实现 SOP 匹配。
 */
public final class SopRegistry {

  private static final Logger logger = LoggerFactory.getLogger(SopRegistry.class);

  /** SOP 模板存储 */
  private final Map<String, SopTemplate> templates = new ConcurrentHashMap<>();

  /** SOP 图存储（JGrapht DAG） */
  private final Map<String, SopGraph> graphs = new ConcurrentHashMap<>();

  /** 关键词索引：keyword -> List<SopTemplateId> */
  private final Map<String, List<String>> keywordIndex = new ConcurrentHashMap<>();

  // ========== 注册 ==========

  /** 注册一个 SOP 模板（平铺步骤）。 */
  public SopRegistry register(SopTemplate template) {
    Objects.requireNonNull(template, "template must not be null");
    templates.put(template.id(), template);

    for (String keyword : template.keywords()) {
      String lowerKey = keyword.toLowerCase();
      keywordIndex.computeIfAbsent(lowerKey, k -> new CopyOnWriteArrayList<>()).add(template.id());
    }

    logger.info(
        "Registered SOP: {} ({} steps, {} keywords)",
        template.id(),
        template.steps().size(),
        template.keywords().size());
    return this;
  }

  /** 注册一个 SOP 图（自动同步生成平铺模板）。 */
  public SopRegistry register(SopGraph graph) {
    Objects.requireNonNull(graph, "graph must not be null");
    graphs.put(graph.id(), graph);

    // 同步创建平铺模板
    SopTemplate template = toTemplate(graph);
    templates.put(graph.id(), template);

    for (String keyword : template.keywords()) {
      String lowerKey = keyword.toLowerCase();
      keywordIndex.computeIfAbsent(lowerKey, k -> new CopyOnWriteArrayList<>()).add(template.id());
    }

    logger.info(
        "Registered SOP Graph: {} ({} nodes, {} edges, {} keywords)",
        graph.id(),
        graph.nodes().size(),
        graph.edges().size(),
        template.keywords().size());
    return this;
  }

  // ========== 查询 ==========

  /** 根据名称查找 SOP 模板。 */
  public SopTemplate get(String id) {
    return templates.get(id);
  }

  /** 根据名称查找 SOP 图。 */
  public SopGraph getGraph(String id) {
    return graphs.get(id);
  }

  /**
   * 根据 prompt 匹配最合适的 SOP。
   *
   * <p>使用关键词匹配 + 简单得分排序。
   *
   * @param prompt 用户输入或任务描述
   * @return 匹配得分最高的 SOP，如果没有匹配返回 null
   */
  public SopTemplate match(String prompt) {
    if (prompt == null || prompt.isBlank()) return null;

    String lowerPrompt = prompt.toLowerCase();
    Map<String, Integer> scores = new HashMap<>();

    for (var entry : keywordIndex.entrySet()) {
      String keyword = entry.getKey();
      if (lowerPrompt.contains(keyword)) {
        for (String templateId : entry.getValue()) {
          scores.merge(templateId, 1, Integer::sum);
        }
      }
    }

    if (scores.isEmpty()) return null;

    return scores.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .map(entry -> templates.get(entry.getKey()))
        .orElse(null);
  }

  /** 获取所有已注册的 SOP ID。 */
  public Set<String> ids() {
    return Set.copyOf(templates.keySet());
  }

  /** 获取所有已注册的 SOP 图 ID。 */
  public Set<String> graphIds() {
    return Set.copyOf(graphs.keySet());
  }

  /** 获取所有 SOP 模板的不可变视图。 */
  public Collection<SopTemplate> all() {
    return List.copyOf(templates.values());
  }

  /** 清空注册表。 */
  public void clear() {
    templates.clear();
    graphs.clear();
    keywordIndex.clear();
  }

  // ========== 内部转换 ==========

  /** 将 SopGraph 转换为平铺的 SopTemplate。 */
  static SopTemplate toTemplate(SopGraph graph) {
    var builder =
        SopTemplate.builder()
            .id(graph.id())
            .description(graph.description())
            .keywords(graph.keywords());
    for (SopGraph.SopNode node : graph.topologicalOrder()) {
      builder.addStep(node);
    }
    return builder.build();
  }

  // ========== SopTemplate ==========

  /** SOP 模板，描述一个标准操作流程的平铺步骤列表。 */
  public static final class SopTemplate {

    private final String id;
    private final String description;
    private final List<String> keywords;
    private final List<SopGraph.SopNode> steps;

    private SopTemplate(Builder builder) {
      this.id = Objects.requireNonNull(builder.id, "id must not be null");
      this.description = builder.description;
      this.keywords = builder.keywords != null ? List.copyOf(builder.keywords) : List.of();
      this.steps = builder.steps != null ? List.copyOf(builder.steps) : List.of();
    }

    public static Builder builder() {
      return new Builder();
    }

    public String id() {
      return id;
    }

    public String description() {
      return description;
    }

    public List<String> keywords() {
      return keywords;
    }

    /** 获取平铺步骤列表（SopNode 类型，不依赖 Plan.Step）。 */
    public List<SopGraph.SopNode> steps() {
      return steps;
    }

    @Override
    public String toString() {
      return "SopTemplate{id='" + id + "', steps=" + steps.size() + "}";
    }

    // ========== Builder ==========

    public static final class Builder {
      private String id;
      private String description;
      private List<String> keywords;
      private List<SopGraph.SopNode> steps;

      private Builder() {}

      public Builder id(String id) {
        this.id = id;
        return this;
      }

      public Builder description(String description) {
        this.description = description;
        return this;
      }

      public Builder keywords(List<String> keywords) {
        this.keywords = keywords;
        return this;
      }

      public Builder steps(List<SopGraph.SopNode> steps) {
        this.steps = steps;
        return this;
      }

      public Builder addKeyword(String keyword) {
        if (this.keywords == null) this.keywords = new ArrayList<>();
        this.keywords.add(keyword);
        return this;
      }

      public Builder addStep(SopGraph.SopNode step) {
        if (this.steps == null) this.steps = new ArrayList<>();
        this.steps.add(step);
        return this;
      }

      public Builder addStep(String actionType, String target) {
        return addStep(
            new SopGraph.SopNode(
                "step-" + (steps != null ? steps.size() : 0), actionType, target, Map.of(), null));
      }

      public SopTemplate build() {
        return new SopTemplate(this);
      }
    }
  }
}
