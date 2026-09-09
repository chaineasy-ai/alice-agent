package org.cland.alice.core.agent.kernel.graph;

import java.util.Map;

/**
 * 语义决策输出空间（R1，§5.2.3）— STRATEGIZE 审慎决策与战术 DECIDE 共用。
 *
 * @param kind 决策类型（answer/act/finish-goal/revise/abort）
 * @param route 出边端口名：d.route 指向的边（解释器沿它走）
 * @param content answer/revise/abort 的文本内容
 * @param toolCalls act 携带的效果请求（首个被当前 effect 节点消费）
 * @param bindings 结构 payload：goal 绑定建议（写点 ① 落账为账本事实）
 * @param meta 附加结构 payload（route/判据/预算重分配等，写点 ① 落账）
 * @param artifacts 会话产物候选（如最终回答；写点 ① 经 {@link Ledger#recordArtifact} 落账）
 */
public record Decision(
    DecisionKind kind,
    String route,
    String content,
    java.util.List<ToolCallReq> toolCalls,
    java.util.List<GoalRef> bindings,
    Map<String, Object> meta,
    Map<String, Object> artifacts) {

  public Decision {
    toolCalls = toolCalls != null ? java.util.List.copyOf(toolCalls) : java.util.List.of();
    bindings = bindings != null ? java.util.List.copyOf(bindings) : java.util.List.of();
    meta = meta != null ? Map.copyOf(meta) : Map.of();
    artifacts = artifacts != null ? Map.copyOf(artifacts) : Map.of();
  }

  /** 是否携带结构写（goal 绑定/仲裁结论/预算重分配/产物）——写点 ① 判据。 */
  public boolean hasStateWrite() {
    return !bindings.isEmpty() || !meta.isEmpty() || !artifacts.isEmpty();
  }

  public static Decision answer(String route, String content) {
    return new Decision(
        DecisionKind.ANSWER,
        route,
        content,
        java.util.List.of(),
        java.util.List.of(),
        Map.of(),
        Map.of());
  }

  public static Decision act(String route, ToolCallReq call) {
    return new Decision(
        DecisionKind.ACT,
        route,
        null,
        java.util.List.of(call),
        java.util.List.of(),
        Map.of(),
        Map.of());
  }

  /** goal 级判据提交（finish-goal）——不终结会话，只是 goal 级 terminal 判据。 */
  public static Decision finishGoal(String route) {
    return new Decision(
        DecisionKind.FINISH_GOAL,
        route,
        null,
        java.util.List.of(),
        java.util.List.of(),
        Map.of(),
        Map.of());
  }

  /** goal 级判据提交并携带会话产物（如模型最终回答 answer，写点 ① 落账）。 */
  public static Decision finishGoal(String route, Map<String, Object> artifacts) {
    return new Decision(
        DecisionKind.FINISH_GOAL,
        route,
        null,
        java.util.List.of(),
        java.util.List.of(),
        Map.of(),
        artifacts);
  }

  public static Decision revise(String route, String feedback) {
    return new Decision(
        DecisionKind.REVISE,
        route,
        feedback,
        java.util.List.of(),
        java.util.List.of(),
        Map.of(),
        Map.of());
  }

  public static Decision abort(String route, String reason) {
    return new Decision(
        DecisionKind.ABORT,
        route,
        reason,
        java.util.List.of(),
        java.util.List.of(),
        Map.of(),
        Map.of());
  }
}
