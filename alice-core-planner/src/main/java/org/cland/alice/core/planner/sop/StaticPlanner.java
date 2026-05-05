package org.cland.alice.core.planner.sop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.cland.alice.core.planner.Plan;

import java.util.Map;
import java.util.Objects;

/**
 * 静态规划器，对应设计文档中的 {@code StaticPlanner}。
 * <p>
 * 负责将 SOP 模板直接解析为一系列 {@link Plan.Step} 列表，
 * 完全跳过模型生成，保证确定性执行。
 * <p>
 * 适用于 SOP 明确的任务（如"查询天气"、"发送邮件"等标准流程）。
 */
public final class StaticPlanner {

    private static final Logger logger = LoggerFactory.getLogger(StaticPlanner.class);

    private final SopRegistry sopRegistry;

    public StaticPlanner(SopRegistry sopRegistry) {
        this.sopRegistry = Objects.requireNonNull(sopRegistry, "sopRegistry must not be null");
    }

    /**
     * 根据上下文匹配并生成静态规划。
     * <p>
     * 如果匹配到 SOP 模板，返回 {@link Plan.Type#STATIC} 的规划；
     * 否则返回 null，由 {@code StrategySelector} 走 fast/slow path。
     *
     * @param context 规划器上下文的只读快照
     * @return 静态规划，或 null（如果无匹配模板）
     */
    public Plan plan(Map<String, Object> context) {
        String prompt = (String) context.getOrDefault("prompt", "");
        if (prompt == null || prompt.isBlank()) return null;

        // 匹配 SOP 模板
        SopRegistry.SopTemplate template = sopRegistry.match(prompt);
        if (template == null) {
            logger.debug("[StaticPlanner] No matching SOP for prompt");
            return null;
        }

            logger.info("[StaticPlanner] Matched SOP: {}", template.id());

        // 直接转换为静态 Plan
        Plan.Builder builder = Plan.builder()
            .type(Plan.Type.STATIC)
            .summary("Static plan from SOP: " + template.id())
            .metadata(Map.of("sopId", template.id()));

        for (Plan.Step step : template.steps()) {
            builder.addStep(step);
        }

        // 如果模板步骤中没有 FINISH，自动添加
        boolean hasFinish = template.steps().stream()
            .anyMatch(s -> "FINISH".equals(s.actionType()));
        if (!hasFinish) {
            builder.addStep(Plan.Step.of("FINISH", "FINISH"));
        }

        return builder.build();
    }
}
