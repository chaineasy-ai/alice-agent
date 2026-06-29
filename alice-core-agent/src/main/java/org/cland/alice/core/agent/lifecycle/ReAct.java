package org.cland.alice.core.agent.lifecycle;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.core.planner.PlannerService;

/**
 * ReAct (Reasoning + Acting) 模板 — 定义一个"Reason → Act → Observe"循环规约。
 *
 * <p>ReAct 本身不是实现，而是一个<b>模板代码 (Template Code)</b>：描述"思考 → 行动 → 观察"的循环模式。 当需要使用这个循环模式时，实现此接口即可。
 *
 * <p>核心概念：
 *
 * <ul>
 *   <li><b>Reason (思考)</b> — 基于上下文推理出下一步行动意图
 *   <li><b>Act (行动)</b> — 执行该行动
 *   <li><b>Observe (观察)</b> — 收集行动结果，反馈给下一轮思考
 * </ul>
 *
 * <p><b>默认循环实现</b>：{@link #loop(Map, ReActContext)} 提供一个通用的 Reason→Act→Observe→Reason 循环模板。
 * 调用方只需传入初始上下文，ReAct 会迭代执行直到返回 {@code FINISH}。
 *
 * <p>使用示例：
 *
 * <pre>{@code
 * // 1. 实现 ReAct 模板（定义 Reason 逻辑）
 * ReAct react = ctx -> {
 *   String prompt = (String) ctx.get("prompt");
 *   if (prompt == null || prompt.isEmpty()) {
 *     return Map.of("type", "FINISH", "target", "FINISH");
 *   }
 *   return Map.of(
 *     "type", "LLM_INFERENCE",
 *     "target", "gpt-4o",
 *     "prompt", prompt
 *   );
 * };
 *
 * // 2. 执行 ReAct 循环
 * ReActContext rctx = ReActContext.create();
 * Map<String, Object> result = react.loop(ctx, rctx);
 * }</pre>
 *
 * <p><b>与 PlannerService 的关系</b>：
 *
 * <ul>
 *   <li>{@link PlannerService} 是规划器的根入口，负责"下一步做什么"的决策
 *   <li>ReAct 是一个循环模板，定义了如何使用 PlannerService 的结果来驱动 Reason→Act→Observe 迭代
 *   <li>通过 {@link #from(PlannerService)} 可以将 PlannerService 适配为 ReAct 的 Reason 阶段
 * </ul>
 *
 * @see PlannerService
 * @see ReActContext
 */
@FunctionalInterface
public interface ReAct {

  /**
   * Reason（思考阶段）：基于当前上下文，推理出下一步的行动意图。
   *
   * <p>这是 ReAct 循环中的 <b>Think/Reason</b> 步骤 — 根据已有的观察和状态， 决定下一步应该做什么。
   *
   * <p>返回的 Map 包含：
   *
   * <ul>
   *   <li>{@code "type"} — 意图类型: "LLM_INFERENCE" | "TOOL_CALL" | "FINISH" | "REVISION"
   *   <li>{@code "target"} — 目标（模型 ID / 工具名）
   *   <li>{@code "prompt"} — 推理用的 prompt（可选）
   * </ul>
   *
   * @param context AgentContext 的只读快照（Map 视图），包含当前状态和之前的观察
   * @return 描述下一步意图的 Map
   */
  Map<String, Object> reason(Map<String, Object> context);

  /**
   * 执行完整的 ReAct 循环：Reason → Act → Observe → Reason → ... → FINISH。
   *
   * <p>这是 ReAct 模式的核心模板方法，定义了迭代过程：
   *
   * <pre>
   *   loop(context):
   *     while true:
   *       intent = reason(context)         // 思考：下一步做什么？
   *       if intent.type == FINISH: break  // 终止条件
   *       observation = act(intent)         // 行动：执行意图
   *       context = observe(observation)    // 观察：更新上下文
   *     return context
   * </pre>
   *
   * <p>{@code reactContext} 跟踪循环状态（迭代次数、token 消耗等）。 子类可以重写 {@link #act(Map)} 和 {@link
   * #observe(Object, Map)} 来定制 Act 和 Observe 阶段的行为。
   *
   * @param initialContext 初始上下文（Map 视图）
   * @param reactContext ReAct 循环上下文（跟踪状态）
   * @return 循环结束后的最终上下文
   */
  default Map<String, Object> loop(Map<String, Object> initialContext, ReActContext reactContext) {
    Objects.requireNonNull(initialContext, "initialContext must not be null");
    Objects.requireNonNull(reactContext, "reactContext must not be null");

    Map<String, Object> ctx = new java.util.LinkedHashMap<>(initialContext);

    while (!reactContext.isFinished()) {
      reactContext.incrementIteration();

      // Phase 1: Reason — 思考下一步行动
      Map<String, Object> intent = reason(Map.copyOf(ctx));

      String type = (String) intent.getOrDefault("type", "LLM_INFERENCE");

      // Phase 2: Check FINISH
      if ("FINISH".equals(type)) {
        reactContext.markFinished();
        break;
      }

      // Phase 3: Act — 执行行动
      Object observation = act(intent);
      reactContext.recordAction(type, observation);

      // Phase 4: Observe — 收集观察结果，更新上下文
      ctx = observe(observation, ctx);

      // Phase 5: Check budget/limits
      if (reactContext.isBudgetExhausted()) {
        reactContext.markFinished();
        break;
      }
    }

    ctx.put("__react_iterations", reactContext.iteration());
    ctx.put("__react_finished", reactContext.isFinished());
    return Map.copyOf(ctx);
  }

