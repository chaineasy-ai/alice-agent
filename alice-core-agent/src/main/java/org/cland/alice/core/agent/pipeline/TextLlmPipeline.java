package org.cland.alice.core.agent.pipeline;

import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cland.alice.core.agent.kernel.InferRequest;
import org.cland.alice.core.agent.kernel.InferRequest.ToolSpec;
import org.cland.alice.core.agent.kernel.Inferencer;
import org.cland.alice.core.agent.kernel.ModelObservation;
import org.cland.alice.core.agent.kernel.ModelObservation.ToolDecision;
import org.cland.alice.core.agent.kernel.ModelStatus;
import org.cland.alice.model.Call;
import org.cland.alice.model.CallStatus;
import org.cland.alice.model.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文本 LLM pipeline（actor kind，六段有序链，§6.2）。
 *
 * <p>实现内核决策循环的 LLM 触点：把"怎么问模型、怎么解析回答"封闭在六段内， 对外只暴露 {@link Inferencer} 语义契约。
 *
 * <pre>
 *  ① Resolve   ② Assemble   ③ Serialize   ④ Transport   ⑤ Decode     ⑥ Observe→Record
 *  Prompt      消息组装      序列化/编码    网络/执行     响应解析      语义解码+记录
 *  (上层已解析) (roles 直通)  tools schema  ModelProvider (extract:     (→ModelObservation/
 *                            /thinking     dispatch      content/       Status 翻译)
 *                            参数转发)
 * </pre>
 *
 * <p>每段职责与扩展点对应 docs/alice-core-agent/kernel-architecture.md §6.2 表格； 按 kind spec 装配（D5：先内部实现、后装配）
 * 前，各段以内部方法呈现。④ 超时/重试属本策略层（内核只管"一次 Infer 有超时上限"这一执行点）。
 *
 * <p>线程模型：本实现同步完成 —— 传输为阻塞调用，返回已完成的 Future，调用方可在阻塞线程内安全等待。
 */
public final class TextLlmPipeline implements Inferencer {

  private static final Logger logger = LoggerFactory.getLogger(TextLlmPipeline.class);

  /** 构造。传输段使用 {@link ModelProvider} 单例（测试可经 ModelProvider.reset + stub 供应商接管）。 */
  public TextLlmPipeline() {}

  @Override
  public Future<ModelObservation> infer(InferRequest request) {
    // ① Resolve：角色 prompt 解析。legacy 过渡期由上层（PromptManager + executor 上下文重建）完成，
    // 此处直通；动态 PromptProvider（D6）落地后此段接管模板解析。
    Resolved resolved = resolve(request);

    // ② Assemble：消息组装。legacy 传输契约直接接收 system/user 文本，此处保持直通。
    Assembled assembled = assemble(resolved);

    // ③ Serialize：把工具素材与附加参数编码为厂商请求体字段。
    Payload payload = serialize(assembled);

    // ④ Transport：网络/执行（超时/重试策略属本段；现状：无重试，由 ModelProvider 直发）。
    // 执行层异常向上抛出（由调用方以 "call error" 语义处理），与 legacy 行为一致。
    Call call = transport(payload);
    logger.info(
        "[Pipeline/Transport] model={} status={} promptLength={} tools={}",
        request.modelId(),
        call.status(),
        request.userPrompt() != null ? request.userPrompt().length() : 0,
        request.tools().size());

    // ⑤ Decode：抠 content/reasoning/tool_calls/finish_reason（厂商原词，不进内核）。
    Decoded decoded = decode(call);

    // ⑥ Observe→Record：协议词 → 内核语义（Status/Decision 翻译）；WAL 触发点仍归执行器。
    ModelObservation observation = observe(decoded);
    logger.info(
        "[Pipeline/Map] status={} contentLength={} reasoningLength={} toolCalls={}",
        observation.status(),
        observation.content() != null ? observation.content().length() : 0,
        observation.reasoning() != null ? observation.reasoning().length() : 0,
        observation.toolCalls().size());
    return Future.succeededFuture(observation);
  }

  // ========================================================================
  // ① Resolve — 角色 prompt 解析
  // ========================================================================

  /** 角色解析结果（system/user/参数/工具素材）。 */
  private record Resolved(
      String modelId,
      String systemPrompt,
      String userPrompt,
      Map<String, Object> params,
      List<ToolSpec> tools) {}

  private Resolved resolve(InferRequest request) {
    return new Resolved(
        request.modelId(),
        request.systemPrompt(),
        request.userPrompt(),
        request.parameters(),
        request.tools());
  }

  // ========================================================================
  // ② Assemble — 消息组装（legacy 直通）
  // ========================================================================

  private record Assembled(
      String modelId,
      String systemPrompt,
      String userPrompt,
      Map<String, Object> params,
      List<ToolSpec> tools) {}

  private Assembled assemble(Resolved resolved) {
    return new Assembled(
        resolved.modelId(),
        resolved.systemPrompt(),
        resolved.userPrompt(),
        resolved.params(),
        resolved.tools());
  }

  // ========================================================================
  // ③ Serialize — 请求体编码（tools schema / thinking 参数转发）
  // ========================================================================

  /** 编码后的请求负载：modelId + 系统/用户文本 + 请求参数（含 tools 数组）。 */
  private record Payload(
      String modelId, String systemPrompt, String userPrompt, Map<String, Object> callParams) {}

