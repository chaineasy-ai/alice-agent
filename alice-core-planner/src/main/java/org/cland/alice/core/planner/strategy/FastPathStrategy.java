package org.cland.alice.core.planner.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.core.planner.Plan.Intent;
import org.cland.alice.core.planner.model.ModelSession;
import org.cland.alice.core.planner.model.PlannerModelSupplier;
import org.cland.alice.model.Call;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 快速路径策略 (System 1) — 通过轻量 LLM 调用决策业务意图链。
 *
 * <p>调用指令模型对用户输入做意图分类，输出 {@link Plan.Intent} 链（如 {@code "SEARCH CODE"}）。 planning prompt 由 Agent 层
 * {@code PromptManager.buildPlannerPrompt()} 渲染 {@code planner.ftl} 模板后，通过 context 的 {@code
 * "plannerPrompt"} 键传入。
 */
public final class FastPathStrategy implements DecisionStrategy {

  private static final Logger logger = LoggerFactory.getLogger(FastPathStrategy.class);

  private final PlannerModelSupplier modelSupplier;

  public FastPathStrategy(PlannerModelSupplier modelSupplier) {
    this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier must not be null");
  }

  @Override
  public Plan decide(Map<String, Object> context) {
    String prompt = (String) context.getOrDefault("prompt", "");
    String result = context.containsKey("result") ? context.get("result").toString() : null;

    logger.debug("[FastPath] deciding intent for prompt length={}", prompt.length());

    if (result != null && !result.isEmpty()) {
      return Plan.fastPath("Task completed", Intent.FINISH, "FINISH");
    }

    String plannerPrompt =
        context.containsKey("plannerPrompt") ? context.get("plannerPrompt").toString() : prompt;

    ClassifyResult classifyResult = classifyIntent(plannerPrompt);
    Intent primaryIntent = classifyResult.intent;

    ModelSession session = modelSupplier.getInstructionModel();
    String modelId = session != null ? session.modelId() : "gpt-4o-mini";

    Map<String, Object> llmParams = new java.util.LinkedHashMap<>();
    llmParams.put("prompt", prompt);
    copyIfPresent(context, llmParams, "lastObservation");
    copyIfPresent(context, llmParams, "lastActionResult");
    copyIfPresent(context, llmParams, "error");
    copyIfPresent(context, llmParams, "availableTools");
    if (session != null) {
      session
          .parameters()
          .forEach(
              (k, v) -> {
                if ("enable_thinking".equals(k) || "reasoning_effort".equals(k)) {
                  llmParams.put(k, v);
                }
              });
    }

    logger.info("[FastPath] primaryIntent={}, chain={}", primaryIntent, classifyResult.chain);

    Plan.Builder planBuilder =
        Plan.builder()
            .type(Plan.Type.FAST_PATH)
            .summary("Fast path: " + primaryIntent.name())
            .addStep(Plan.Step.of(primaryIntent, modelId, llmParams))
            .addStep(Plan.Step.of(Intent.FINISH, "FINISH"));

    Map<String, Object> meta = new java.util.LinkedHashMap<>();
    meta.put("path", "fast");
    meta.put("intent", primaryIntent.name());
    meta.put("intentChain", classifyResult.chain.stream().map(Intent::name).toList());
    meta.put("plannerRawResponse", classifyResult.rawResponse);

    return planBuilder.metadata(meta).build();
  }

  /**
   * 调用指令模型对用户输入做意图分类。
   *
   * <p>返回意图链（可能多个），例如 {@code "SEARCH CODE"} 或 {@code "ANSWER"}。
   */
  private ClassifyResult classifyIntent(String plannerPrompt) {
    try {
      ModelSession session = modelSupplier.getInstructionModel();
      String modelId = session != null ? session.modelId() : "gpt-4o-mini";

      Call call =
          Call.builder().payload(new Call.Payload(modelId, plannerPrompt, null, Map.of())).build();

      String raw = modelSupplier.request(call).content();
      if (raw != null && !raw.isBlank()) {
        String trimmed = raw.trim();
        String[] words = trimmed.toUpperCase().split("\\s+");
        List<Intent> chain = new ArrayList<>();
        for (String word : words) {
          String clean = word.replaceAll("[^A-Z_]", "");
          for (Intent intent : Intent.values()) {
            if (intent.name().equals(clean)) {
              chain.add(intent);
              break;
            }
          }
        }
        if (!chain.isEmpty()) {
          return new ClassifyResult(chain.get(0), trimmed, chain);
        }
        logger.warn(
            "[FastPath] Unrecognized intent '{}' from model, falling back to ANALYZE", trimmed);
      }
    } catch (Exception e) {
      logger.warn("[FastPath] Model call failed, falling back to ANALYZE", e);
    }
    return new ClassifyResult(Intent.ANALYZE, "", List.of(Intent.ANALYZE));
  }

  private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
    if (from.containsKey(key) && from.get(key) != null) {
      to.put(key, from.get(key));
    }
  }

  private static class ClassifyResult {
    final Intent intent;
    final String rawResponse;
    final List<Intent> chain;

    ClassifyResult(Intent intent, String rawResponse, List<Intent> chain) {
      this.intent = intent;
      this.rawResponse = rawResponse;
      this.chain = chain;
    }
  }
}
