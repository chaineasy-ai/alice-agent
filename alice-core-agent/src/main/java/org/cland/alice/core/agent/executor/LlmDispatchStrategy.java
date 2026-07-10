package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.AgentFacade;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.Observation;
import org.cland.alice.core.agent.lifecycle.StepWithContext;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.agent.wal.WalSession;
import org.cland.alice.model.Call;
import org.cland.alice.model.CallStatus;
import org.cland.alice.model.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 推理分派策略。
 *
 * <p>处理 {@link Action.Type#LLM_INFERENCE} 类型的动作：
 *
 * <ol>
 *   <li>调用 ModelProvider 进行模型推理
 *   <li>提取 reasoning / tool_calls / finish_reason
 *   <li>通过 {@link AgentEventBus} 广播事件
 *   <li>记录 WAL
 * </ol>
 *
 * <p>遵循开闭原则（OCP）：通过 {@link MicroReActEngine#registerStrategy} 注入。
 */
public class LlmDispatchStrategy implements DispatchStrategy {

  private static final Logger logger = LoggerFactory.getLogger(LlmDispatchStrategy.class);

  private final AgentFacade agent;
  private final Vertx vertx;
  private final WalSession wal;
  private final AgentEventBus eventBus;

  public LlmDispatchStrategy(
      AgentFacade agent, Vertx vertx, WalSession wal, AgentEventBus eventBus) {
    this.agent = Objects.requireNonNull(agent, "agent must not be null");
    this.vertx = Objects.requireNonNull(vertx, "vertx must not be null");
    this.wal = wal;
    this.eventBus = eventBus != null ? eventBus : new AgentEventBus();
  }

  @Override
  public boolean supports(Action action) {
    return action.type() == Action.Type.LLM_INFERENCE;
  }

  @Override
  public Future<StepWithContext> execute(AgentContext ctx, Action action) {
    Promise<StepWithContext> promise = Promise.promise();

    String modelId = action.target();
    String prompt = action.parameters().getOrDefault("prompt", "").toString();

    vertx
        .<StepResult>executeBlocking(
            () -> {
              try {
                ModelProvider provider = ModelProvider.getInstance();
                logger.info(
                    "[LlmDispatch] Calling model={} promptLength={}", modelId, prompt.length());

                // 构建调用参数
                Map<String, Object> callParams = new java.util.LinkedHashMap<>();
                if (action.parameters() != null) {
                  for (var entry : action.parameters().entrySet()) {
                    String k = entry.getKey();
                    if ("enable_thinking".equals(k) || "reasoning_effort".equals(k)) {
                      callParams.put(k, entry.getValue());
                    }
                  }
                }

                // 附加 tools schema
                if (agent.toolRegistry() != null) {
                  try {
                    var allTools = agent.toolRegistry().allTools();
                    if (!allTools.isEmpty()) {
                      var tools =
                          allTools.stream()
                              .<Map<String, Object>>map(
                                  meta -> {
                                    var function = new java.util.LinkedHashMap<String, Object>();
                                    function.put("name", meta.name());
                                    function.put("description", meta.description());
                                    function.put("parameters", meta.inputSchema());
                                    var tool = new java.util.LinkedHashMap<String, Object>();
                                    tool.put("type", "function");
                                    tool.put("function", function);
                                    return tool;
                                  })
                              .collect(java.util.stream.Collectors.toList());
                      callParams.put("tools", tools);
                    }
                  } catch (Exception e) {
                    logger.warn("[LlmDispatch] Failed to generate tools schema", e);
                  }
                }

                String microSystemPrompt =
                    ctx.containsKey("__micro_system_prompt")
                        ? ctx.get("__micro_system_prompt").toString()
                        : null;

                Call call =
                    microSystemPrompt != null
                        ? provider.dispatch(modelId, microSystemPrompt, prompt, callParams)
                        : provider.dispatch(modelId, prompt, callParams);

                if (call.status() == CallStatus.FINISHED && call.result() != null) {
                  return processResponse(ctx, call, modelId);
                } else {
                  if (wal != null) {
                    wal.finalAnswer(ctx.sessionId(), "[LLM Error: " + call.status() + "]");
                  }
                  return new StepResult.Failure("LLM call failed: " + call.status());
                }
              } catch (Exception e) {
                logger.error("[LlmDispatch] error", e);
                return new StepResult.Failure("LLM call error: " + e.getMessage());
              }
            })
        .onComplete(
            ar -> {
              if (ar.succeeded()) {
                promise.complete(new StepWithContext(ctx, ar.result()));
              } else {
                promise.fail(ar.cause());
              }
            });

    return promise.future();
  }

  private StepResult processResponse(AgentContext ctx, Call call, String modelId) {
    Call.Response response = call.result();
    String content = response.content() != null ? response.content() : "";
    java.util.List<Call.ToolCall> toolCalls = response.toolCalls();

    logger.info(
        "[LlmDispatch] Response model={} length={} toolCalls={}",
        modelId,
        content.length(),
        toolCalls.size());

    if (content != null && !content.isBlank()) {
      ctx.put("result", content);
    }
    ctx.put("__llm_response", content != null ? content : "");
    ctx.put("__llm_reasoning", extractReasoningFromRaw(response));

    Object reasoning = ctx.get("__llm_reasoning");
    eventBus.fireOnThought(reasoning != null ? reasoning.toString() : "");

    String finishReason = extractFinishReasonFromRaw(response);
    ctx.put("__finish_reason", finishReason);
    ctx.put("__turn_end", "stop".equals(finishReason));
    ctx.put(
        "__true_start",
        "stop".equals(finishReason) || finishReason == null || finishReason.isBlank());
    ctx.remove("__tool_call_index");

    if (toolCalls != null && !toolCalls.isEmpty()) {
      ctx.put("__tool_calls", toolCalls);
    }

    // WAL
    if (wal != null) {
      String walContent = content;
      boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
      if ((walContent == null || walContent.isEmpty()) && hasToolCalls) {
        String extractedReasoning = extractReasoningFromRaw(response);
        if (!extractedReasoning.isEmpty()) {
          walContent = "<thought>" + extractedReasoning + "</thought>";
        }
      }
      if (walContent != null && !walContent.isEmpty()) {
        if (hasToolCalls) {
          wal.think(ctx.sessionId(), walContent);
        } else {
          wal.finalAnswer(ctx.sessionId(), walContent);
        }
      }
    }

    return new StepResult.Continue(null, Observation.success(content));
  }

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
        sb.append(rawStr.charAt(idx + 1));
        idx += 2;
      } else if (c == '"') break;
      else {
        sb.append(c);
        idx++;
      }
    }
    return sb.toString();
  }

  private static String extractFinishReasonFromRaw(Call.Response response) {
    if (response == null || response.metadata() == null) return "stop";
    Object raw = response.metadata().get("raw");
    if (raw == null) return "stop";
    String rawStr = raw.toString();
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
