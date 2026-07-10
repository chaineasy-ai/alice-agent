package org.cland.alice.core.agent.lifecycle;

import java.util.Map;
import org.cland.alice.core.planner.Plan;

/**
 * Plan → Action 意图转换器。
 *
 * <p>将 {@link Plan}（规划器输出，多步步骤列表）转换为 {@link Action}（单步可执行动作）。 遵循单一职责原则（SRP）：仅负责 Plan-to-Action
 * 的数据转换，不参与编排逻辑。
 *
 * <p>职责范围：
 *
 * <ul>
 *   <li>取 Plan 的第一步（firstStep）作为当前 Macro 意图
 *   <li>将 Plan.Step 的 actionType/target/parameters 映射为 Action
 *   <li>当 Plan 为空时，生成默认的 LLM_INFERENCE Action
 * </ul>
 */
public final class PlanToIntentConverter {

  private PlanToIntentConverter() {}

  /**
   * 将 {@link Plan} 转换为 ReAct 兼容的意图 Map（仅取第一步）。
   *
   * <p>Plan 是 PlannerService 的输出，包含多个步骤。 AgentExecutor 只需要第一步作为当前 Action 意图。
   *
   * @param plan 规划器输出（可能包含多步）
   * @param context 当前上下文（用于填充默认 prompt）
   * @return 意图 Map，包含 "type"、"target"、"prompt" 等键
   */
  public static Map<String, Object> planToIntent(Plan plan, Map<String, Object> context) {
    if (plan.steps().isEmpty()) {
      return Map.of(
          "type", "LLM_INFERENCE",
          "target", "gpt-4o-mini",
          "prompt", context.getOrDefault("prompt", "Hello!"));
    }

    Plan.Step firstStep = plan.steps().get(0);
    String actionType = firstStep.actionType();

    return switch (actionType) {
      case "FINISH" -> Map.of("type", "FINISH", "target", "FINISH");
      case "TOOL_CALL" -> {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("type", "TOOL_CALL");
        m.put("target", firstStep.target());
        if (!firstStep.parameters().isEmpty()) m.put("parameters", firstStep.parameters());
        if (firstStep.thought() != null) m.put("thought", firstStep.thought());
        yield Map.copyOf(m);
      }
      case "REVISION" -> {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("type", "REVISION");
        m.put("target", "REVISION");
        m.put("feedback", firstStep.parameters().getOrDefault("feedback", "Revision requested"));
        yield Map.copyOf(m);
      }
      default -> {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("type", "LLM_INFERENCE");
        m.put("target", firstStep.target() != null ? firstStep.target() : "gpt-4o-mini");
        m.put(
            "prompt",
            firstStep
                .parameters()
                .getOrDefault("prompt", context.getOrDefault("prompt", "Hello!")));
        if (firstStep.thought() != null) m.put("thought", firstStep.thought());
        for (var e : firstStep.parameters().entrySet()) {
          String k = e.getKey();
          if (!"prompt".equals(k) && !"thought".equals(k)) {
            m.put(k, e.getValue());
          }
        }
        yield Map.copyOf(m);
      }
    };
  }

  /**
   * 将意图 Map 转换为 {@link Action}。
   *
   * @param plan 意图 Map（来自 {@link #planToIntent}）
   * @return 可执行的 Action 实例
   */
  public static Action mapToAction(Map<String, Object> plan) {
    String type = (String) plan.getOrDefault("type", "LLM_INFERENCE");
    String target = (String) plan.getOrDefault("target", "gpt-4o-mini");

    return switch (type) {
      case "FINISH" -> Action.finish();
      case "TOOL_CALL" -> {
        @SuppressWarnings("unchecked")
        Map<String, Object> params =
            (Map<String, Object>) plan.getOrDefault("parameters", Map.of());
        yield Action.toolCall(target, params);
      }
      case "REVISION" -> {
        String feedback = (String) plan.getOrDefault("feedback", "Revision requested");
        yield Action.revision(feedback);
      }
      default -> {
        String prompt = (String) plan.getOrDefault("prompt", "Hello!");
        var action = Action.builder().type(Action.Type.LLM_INFERENCE).target(target);
        action.parameter("prompt", prompt);
        for (var e : plan.entrySet()) {
          String k = e.getKey();
          if (!"type".equals(k)
              && !"target".equals(k)
              && !"prompt".equals(k)
              && !"thought".equals(k)) {
            Object v = e.getValue();
            if (v != null) action.parameter(k, v);
          }
        }
        if (plan.containsKey("thought")) {
          action.parameter("thought", plan.get("thought"));
        }
        yield action.build();
      }
    };
  }
}
