package org.cland.alice.core.agent.kernel;

import java.util.List;

/**
 * 一次 Infer 的语义化观测结果（内核词汇，§6.1）。
 *
 * <p>字段为语义层词汇：内容 / 推理链 / 工具决策 / 终止状态。 厂商词（reasoning_content/finish_reason/tool_calls 的协议形态）由
 * pipeline 翻译后填充，不进内核。
 *
 * @param content 模型输出内容（可能为空串）
 * @param reasoning 推理/思考链（由 pipeline 从厂商原始响应解码）
 * @param toolCalls 工具决策列表（状态为 TOOL_CALLS 时非空）
 * @param status 语义化终止状态
 * @param detail 附加诊断细节（失败原因 / 截断原词），仅供 legacy 日志与 WAL 标签使用
 */
public record ModelObservation(
    String content,
    String reasoning,
    List<ToolDecision> toolCalls,
    ModelStatus status,
    String detail) {

  /** 一次工具决策（语义化）：工具名 + 参数 JSON 文本。 */
  public record ToolDecision(String name, String argumentsJson) {}

  public ModelObservation {
    toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
  }

  /** 是否有工具决策。 */
  public boolean hasToolCalls() {
    return !toolCalls.isEmpty();
  }

  /** 便捷工厂：自然完成（CONTENT）。 */
  public static ModelObservation content(String content, String reasoning) {
    return new ModelObservation(content, reasoning, List.of(), ModelStatus.CONTENT, null);
  }
}
