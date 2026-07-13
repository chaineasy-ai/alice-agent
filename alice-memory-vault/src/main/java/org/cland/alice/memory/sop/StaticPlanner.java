package org.cland.alice.memory.sop;

import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.planner.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 静态规划器 — 将 SOP 模板直接解析为 {@link Plan} 步骤列表。
 *
 * <p>完全跳过模型生成，保证确定性执行。适用于 SOP 明确的任务（如"查询天气"、"发送邮件"等标准流程）。
 *
 * <p>作为 {@code alice-core-planner} 中 {@link org.cland.alice.core.planner.PlannerService
 * PlannerService} 的静态规划函数注入。
 */
public final class StaticPlanner {

  private static final Logger logger = LoggerFactory.getLogger(StaticPlanner.class);

  private final SopRegistry sopRegistry;

  public StaticPlanner(SopRegistry sopRegistry) {
    this.sopRegistry = Objects.requireNonNull(sopRegistry, "sopRegistry must not be null");
  }

  /**
   * 根据上下文匹配并生成静态规划。
   *
   * @param context 规划器上下文的只读快照（来自 {@code alice-core-planner}）
   * @return 静态 {@link Plan}，如果没有匹配的 SOP 则返回 null
   */
  public Plan plan(Map<String, Object> context) {
    String prompt = (String) context.getOrDefault("prompt", "");
    if (prompt == null || prompt.isBlank()) return null;

    SopRegistry.SopTemplate template = sopRegistry.match(prompt);
    if (template == null) {
      logger.debug("[StaticPlanner] No matching SOP for prompt");
      return null;
    }

    logger.info("[StaticPlanner] Matched SOP: {}", template.id());

    Plan.Builder builder =
        Plan.builder()
            .type(Plan.Type.STATIC)
            .summary("Static plan from SOP: " + template.id())
            .metadata(Map.of("sopId", template.id()));

    for (SopGraph.SopNode node : template.steps()) {
      builder.addStep(node.intent(), node.target(), node.parameters(), node.thought());
    }

    // 如果模板步骤中没有 FINISH，自动添加
    boolean hasFinish = template.steps().stream().anyMatch(n -> n.intent() == Plan.Intent.FINISH);
    if (!hasFinish) {
      builder.addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH"));
    }

    return builder.build();
  }
}