  private Payload serialize(Assembled assembled) {
    Map<String, Object> callParams = new LinkedHashMap<>(assembled.params());

    // tools schema：{type:function, function:{name, description, parameters}}
    if (!assembled.tools().isEmpty()) {
      List<Map<String, Object>> tools =
          assembled.tools().stream()
              .<Map<String, Object>>map(
                  spec -> {
                    var function = new LinkedHashMap<String, Object>();
                    function.put("name", spec.name());
                    function.put("description", spec.description());
                    function.put("parameters", spec.inputSchema());
                    var tool = new LinkedHashMap<String, Object>();
                    tool.put("type", "function");
                    tool.put("function", function);
                    return tool;
                  })
              .toList();
      callParams.put("tools", tools);
      logger.info("[Pipeline/Serialize] Attached {} tools to LLM call", tools.size());
    }

    return new Payload(
        assembled.modelId(), assembled.systemPrompt(), assembled.userPrompt(), callParams);
  }

  // ========================================================================
  // ④ Transport — 网络/执行
  // ========================================================================

  private Call transport(Payload payload) {
    ModelProvider provider = ModelProvider.getInstance();
    return payload.systemPrompt() != null
        ? provider.dispatch(
            payload.modelId(), payload.systemPrompt(), payload.userPrompt(), payload.callParams())
        : provider.dispatch(payload.modelId(), payload.userPrompt(), payload.callParams());
  }

  // ========================================================================
  // ⑤ Decode — 响应解析（vendor 原词在此层，不进内核）
  // ========================================================================

  /** 解码结果：内容/推理/工具调用/厂商终止原词。 */
  private record Decoded(
      String content,
      String reasoning,
      List<ToolDecision> toolCalls,
      String finishReason,
      String transportDetail) {}

  private Decoded decode(Call call) {
    if (call.status() != CallStatus.FINISHED || call.result() == null) {
      return new Decoded("", "", List.of(), null, call.status().toString());
    }

    Call.Response response = call.result();
    String content = response.content() != null ? response.content() : "";
    String reasoning = extractReasoningFromRaw(response);
    String finishReason = extractFinishReasonFromRaw(response);

    List<ToolDecision> toolCalls = new ArrayList<>();
    if (response.toolCalls() != null) {
      for (Call.ToolCall tc : response.toolCalls()) {
        toolCalls.add(new ToolDecision(tc.name(), tc.arguments()));
      }
    }
    return new Decoded(content, reasoning, toolCalls, finishReason, null);
  }

  // ========================================================================
  // ⑥ Observe→Record — 协议词 → 内核 Status/Decision 翻译
  // ========================================================================

  private ModelObservation observe(Decoded decoded) {
    if (decoded.transportDetail() != null) {
      return new ModelObservation("", "", List.of(), ModelStatus.FAILED, decoded.transportDetail());
    }

    String fr = decoded.finishReason();
    ModelStatus status;
    String detail = null;
    if ("tool_calls".equals(fr)) {
      status = ModelStatus.TOOL_CALLS;
    } else if (fr == null || fr.isBlank() || "stop".equals(fr)) {
      status = ModelStatus.CONTENT;
    } else {
      // length / content_filter 等非自然终止
      status = ModelStatus.TRUNCATED;
      detail = fr;
    }
    return new ModelObservation(
        decoded.content(), decoded.reasoning(), decoded.toolCalls(), status, detail);
  }

  // ========================================================================
  // 解码辅助（自 legacy AgentExecutor 迁移，保持原解析语义）
  // ========================================================================

  /** 从 Call.Response 的 raw metadata 中提取 reasoning_content（自 legacy 迁移；补标准转义解码）。 */
  private static String extractReasoningFromRaw(Call.Response response) {
    if (response == null || response.metadata() == null) return "";
    Object raw = response.metadata().get("raw");
    if (raw == null) return "";
    String rawStr = raw.toString();
    int idx = rawStr.indexOf("\"reasoning_content\":\"");
    if (idx < 0) return "";
    idx += 21;
    StringBuilder sb = new StringBuilder();
    while (idx < rawStr.length()) {
      char c = rawStr.charAt(idx);
      if (c == '\\' && idx + 1 < rawStr.length()) {
        char next = rawStr.charAt(idx + 1);
        switch (next) {
          case 'n' -> sb.append('\n');
          case 't' -> sb.append('\t');
          case 'r' -> sb.append('\r');
          default -> sb.append(next);
        }
        idx += 2;
      } else if (c == '"') {
        break;
      } else {
        sb.append(c);
        idx++;
      }
    }
    return sb.toString();
  }

  /** 从 Call.Response 的 raw metadata 中提取 finish_reason。 */
  private static String extractFinishReasonFromRaw(Call.Response response) {
    if (response == null || response.metadata() == null) return "stop";
    Object raw = response.metadata().get("raw");
    if (raw == null) return "stop";
    String rawStr = raw.toString();
    // Match "finish_reason":"value" from choices[0]
    int idx = rawStr.indexOf("\"finish_reason\":\"");
    if (idx < 0) return "stop";
    idx += 17;
    StringBuilder sb = new StringBuilder();
    while (idx < rawStr.length()) {
      char c = rawStr.charAt(idx);
      if (c == '"') break;
      sb.append(c);
      idx++;
    }
    return sb.toString();
  }
}
