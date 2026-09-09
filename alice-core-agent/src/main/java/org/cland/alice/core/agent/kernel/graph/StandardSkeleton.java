package org.cland.alice.core.agent.kernel.graph;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 标准会话骨架装配（§5.3 实例化：规划 → TAO → 反思，递归同构）。
 *
 * <p>把三个"命名装配模式"表达为 R0 可遍历的会话图：
 *
 * <pre>
 * S(STRATEGIZE 复合, 起点必达, D9)
 *   └─ 写点①: goal 图/route/判据/预算 → 账本
 * goal-avail(gate: 有 goal?) ─pass→ tao(ACT 复合, 展开战术子图 Thought→Action→Observe)
 *   └─ goal-done(goal 级 terminal) ─exit port→ vp(verifyPost(g) gate, P7)
 * vp ─pass→ arbitrate(DECISION)          ── PASS → next-goal(gate: 游标+1 → tao / 无 → 会话 terminal)
 *                                   ── FAIL → rev-budget(gate: 修订计数 < 注解预算 → 回 tao 同 goal 重跑)
 *                                            (修订超限 → guard → 回 S 重新审慎, D9 反思重入)
 *                                   ── ROUTE(路线偏差) → 回 S
 *                                   ── ABORT → 会话 terminal
 * </pre>
 *
 * 三条回路全部是图结构（TAO 自环 / FAIL→同 goal 修订 / 偏差·超限→回规划）；"会话结束"只能是 最外层会话 terminal（R2/P1）；goal
 * 迭代由账本游标事实驱动（P2）。brain/门策略为可插拔钩子 —— 生产装配由 L2 Agent 注入（STRATEGIZE 审慎后端 =
 * PlannerService/PlanTool、TAO Thought = Inferencer、 verifyPost = guardrail 规则体系，D8 判据语义维持原实现）。
 */
public final class StandardSkeleton {

  /** STRATEGIZE 审慎决策源（产出 goal 绑定建议 + 结构 payload）。 */
  public interface StrategizeBrain {
    StrategyPlan strategize(String task, Ledger ledger);
  }

  /** STRATEGIZE 产出：goal 图素材 + route/判据/预算结构 payload（写点 ① 落账）。 */
  public record StrategyPlan(List<GoalRef> goals, Map<String, Object> meta) {

    public StrategyPlan {
      goals = goals != null ? List.copyOf(goals) : List.of();
      meta = meta != null ? Map.copyOf(meta) : Map.of();
    }

    public static StrategyPlan of(List<GoalRef> goals) {
      return new StrategyPlan(goals, Map.of());
    }
  }

  /** 战术 Thought 决策源：产出 act（执行效果）或 done（goal 判据提交 + 候选产物，如模型最终回答）。 目标上下文 = 账本游标当前 goal（P7 的目标携带）。 */
  public interface ActorBrain {
    ActorStep think(GoalRef goal, Ledger ledger, String lastObservation);
  }

  /** Thought 决策结果。 */
  public sealed interface ActorStep permits ActorStep.Act, ActorStep.Done {
    /** 执行效果。 */
    public record Act(ToolCallReq tool) implements ActorStep {}

    /** goal 判据提交；answer 为候选会话产物（可为空）。 */
    public record Done(String answer) implements ActorStep {}
  }

  /** 反思仲裁结论（ARBITRATE 三出口 + abort）。 */
  public enum ArbitrationVerdict {
    /** goal 判据通过 → 游标 +1 → 下一 goal。 */
    PASS,

    /** 判据未达 → 修订（回同 goal 的 TAO 重跑）。 */
    FAIL,

    /** 路线偏差 → 回 STRATEGIZE 重新审慎。 */
    ROUTE,

    /** 放弃 → 跳过 / 会话终止。 */
    ABORT
  }

  /** 反思仲裁决策源。 */
  public interface ArbitrationBrain {
    Arbitration arbitrate(GoalRef goal, Ledger ledger, String lastObservation);
  }

  /** 仲裁结论。 */
  public record Arbitration(ArbitrationVerdict verdict, String feedback) {

    public static Arbitration pass() {
      return new Arbitration(ArbitrationVerdict.PASS, null);
    }

