package org.cland.alice.core.planner;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Planner 的输出结果，描述一个完整的行动方案。
 *
 * <p>对应设计文档中 Plan 领域对象，可包含一系列步骤或单一 Action 意图。 由 {@link PlannerService#plan(AgentContext)} 返回。
 */
public final class Plan {

  /** 规划类型 */
  public enum Type {
    /** 快速路径 — 直接 LLM 响应或模板匹配 */
    FAST_PATH,
    /** 慢速路径 — MCTS 树搜索后的精炼规划 */
    SLOW_PATH,
    /** 静态规划 — SOP 模板直接解析 */
    STATIC
  }

  private final Type type;
  private final String summary;
  private final List<Step> steps;
  private final Map<String, Object> metadata;

  private Plan(Builder builder) {
    this.type = Objects.requireNonNull(builder.type, "type must not be null");
    this.summary = builder.summary;
    this.steps = builder.steps != null ? List.copyOf(builder.steps) : List.of();
    this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : Map.of();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** 快速创建单步 FAST_PATH 规划 */
  public static Plan fastPath(String summary, String actionType, String target) {
    return builder()
        .type(Type.FAST_PATH)
        .summary(summary)
        .addStep(Step.of(actionType, target))
        .build();
  }

  /** 快速创建 STATIC 规划 */
  public static Plan staticPlan(String summary, List<Step> steps) {
    return builder().type(Type.STATIC).summary(summary).steps(steps).build();
  }

  // ========== Getters ==========

  public Type type() {
    return type;
  }

  public String summary() {
    return summary;
  }

  public List<Step> steps() {
    return steps;
  }

  public Map<String, Object> metadata() {
    return metadata;
  }

  @Override
  public String toString() {
    return "Plan{type=" + type + ", steps=" + steps.size() + ", summary='" + summary + "'}";
  }

  // ========== Step ==========

  /** 规划中的单个步骤，描述一个 action 意图。 */
  public static final class Step {
    private final String
        actionType; // "LLM_INFERENCE" | "TOOL_CALL" | "FINISH" | "REVISION" | "OBSERVE"
    private final String target;
    private final Map<String, Object> parameters;
    private final String thought;

    private Step(String actionType, String target, Map<String, Object> parameters, String thought) {
      this.actionType = Objects.requireNonNull(actionType, "actionType must not be null");
      this.target = target;
      this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
      this.thought = thought;
    }

    public static Step of(String actionType, String target) {
      return new Step(actionType, target, Map.of(), null);
    }

    public static Step of(String actionType, String target, Map<String, Object> parameters) {
      return new Step(actionType, target, parameters, null);
    }

    public static Step of(
        String actionType, String target, Map<String, Object> parameters, String thought) {
      return new Step(actionType, target, parameters, thought);
    }

    public String actionType() {
      return actionType;
    }

    public String target() {
      return target;
    }

    public Map<String, Object> parameters() {
      return parameters;
    }

    public String thought() {
      return thought;
    }

    /** 转换为 {@code Action.Type} 兼容的 Map */
    public Map<String, Object> toActionMap() {
      var map = new java.util.LinkedHashMap<String, Object>();
      map.put("type", actionType);
      map.put("target", target);
      if (!parameters.isEmpty()) map.put("parameters", parameters);
      if (thought != null) map.put("thought", thought);
      return Map.copyOf(map);
    }

    @Override
    public String toString() {
      return "Step{type=" + actionType + ", target='" + target + "'}";
    }
  }

  // ========== Builder ==========

  public static final class Builder {
    private Type type;
    private String summary;
    private List<Step> steps;
    private Map<String, Object> metadata;
    private final java.util.ArrayList<Step> stepBuilder = new java.util.ArrayList<>();

    private Builder() {}

    public Builder type(Type type) {
      this.type = type;
      return this;
    }

    public Builder summary(String summary) {
      this.summary = summary;
      return this;
    }

    public Builder steps(List<Step> steps) {
      this.steps = steps;
      return this;
    }

    public Builder metadata(Map<String, Object> m) {
      this.metadata = m;
      return this;
    }

    public Builder addStep(Step step) {
      stepBuilder.add(step);
      return this;
    }

    public Builder addStep(String actionType, String target) {
      stepBuilder.add(Step.of(actionType, target));
      return this;
    }

    public Plan build() {
      if (steps == null && !stepBuilder.isEmpty()) {
        steps = List.copyOf(stepBuilder);
      }
      return new Plan(this);
    }
  }
}
