package org.cland.alice.core.planner;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Planner 的输出结果，描述一个完整的行动方案。
 *
 * <p>对应设计文档中 Plan 领域对象，可包含一系列步骤或单一 Action 意图。 由 {@link PlannerService#plan(AgentContext)} 返回。
 *
 * <p>{@link Step} 使用 {@link Intent} 表达 Macro 层业务意图，执行层（AgentExecutor）将其映射为具体的 {@code
 * Action.Type}（LLM_INFERENCE / TOOL_CALL 等）。
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

  /**
   * Macro 层业务意图 — 规划器产出的步骤类型，表达"要做什么方向"，而非"用什么基础设施实现"。
   *
   * <p>执行层（AgentExecutor）通过 {@link #toActionString()} 映射到具体的 {@code Action.Type}。
   */
  public enum Intent {
    /** 需要分析 / 推理 — 映射到 LLM_INFERENCE */
    ANALYZE,
    /** 需要搜索 / 查找信息 — 映射到 TOOL_CALL */
    SEARCH,
    /** 需要编写代码 — 映射到 LLM_INFERENCE */
    CODE,
    /** 需要生成内容 — 映射到 LLM_INFERENCE */
    GENERATE,
    /** 直接回答 — 映射到 FINISH */
    ANSWER,
    /** 任务完成 */
    FINISH,
    /** 需要修订 */
    REVISION;

    /** 映射到 {@code Action.Type} 兼容的字符串。 */
    public String toActionString() {
      return switch (this) {
        case ANALYZE, CODE, GENERATE -> "LLM_INFERENCE";
        case SEARCH -> "TOOL_CALL";
        case ANSWER, FINISH -> "FINISH";
        case REVISION -> "REVISION";
      };
    }
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
  public static Plan fastPath(String summary, Intent intent, String target) {
    return builder().type(Type.FAST_PATH).summary(summary).addStep(Step.of(intent, target)).build();
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

  /** 规划中的单个步骤，描述一个 Macro 业务意图。 */
  public static final class Step {
    private final Intent intent;
    private final String target;
    private final Map<String, Object> parameters;
    private final String thought;

    private Step(Intent intent, String target, Map<String, Object> parameters, String thought) {
      this.intent = Objects.requireNonNull(intent, "intent must not be null");
      this.target = target;
      this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
      this.thought = thought;
    }

    public static Step of(Intent intent, String target) {
      return new Step(intent, target, Map.of(), null);
    }

    public static Step of(Intent intent, String target, Map<String, Object> parameters) {
      return new Step(intent, target, parameters, null);
    }

    public static Step of(
        Intent intent, String target, Map<String, Object> parameters, String thought) {
      return new Step(intent, target, parameters, thought);
    }

    public Intent intent() {
      return intent;
    }

    /**
     * @deprecated use {@link #intent()}
     */
    @Deprecated
    public String actionType() {
      return intent.toActionString();
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
      map.put("type", intent.toActionString());
      map.put("intent", intent.name());
      map.put("target", target);
      if (!parameters.isEmpty()) map.put("parameters", parameters);
      if (thought != null) map.put("thought", thought);
      return Map.copyOf(map);
    }

    @Override
    public String toString() {
      return "Step{intent=" + intent + ", target='" + target + "'}";
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

    public Builder addStep(Intent intent, String target) {
      stepBuilder.add(Step.of(intent, target));
      return this;
    }

    public Builder addStep(
        Intent intent, String target, Map<String, Object> parameters, String thought) {
      stepBuilder.add(Step.of(intent, target, parameters, thought));
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