    public static Arbitration fail(String feedback) {
      return new Arbitration(ArbitrationVerdict.FAIL, feedback);
    }

    public static Arbitration route(String reason) {
      return new Arbitration(ArbitrationVerdict.ROUTE, reason);
    }

    public static Arbitration abort(String reason) {
      return new Arbitration(ArbitrationVerdict.ABORT, reason);
    }
  }

  // 图内固定节点 id（trace 断言 / 装配引用）
  public static final String N_STRATEGIZE = "S";
  public static final String N_PLAN = "S-plan";
  public static final String N_STRATEGIZE_DONE = "S-done";
  public static final String N_GOAL_AVAIL = "goal-avail";
  public static final String N_TAO = "tao";
  public static final String N_THOUGHT = "thought";
  public static final String N_ACT = "act";
  public static final String N_OBSERVE = "observe";
  public static final String N_TAO_DONE = "goal-done";
  public static final String N_VP = "vp";
  public static final String N_ARBITRATE = "arbitrate";
  public static final String N_REV_BUDGET = "rev-budget";
  public static final String N_NEXT_GOAL = "next-goal";
  public static final String N_SESSION_END = "end-all";

  /** 修订预算注解键（rev-budget gate 节点 attr）。 */
  public static final String ATTR_REVISION_BUDGET = "revisionBudget";

  private final String task;
  private final StrategizeBrain strategize;
  private final ActorBrain actor;
  private final ArbitrationBrain arbitration;
  private final GatePolicy verifyPost;
  private final EffectGateway effects;
  private final int revisionBudget;
  private final int taoEffectBudget;
  private final KernelTraceListener trace;

  private final SessionGraph graph;
  private final R0Interpreter interpreter;

  /**
   * 装配标准会话骨架。
   *
   * @param task 会话任务（STRATEGIZE 的输入）
   * @param strategize STRATEGIZE 审慎决策源
   * @param actor 战术 Thought 决策源（TAO）
   * @param arbitration 反思仲裁决策源（ARBITRATE）
   * @param verifyPost verifyPost(g) 门策略（默认放行；D8 判据内容由装配方提供）
   * @param effects 战术效果网关（TAO 的 Action）
   * @param revisionBudget 每 goal 修订预算注解（超限 → 回规划）
   * @param taoEffectBudget 每 goal 战术效果数注解（熔断）
   */
  public StandardSkeleton(
      String task,
      StrategizeBrain strategize,
      ActorBrain actor,
      ArbitrationBrain arbitration,
      GatePolicy verifyPost,
      EffectGateway effects,
      int revisionBudget,
      int taoEffectBudget) {
    this(
        task,
        strategize,
        actor,
        arbitration,
        verifyPost,
        effects,
        revisionBudget,
        taoEffectBudget,
        null);
  }

  /** 带执行 trace 监听的装配（事件桥接形态）。 */
  public StandardSkeleton(
      String task,
      StrategizeBrain strategize,
      ActorBrain actor,
      ArbitrationBrain arbitration,
      GatePolicy verifyPost,
      EffectGateway effects,
      int revisionBudget,
      int taoEffectBudget,
      KernelTraceListener trace) {
    this.task = Objects.requireNonNull(task, "task");
    this.strategize = Objects.requireNonNull(strategize, "strategize");
    this.actor = Objects.requireNonNull(actor, "actor");
    this.arbitration = Objects.requireNonNull(arbitration, "arbitration");
    this.verifyPost = verifyPost != null ? verifyPost : (n, l, o) -> GateResult.passed();
    this.effects = Objects.requireNonNull(effects, "effects");
    this.revisionBudget = revisionBudget;
    this.taoEffectBudget = taoEffectBudget;
    this.trace = trace;
    this.graph = buildGraph();
    this.interpreter = wireInterpreter();
  }

  public SessionGraph graph() {
    return graph;
  }

  /** 运行一次骨架会话。 */
  public SessionOutcome run(Map<String, Object> options) {
    return interpreter.run(graph, options);
  }

  /** 便捷：默认步数预算下运行。 */
  public SessionOutcome run() {
    return run(Map.of());
  }

