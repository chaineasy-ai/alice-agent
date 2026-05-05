package org.cland.alice.core.planner.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.core.planner.model.ModelSession;
import org.cland.alice.core.planner.model.ModelSupplier;

import java.util.Map;
import java.util.Objects;

/**
 * 快速路径策略 (System 1)，对应设计文档中的 {@code FastPathStrategy}。
 * <p>
 * 适用于低复杂度任务，通过以下方式快速生成规划：
 * <ul>
 *   <li>直接 LLM 调用（指令模型）</li>
 *   <li>模板匹配（从 SopRegistry 检索）</li>
 *   <li>简单意图分类</li>
 * </ul>
 */
public final class FastPathStrategy implements DecisionStrategy {

    private static final Logger logger = LoggerFactory.getLogger(FastPathStrategy.class);

    private final ModelSupplier modelSupplier;

    public FastPathStrategy(ModelSupplier modelSupplier) {
        this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier must not be null");
    }

    @Override
    public Plan decide(Map<String, Object> context) {
        String prompt = (String) context.getOrDefault("prompt", "");
        String result = context.containsKey("result") ? context.get("result").toString() : null;

            logger.debug("[FastPath] processing prompt length={}", prompt.length());

        // 如果有最终结果，直接返回 FINISH
        if (result != null && !result.isEmpty()) {
            return Plan.fastPath("Task completed", "FINISH", "FINISH");
        }

        // 通过指令模型快速推理
        ModelSession session = modelSupplier.getInstructionModel();
        String modelId = session != null ? session.modelId() : "gpt-4o-mini";

        return Plan.builder()
            .type(Plan.Type.FAST_PATH)
            .summary("Fast path direct LLM call")
            .addStep(Plan.Step.of("LLM_INFERENCE", modelId, Map.of("prompt", prompt)))
            .addStep(Plan.Step.of("FINISH", "FINISH"))
            .metadata(Map.of("path", "fast"))
            .build();
    }
}
