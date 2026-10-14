package org.cland.alice.core.agent.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.agent.kernel.graph.GoalRef;
import org.cland.alice.core.agent.kernel.graph.Ledger;
import org.cland.alice.core.agent.kernel.graph.StandardSkeleton;
import org.cland.alice.core.agent.prompt.PromptManager;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.core.planner.PlannerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * STRATEGIZE 审慎决策源的生产适配（D10：planner 模块不拆，作为战略规划后端）。
 *
 * <p>把 PlannerService 的 Plan（步骤建议）映射为账本 goal 图素材（写点 ① 由解释器落账）：
 *
 * <ul>
 *   <li><b>多步骤全部入队</b> —— 取代 legacy 的 steps[0] 单步消费（修 P2）；goal 队列由账本游标推进
 *   <li><b>FINISH 步骤过滤</b> —— 计划级结束符不是 goal（R2：会话终止由图 exit 链/仲裁决定）
 *   <li><b>修订反馈随回路携带</b> —— 反思 FAIL/ROUTE 落账的 feedback 在重新审慎时以 lastFeedback 回填规划上下文（P5）
 * </ul>
 *
 * 规划上下文最小集：prompt=任务 + lastFeedback（修订重入时）+ plannerPrompt 扩展点（后续按 D6 注入规则/观察）。
 */
public final class PlannerGoalBrain implements StandardSkeleton.StrategizeBrain {

  private static final Logger logger = LoggerFactory.getLogger(PlannerGoalBrain.class);

  private final PlannerService planner;

  /** 总重规划封顶（#520 方案 B）：>0 时，第 maxPlans 次之后的 STRATEGIZE 返回空 goal 队列 ⇒ 会话落终态。 */
  private final int maxPlans;

  /** 本会话已执行的 STRATEGIZE 次数（每会话一个 brain 实例）。 */
  private int planCount;

  /**
   * @param planner 战略规划后端（PlannerService 聚合根，含 Fast/Slow/SOP）
   */
  public PlannerGoalBrain(PlannerService planner) {
    this(planner, 0);
  }

  /**
   * @param planner 战略规划后端
   * @param maxPlans 总重规划封顶（≤0 = 不封顶）
   */
  public PlannerGoalBrain(PlannerService planner, int maxPlans) {
    this.planner = Objects.requireNonNull(planner, "planner must not be null");
    this.maxPlans = maxPlans;
  }

  @Override
  public StandardSkeleton.StrategyPlan strategize(String task, Ledger ledger) {
    planCount++;
    // #520 方案 B：与 legacy maxIterations 等效的总重规划封顶。
    // 无此封顶时：VP 拦截 → 修订 → 预算耗尽 → re-plan 新 goal → 无界循环，直到 CLI 超时。
    if (maxPlans > 0 && planCount > maxPlans) {
      logger.warn(
          "[PlannerGoalBrain] re-plan cap {} reached (plan #{}) -> ending session with no goals",
          maxPlans,
          planCount);
      return new StandardSkeleton.StrategyPlan(List.of(), Map.of("capped", true));
    }

    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("prompt", task != null ? task : "");
    // #520 根因修复：与 legacy（AgentExecutor）对齐，注入意图分类模板。
    // 缺失时 FastPathStrategy.classifyIntent 会把原始任务当分类 prompt，得到散文 → fallback ANALYZE
    // ⇒ goal=ANALYZE:* ⇒ actor 只分析不落盘（「能答≠能改」）。
    ctx.put("plannerPrompt", PromptManager.buildPlannerPrompt(task != null ? task : "", ctx));
    Object feedback = ledger.route().get("feedback");
    if (feedback != null) {
      ctx.put("lastFeedback", feedback);
      logger.info("[PlannerGoalBrain] re-planning with feedback: {}", feedback);
    }

    Plan plan = planner.plan(ctx);

    List<GoalRef> goals = new ArrayList<>();
    for (Plan.Step step : plan.steps()) {
      if (step.intent() == Plan.Intent.FINISH) {
        continue; // 计划级结束符不入 goal 队列（R2）
      }
      String target = step.target() != null ? step.target() : "";
      goals.add(GoalRef.of("g" + goals.size(), step.intent().name() + ":" + target));
    }

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("planType", plan.type().name());
    Object path = plan.metadata().get("path");
    if (path != null) {
      meta.put("path", path);
    }
    logger.info(
        "[PlannerGoalBrain] plan type={} steps={} -> goals={}",
        plan.type(),
        plan.steps().size(),
        goals.size());
    return new StandardSkeleton.StrategyPlan(goals, meta);
  }
}