  // ========================================================================
  // 图装配：六原语组合出 规划 → TAO → 反思
  // ========================================================================

  private SessionGraph buildGraph() {
    // STRATEGIZE 内部：审慎决策 → 写点①（goal 绑定/判据/预算落账）→ 内部 terminal
    SessionGraph strategizeInner =
        SessionGraph.builder("strategize")
            .node(GraphNode.of(N_PLAN, NodeKind.DECISION))
            .node(GraphNode.of(N_STRATEGIZE_DONE, NodeKind.TERMINAL))
            .start(N_PLAN)
            .edge(N_PLAN, N_STRATEGIZE_DONE, "bound")
            .build();

    // TAO 内部：Thought(decision) → Action(effect, 熔断注解) → Observe → 回 Thought；
    // finish-goal → goal 级 terminal（不终结会话，P1）
    GraphNode actNode =
        GraphNode.of(N_ACT, NodeKind.EFFECT, Map.of(GraphNode.ATTR_MAX_EFFECTS, taoEffectBudget));
    SessionGraph taoInner =
        SessionGraph.builder("tao")
            .node(GraphNode.of(N_THOUGHT, NodeKind.DECISION))
            .node(actNode)
            .node(GraphNode.of(N_OBSERVE, NodeKind.OBSERVE))
            .node(GraphNode.of(N_TAO_DONE, NodeKind.TERMINAL))
            .start(N_THOUGHT)
            .edge(N_THOUGHT, N_ACT, "act")
            .edge(N_THOUGHT, N_TAO_DONE, "finish")
            .edge(N_ACT, N_OBSERVE)
            .edge(N_ACT, N_TAO_DONE, R0Interpreter.PORT_GUARD) // 效果熔断 → 退出 goal（判据提交）
            .edge(N_OBSERVE, N_THOUGHT)
            .build();

    GraphNode revBudgetGate =
        GraphNode.of(N_REV_BUDGET, NodeKind.GATE, Map.of(ATTR_REVISION_BUDGET, revisionBudget));

    return SessionGraph.builder("session")
        .node(
            GraphNode.composite(
                N_STRATEGIZE,
                new CompositeSpec(strategizeInner, Map.of(N_STRATEGIZE_DONE, N_GOAL_AVAIL))))
        .node(GraphNode.of(N_GOAL_AVAIL, NodeKind.GATE))
        .node(GraphNode.composite(N_TAO, new CompositeSpec(taoInner, Map.of(N_TAO_DONE, N_VP))))
        .node(GraphNode.of(N_VP, NodeKind.GATE))
        .node(GraphNode.of(N_ARBITRATE, NodeKind.DECISION))
        .node(revBudgetGate)
        .node(GraphNode.of(N_NEXT_GOAL, NodeKind.GATE))
        .node(GraphNode.of(N_SESSION_END, NodeKind.TERMINAL))
        .start(N_STRATEGIZE)
        // 规划出口：goal-avail 门（有 goal → TAO；无 → 会话结束）
        .edge(N_GOAL_AVAIL, N_TAO, R0Interpreter.PORT_PASS)
        .edge(N_GOAL_AVAIL, N_SESSION_END, "none")
        // verifyPost(g) gate → 反思仲裁
        .edge(N_VP, N_ARBITRATE, R0Interpreter.PORT_PASS)
        .edge(N_VP, N_REV_BUDGET, "reject") // 规则后检拦截 → 按 FAIL 修订处理
        // ARBITRATE 三出口 + abort
        .edge(N_ARBITRATE, N_NEXT_GOAL, "pass")
        .edge(N_ARBITRATE, N_REV_BUDGET, "fail")
        .edge(N_ARBITRATE, N_STRATEGIZE, "route") // 路线偏差 → 回规划
        .edge(N_ARBITRATE, N_SESSION_END, "abort")
        // 修订预算门：未超 → 同 goal 重跑；超限 → 回规划（D9 重入）
        .edge(N_REV_BUDGET, N_TAO, R0Interpreter.PORT_PASS)
        .edge(N_REV_BUDGET, N_STRATEGIZE, "exceed")
        // 下一 goal 门：游标 +1 → TAO；耗尽 → 会话 terminal（R2）
        .edge(N_NEXT_GOAL, N_TAO, R0Interpreter.PORT_PASS)
        .edge(N_NEXT_GOAL, N_SESSION_END, "done")
        .terminalExit(N_SESSION_END, SessionGraph.SESSION_END)
        .build();
  }

