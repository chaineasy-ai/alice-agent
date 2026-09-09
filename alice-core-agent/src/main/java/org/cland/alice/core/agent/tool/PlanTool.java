package org.cland.alice.core.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.core.planner.PlannerService;
import org.cland.alice.tool.gateway.annotation.AgentTool;
import org.cland.alice.tool.gateway.annotation.RiskLevel;
import org.cland.alice.tool.gateway.annotation.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 规划工具（tool 层 {@code plan}，D10）— 把战略规划后端暴露为可调用的工具。
 *
 * <p>与 STRATEGIZE/ARBITRATE 决策点共用同一后端（alice-core-planner 保持模块内聚）： 分类路由（FastPath）/ 审慎搜索 （SlowPath
 * MCTS）/ SOP 静态匹配 都在 {@link PlannerService} 内完成。 供 STRATEGIZE 审慎子图与决策循环内按需调用； 产出的是结构化 goal/step
 * <b>建议</b>——绑定为账本事实仍只发生在决策/仲裁写点（D11）。
 *
 * <p>输出为紧凑 JSON 文本（供 LLM 消费）：类型 + 有序步骤（intent/target/thought）+ 元数据摘要。
 */
public final class PlanTool {

  private static final Logger logger = LoggerFactory.getLogger(PlanTool.class);

  private final PlannerService plannerService;
  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * @param plannerService 战略规划后端（不可为 null）
   */
  public PlanTool(PlannerService plannerService) {
    this.plannerService = Objects.requireNonNull(plannerService, "plannerService must not be null");
  }

  /**
   * 对给定任务执行一次规划，返回步骤建议。
   *
   * @param prompt 任务或子目标描述
   * @param context 可选扩展上下文（JSON
   *     对象：availableTools/lastObservation/error/lastFeedback/plannerPrompt…）
   * @return JSON 文本：{type, path, steps:[{intent,target,thought}], metadata}
   */
  @AgentTool(
      name = "plan",
      description =
          "Strategic planning tool. Decomposes the current task into an ordered list of step "
              + "suggestions (goal-level guidance) using the planner backend: SOP matching, fast "
              + "intent classification or slow MCTS search. Each step carries an intent "
              + "(ANALYZE/SEARCH/CODE/GENERATE/ANSWER/FINISH/REVISION) and a target. Use when a "
              + "task is complex, multi-step, or when you need to decide what to do next.",
      risk = RiskLevel.LOW)
  public String plan(
      @ToolParam(value = "prompt", description = "The task or sub-goal to plan for") String prompt,
      @ToolParam(
              value = "context",
              description =
                  "Optional JSON object: availableTools (list), lastObservation, lastActionResult, "
                      + "error, lastFeedback, plannerPrompt")
          String context) {
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("prompt", prompt != null ? prompt : "");
    if (context != null && !context.isBlank()) {
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> extra = mapper.readValue(context, Map.class);
        extra.forEach(ctx::put);
      } catch (Exception e) {
        logger.warn("[PlanTool] Failed to parse context JSON, ignoring: {}", e.getMessage());
      }
    }

    try {
      Plan plan = plannerService.plan(ctx);
      return toJson(plan);
    } catch (Exception e) {
      logger.error("[PlanTool] Planning failed", e);
      return "{\"error\": \"" + e.getMessage() + "\"}";
    }
  }

  /** 把 Plan 收敛为紧凑 JSON（步骤建议 + 元数据摘要）。 */
  private String toJson(Plan plan) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("type", plan.type().name());
    root.put("summary", plan.summary() != null ? plan.summary() : "");

    List<Map<String, Object>> steps = new java.util.ArrayList<>();
    for (Plan.Step step : plan.steps()) {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("intent", step.intent().name());
      s.put("target", step.target() != null ? step.target() : "");
      if (step.thought() != null) {
        s.put("thought", step.thought());
      }
      if (!step.parameters().isEmpty()) {
        s.put("parameters", step.parameters());
      }
      steps.add(s);
    }
    root.put("steps", steps);

    Map<String, Object> meta = new LinkedHashMap<>(plan.metadata());
    root.put("metadata", meta);
    try {
      return mapper.writeValueAsString(root);
    } catch (Exception e) {
      logger.warn("[PlanTool] Failed to serialize plan, falling back to plain text", e);
      return plan.toString();
    }
  }
}
