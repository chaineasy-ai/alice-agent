package org.cland.alice.core.planner;

import java.util.Map;

/**
 * ReAct (Reasoning + Acting) 规划器。
 * <p>
 * 对应设计文档中 Planner (P) 的角色，负责在 PPAO 循环的 Plan 阶段
 * 基于当前上下文推理出下一步的意图。
 */
public class ReAct {

    /**
     * 基于当前上下文，提议下一步的执行意图。
     * <p>
     * 返回一个描述意图的 Map，包含：
     * <ul>
     *   <li>{@code "type"} — 意图类型: "LLM_INFERENCE" | "TOOL_CALL" | "FINISH" | "REVISION"</li>
     *   <li>{@code "target"} — 目标（模型 ID / 工具名）</li>
     *   <li>{@code "prompt"} — 推理用的 prompt（可选）</li>
     * </ul>
     *
     * @param context 当前 Agent 上下文的只读快照
     * @return 描述意图的 Map
     */
    public Map<String, Object> proposeNext(Map<String, Object> context) {
        // 检查是否有最终结果
        if (context.containsKey("result") && context.get("result") != null) {
            return Map.of("type", "FINISH", "target", "FINISH");
        }

        // 默认策略：获取 prompt 并执行 LLM 推理
        String prompt = context.containsKey("prompt")
            ? context.get("prompt").toString()
            : "Hello!";
        String modelId = context.containsKey("model")
            ? context.get("model").toString()
            : "gpt-4o-mini";

        return Map.of(
            "type", "LLM_INFERENCE",
            "target", modelId,
            "prompt", prompt
        );
    }
}
