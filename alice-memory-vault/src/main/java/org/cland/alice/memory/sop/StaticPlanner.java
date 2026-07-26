package org.cland.alice.memory.sop;

import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.planner.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 静态规划器 — 将 SOP 模板解析为链式步骤列表，每次返回下一步。
 *
 * <p>通过 AgentContext 中的 {@code sopActive} / {@code sopSteps} / {@code sopStepIdx} 追踪进度。 不修改传入的
 * context（不可变快照），SOP 状态通过 Plan.metadata 返回，由调用方写入 AgentContext。
 */
public final class StaticPlanner {

  private static final Logger logger = LoggerFactory.getLogger(StaticPlanner.class);

  private final SopRegistry sopRegistry;

  public StaticPlanner(SopRegistry sopRegistry) {
    this.sopRegistry = Objects.requireNonNull(sopRegistry, "sopRegistry must not be null");
  }

  /**
   * 匹配 SOP 并返回当前步骤。
   *
   * <p>已激活时（{@code sopActive=true}）取 {@code sopStepIdx} 对应的步骤。 未激活时匹配 prompt，命中则返回第一步并激活。
   *
   * @param context 只读快照，含 {@code prompt}、{@code sopActive}、{@code sopSteps}、{@code sopStepIdx}
   * @return Plan（单步），SOP 已完成或无匹配时返回 null
   */
  public Plan plan(Map<String, Object> context) {
    // ── 已激活：取下一步 ──
    if (Boolean.TRUE.equals(context.get("sopActive"))) {
      return nextStep(context);
    }

    // ── 未激活：匹配 prompt ──
    String prompt = (String) context.getOrDefault("prompt", "");
    if (prompt == null || prompt.isBlank()) return null;

    SopRegistry.SopTemplate template = sopRegistry.match(prompt);
    if (template == null) return null;

    // 从 graph 构建步骤链
    SopGraph graph = sopRegistry.getGraph(template.id());
    if (graph == null) return null;

    var steps = graph.topologicalOrder();
    if (steps.isEmpty()) return null;

    logger.info(
        "[StaticPlanner] Matched SOP '{}' ({} steps, keywords: {})",
        graph.id(),
        steps.size(),
        graph.keywords());

    // 构建 Plan for first step
    var first = steps.get(0);
    return buildPlan(graph.id(), steps, first, 0);
  }

  /** 返回下一步。从 context 读取状态，通过 metadata 传出给调用方。 */
  private Plan nextStep(Map<String, Object> context) {
    String sopId = (String) context.get("sopId");
    int idx =
        context.containsKey("sopStepIdx") ? ((Number) context.get("sopStepIdx")).intValue() : 0;
    @SuppressWarnings("unchecked")
    var steps = (java.util.List<SopGraph.SopNode>) context.get("sopSteps");

    if (steps == null || idx >= steps.size()) {
      logger.info(
          "[StaticPlanner] SOP '{}' completed (step {}/{})",
          sopId,
          idx,
          steps != null ? steps.size() : 0);
      return null;
    }

    var node = steps.get(idx);
    return buildPlan(sopId, steps, node, idx);
  }

  /** 构建单步 Plan，进度状态写入 metadata。 */
  private Plan buildPlan(
      String sopId, java.util.List<SopGraph.SopNode> allSteps, SopGraph.SopNode node, int idx) {
    int nextIdx = idx + 1;
    boolean hasNext = nextIdx < allSteps.size();

    var meta = new java.util.LinkedHashMap<String, Object>();
    meta.put("sopId", sopId);
    meta.put("sopStepIdx", nextIdx); // 下一步索引（调用方写入上下文）
    meta.put("sopActive", hasNext); // 是否还有后续
    meta.put("sopSteps", allSteps); // 所有步骤（供后续迭代）
    if (hasNext) {
      var next = allSteps.get(nextIdx);
      meta.put("sopNext", next.id() + "(" + next.intent() + ")");
    }

    logger.info(
        "[StaticPlanner] SOP '{}' step {}/{}: intent={}, target={}{}",
        sopId,
        idx + 1,
        allSteps.size(),
        node.intent(),
        node.target(),
        hasNext ? " → next: " + meta.get("sopNext") : " (final)");

    return Plan.builder()
        .type(Plan.Type.STATIC)
        .summary("SOP " + sopId + " step " + (idx + 1) + "/" + allSteps.size())
        .addStep(Plan.Step.of(node.intent(), node.target(), node.parameters(), node.thought()))
        .metadata(Map.copyOf(meta))
        .build();
  }
}
