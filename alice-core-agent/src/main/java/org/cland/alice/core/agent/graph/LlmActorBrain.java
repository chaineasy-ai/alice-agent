package org.cland.alice.core.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.agent.kernel.InferRequest;
import org.cland.alice.core.agent.kernel.InferRequest.ToolSpec;
import org.cland.alice.core.agent.kernel.Inferencer;
import org.cland.alice.core.agent.kernel.ModelObservation;
import org.cland.alice.core.agent.kernel.graph.GoalRef;
import org.cland.alice.core.agent.kernel.graph.Ledger;
import org.cland.alice.core.agent.kernel.graph.StandardSkeleton;
import org.cland.alice.core.agent.kernel.graph.ToolCallReq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TAO Thought 决策源的生产适配（经 {@link Inferencer} 语义契约调用 LLM）。
 *
 * <p>把模型观测翻译为战术决策：
 *
 * <ul>
 *   <li>{@code TOOL_CALLS} → act（一次返回一个效果；同批多个 tool_calls 排队逐次消费）
 *   <li>{@code CONTENT} → finish-goal（goal 级判据提交，不终结会话 —— P1 语义由骨架保证）
 *   <li>{@code FAILED/TRUNCATED} → 抛异常（由会话内核收口为 FAILED；本 brain 不含业务兜底）
 * </ul>
 *
 * 用户文本装配（② Assemble 的最简形态）：任务 + 当前 goal（P7 目标上下文）+ 最近效果观察。
 */
public final class LlmActorBrain implements StandardSkeleton.ActorBrain {

  private static final Logger logger = LoggerFactory.getLogger(LlmActorBrain.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Inferencer inferencer;
  private final String modelId;
  private final String systemPrompt;
  private final List<ToolSpec> tools;

  /** 同批待消费的效果调用队列（模型一次返回多个 tool_calls 时逐次执行）。 */
  private final Deque<ToolCallReq> pending = new ArrayDeque<>();

  /**
   * @param inferencer 文本 LLM pipeline（actor kind）
   * @param modelId 战术决策模型
   * @param systemPrompt 战术 system prompt（可为 null）
   * @param tools 工具 schema 素材（LLM 函数调用可见面）
   */
  public LlmActorBrain(
      Inferencer inferencer, String modelId, String systemPrompt, List<ToolSpec> tools) {
    this.inferencer = Objects.requireNonNull(inferencer, "inferencer");
    this.modelId = Objects.requireNonNull(modelId, "modelId");
    this.systemPrompt = systemPrompt;
    this.tools = tools != null ? List.copyOf(tools) : List.of();
  }

  @Override
  public StandardSkeleton.ActorStep think(GoalRef goal, Ledger ledger, String lastObservation) {
    if (!pending.isEmpty()) {
      return new StandardSkeleton.ActorStep.Act(pending.removeFirst());
    }

    StringBuilder user = new StringBuilder();
    if (goal != null) {
      user.append("<goal>").append(goal.summary()).append("</goal>\n");
    }
    if (lastObservation != null && !lastObservation.isEmpty()) {
      user.append("<tool_result>\n").append(lastObservation).append("\n</tool_result>\n");
    }

    ModelObservation obs =
        inferencer
            .infer(InferRequest.text(modelId, systemPrompt, user.toString()).withTools(tools))
            .toCompletionStage()
            .toCompletableFuture()
            .join();

    switch (obs.status()) {
      case TOOL_CALLS -> {
        List<ToolCallReq> calls = new ArrayList<>();
        for (ModelObservation.ToolDecision d : obs.toolCalls()) {
          calls.add(new ToolCallReq(d.name(), parseArguments(d.argumentsJson())));
        }
        if (calls.isEmpty()) {
          logger.warn("[LlmActorBrain] TOOL_CALLS status without decisions, finishing goal");
          return new StandardSkeleton.ActorStep.Done("");
        }
        pending.addAll(calls.subList(1, calls.size()));
        logger.info("[LlmActorBrain] act={} (+{} queued)", calls.get(0).name(), calls.size() - 1);
        return new StandardSkeleton.ActorStep.Act(calls.get(0));
      }
      case CONTENT -> {
        String content = obs.content() != null ? obs.content() : "";
        logger.debug(
            "[LlmActorBrain] model answered ({} chars), submitting finish-goal", content.length());
        return new StandardSkeleton.ActorStep.Done(content);
      }
      case FAILED, TRUNCATED ->
          throw new IllegalStateException(
              "actor inference failed: " + (obs.detail() != null ? obs.detail() : obs.status()));
      default -> throw new IllegalStateException("unhandled model status: " + obs.status());
    }
  }

  private static Map<String, Object> parseArguments(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return Map.of();
    }
    try {
      Map<?, ?> raw = MAPPER.readValue(argumentsJson, Map.class);
      Map<String, Object> out = new java.util.LinkedHashMap<>();
      raw.forEach(
          (k, v) -> {
            if (k != null && v != null) {
              out.put(k.toString(), v);
            }
          });
      return out;
    } catch (Exception e) {
      logger.warn(
          "[LlmActorBrain] failed to parse tool arguments, using empty params: {}", e.getMessage());
      return Map.of();
    }
  }
}
