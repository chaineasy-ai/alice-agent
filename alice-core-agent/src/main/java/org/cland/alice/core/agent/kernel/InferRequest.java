package org.cland.alice.core.agent.kernel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本 LLM 决策请求（内核语义层，§6.1）。
 *
 * <p>一次 Infer = 一次决策请求（Loop 内的 LLM 触点）；效果由工具/IO 通道执行。 厂商原始词（finish_reason / tools schema /
 * reasoning_content）不出现在此签名 —— 是 pipeline 内部语言。
 *
 * <p>本实现（legacy 过渡期）的 system/user prompt 由上层（PromptManager + executor 上下文重建）解析完成； 动态
 * PromptProvider（D6：registerSource/多级覆盖）落地后，模板解析将收进 pipeline ① Resolve 段。
 *
 * @param modelId 模型 ID
 * @param systemPrompt 系统提示词（可为 null，表示无 system role）
 * @param userPrompt 用户提示词
 * @param parameters 厂商无关的附加参数（如 enable_thinking/reasoning_effort 透传）
 * @param tools 函数调用工具 schema 素材（可为空列表，表示不附加工具）
 */
public record InferRequest(
    String modelId,
    String systemPrompt,
    String userPrompt,
    Map<String, Object> parameters,
    List<ToolSpec> tools) {

  /** 工具 schema 素材：由请求方从能力注册处收集，pipeline ③ Serialize 段负责编为厂商协议。 */
  public record ToolSpec(String name, String description, Object inputSchema) {}

  public InferRequest {
    parameters = normalize(parameters);
    tools = tools != null ? List.copyOf(tools) : List.of();
  }

  /** 便捷工厂：文本会话（无工具）。 */
  public static InferRequest text(String modelId, String systemPrompt, String userPrompt) {
    return new InferRequest(modelId, systemPrompt, userPrompt, Map.of(), List.of());
  }

  /** 附带工具素材的新请求。 */
  public InferRequest withTools(List<ToolSpec> toolSpecs) {
    return new InferRequest(modelId, systemPrompt, userPrompt, parameters, toolSpecs);
  }

  private static Map<String, Object> normalize(Map<String, Object> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return Map.of();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
  }
}