  /**
   * Act（行动阶段）：执行 Reason 阶段产生的意图。
   *
   * <p>默认实现通过 {@code intent} 中的 type 字段做简单分发：
   *
   * <ul>
   *   <li>{@code LLM_INFERENCE} — 返回 prompt 字符串（模拟 LLM 响应）
   *   <li>{@code TOOL_CALL} — 返回工具名 + 参数
   *   <li>{@code REVISION} — 返回反馈信息
   * </ul>
   *
   * <p>子类应重写此方法以集成真实的 LLM 调用或工具执行。
   *
   * @param intent Reason 阶段返回的行动意图
   * @return Act 阶段产生的原始观察
   */
  default Object act(Map<String, Object> intent) {
    String type = (String) intent.getOrDefault("type", "LLM_INFERENCE");
    String target = (String) intent.get("target");
    return switch (type) {
      case "LLM_INFERENCE" -> "[ReAct: would call LLM " + target + "]";
      case "TOOL_CALL" -> "[ReAct: would call tool " + target + "]";
      case "REVISION" -> intent.getOrDefault("feedback", "Revision requested");
      default -> "[ReAct: unknown action " + type + "]";
    };
  }

  /**
   * Observe（观察阶段）：将 Act 阶段的观察结果合并回上下文中。
   *
   * <p>默认实现将 {@code observation} 存入 {@code ctx["lastObservation"]}。 子类可以重写此方法以执行更复杂的上下文更新（如持久化到
   * Memory）。
   *
   * @param observation Act 阶段返回的原始观察
   * @param context 当前上下文
   * @return 更新后的新上下文
   */
  @SuppressWarnings("unchecked")
  default Map<String, Object> observe(Object observation, Map<String, Object> context) {
    Map<String, Object> ctx = new java.util.LinkedHashMap<>(context);
    ctx.put("lastObservation", observation);
    ctx.put("__react_observation", observation);
    return ctx;
  }

  /** 将 {@link ReAct} 适配为 {@link Function}，便于与 {@link java.util.Optional} 或 stream 链式使用。 */
  default Function<Map<String, Object>, Map<String, Object>> asFunction() {
    return this::reason;
  }

  /** 创建一个始终返回 {@code FINISH} 的终止 ReAct。 用于测试或作为空实现。 */
  static ReAct finish() {
    return ctx -> Map.of("type", "FINISH", "target", "FINISH");
  }

  /**
   * 将 {@link PlannerService} 适配为 ReAct 的 Reason 阶段。
   *
   * <p>PlannerService 的 {@code plan(Map)} 方法扮演"思考"角色， 其返回的 Plan 被转换为 ReAct 兼容的意图 Map。
   *
   * @param plannerService 已配置的 PlannerService 实例
   * @return 以 plannerService 为 Reason 引擎的 ReAct
   */
  static ReAct from(PlannerService plannerService) {
    Objects.requireNonNull(plannerService, "plannerService must not be null");
    return ctx -> {
      // Check FINISH condition
      if (ctx.containsKey("result") && ctx.get("result") != null) {
        String result = ctx.get("result").toString();
        if (!result.isEmpty()) {
          return Map.of("type", "FINISH", "target", "FINISH");
        }
      }

      Plan plan = plannerService.plan(ctx);
      return planToIntent(plan, ctx);
    };
  }

  // ========== 辅助方法 ==========

  /** 将 Plan 转换为 ReAct 兼容的意图 Map（仅取第一步）。 */
  private static Map<String, Object> planToIntent(Plan plan, Map<String, Object> context) {
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
        // 转发所有额外参数（enable_thinking, reasoning_effort 等）
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
}