  /** 策略挂点装配（brain/门 → R0 解释器）。 */
  private R0Interpreter wireInterpreter() {
    R0Interpreter interpreter = new R0Interpreter();
    if (trace != null) {
      interpreter.trace(trace);
    }
    return interpreter
        // STRATEGIZE 审慎决策（写点 ① 由解释器在 hasStateWrite 时落账）
        .decision(
            N_PLAN,
            (node, ledger, obs) -> {
              StrategyPlan plan = strategize.strategize(task, ledger);
              return new Decision(
                  DecisionKind.FINISH_GOAL,
                  "bound",
                  null,
                  List.of(),
                  plan.goals(),
                  plan.meta(),
                  Map.of());
            })
        // TAO Thought：act（效果）或 done（判据提交 + 候选产物）
        .decision(
            N_THOUGHT,
            (node, ledger, obs) -> {
              ActorStep step = actor.think(ledger.currentGoal(), ledger, obs);
              if (step instanceof ActorStep.Act a) {
                return Decision.act("act", a.tool());
              }
              if (step instanceof ActorStep.Done done) {
                // 判据提交 + 候选产物（answer）经写点 ① 落账为会话产物
                return done.answer() != null && !done.answer().isBlank()
                    ? Decision.finishGoal("finish", Map.of("answer", done.answer()))
                    : Decision.finishGoal("finish");
              }
              throw new IllegalStateException("unhandled actor step: " + step);
            })
        // ARBITRATE：PASS / FAIL(修订) / ROUTE(回规划) / ABORT
        .decision(
            N_ARBITRATE,
            (node, ledger, obs) -> {
              Arbitration a = arbitration.arbitrate(ledger.currentGoal(), ledger, obs);
              String feedback = a.feedback() != null ? a.feedback() : "";
              return switch (a.verdict()) {
                case PASS -> Decision.finishGoal("pass");
                case FAIL ->
                    feedback.isBlank()
                        ? Decision.revise("fail", feedback)
                        : new Decision(
                            DecisionKind.REVISE,
                            "fail",
                            feedback,
                            List.of(),
                            List.of(),
                            Map.of("feedback", feedback),
                            Map.of());
                case ROUTE ->
                    new Decision(
                        DecisionKind.REVISE,
                        "route",
                        feedback,
                        List.of(),
                        List.of(),
                        Map.of("routeDeviation", true, "reason", feedback),
                        Map.of());
                case ABORT -> Decision.abort("abort", feedback);
              };
            })
        // verifyPost(g) gate（P7：goal 经账本游标携带）
        .gate(N_VP, verifyPost)
        // goal-avail：账本事实（有无 goal）
        .gate(
            N_GOAL_AVAIL,
            (node, ledger, obs) ->
                ledger.hasGoals() ? GateResult.passed() : GateResult.guard("none"))
        // next-goal：账本事实（游标是否耗尽），PASS 时游标 +1（仲裁写点 ②）
        .gate(
            N_NEXT_GOAL,
            (node, ledger, obs) -> {
              if (ledger.hasNextGoal()) {
                ledger.advanceGoal();
                return GateResult.passed();
              }
              return GateResult.guard("done");
            })
        // rev-budget：修订注解（R3），超限 guard → 回规划
        .gate(
            N_REV_BUDGET,
            (node, ledger, obs) -> {
              GoalRef goal = ledger.currentGoal();
              if (goal == null) {
                return GateResult.guard("exceed");
              }
              int budget =
                  node.attr(ATTR_REVISION_BUDGET, 0) instanceof Number n ? n.intValue() : 0;
              if (ledger.revisionsOf(goal.id()) < budget) {
                ledger.bumpRevision(goal.id());
                return GateResult.passed();
              }
              return GateResult.guard("exceed");
            })
        .effect(N_ACT, effects);
  }
}
