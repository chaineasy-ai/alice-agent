package org.cland.alice.memory.router;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.cland.alice.memory.core.Step;
import org.cland.alice.memory.core.Summary;

/**
 * 默认记忆提炼器实现。
 *
 * <p>从原始会话 Trace 中提炼：
 *
 * <ul>
 *   <li><b>Facts</b>：从成功的步骤中提取事实性陈述
 *   <li><b>Success Patterns</b>：从连续成功的步骤序列中提取可复用的模式
 * </ul>
 *
 * 对应设计文档中 "The Consolidation Process" 的 Summarizer 实现。
 */
public final class DefaultMemorySummarizer implements MemorySummarizer {

  /** 连续成功步骤的最小数量，才能构成一个 Success Pattern */
  private static final int MIN_SUCCESS_RUN = 3;

  @Override
  public Summary summarize(List<Step> trace) {
    if (trace == null || trace.isEmpty()) {
      return Summary.builder()
          .sessionId("unknown")
          .facts(List.of())
          .successPatterns(List.of())
          .stepCount(0)
          .build();
    }

    String sessionId =
        trace.get(0).stepId() != null
            ? trace.get(0).stepId().contains("::")
                ? trace.get(0).stepId().substring(0, trace.get(0).stepId().indexOf("::"))
                : trace.get(0).stepId()
            : "session-" + System.currentTimeMillis();

    // 1. 提取 Facts：成功的、重要度高的步骤 → 事实
    List<String> facts = extractFacts(trace);

    // 2. 提取 Success Patterns：连续成功的步骤序列
    List<String> patterns = extractSuccessPatterns(trace);

    return Summary.builder()
        .sessionId(sessionId)
        .facts(facts)
        .successPatterns(patterns)
        .stepCount(trace.size())
        .createdAt(System.currentTimeMillis())
        .build();
  }

  /** 从 Trace 中提取事实性陈述。 事实 = 成功步骤中 action + input → output 的简洁描述。 */
  private List<String> extractFacts(List<Step> trace) {
    List<String> facts = new ArrayList<>();
    Set<String> seen = new HashSet<>();

    for (Step step : trace) {
      if (!step.success()) continue;
      if (step.importance() < 0.3) continue;

      // 去重
      String factKey = step.action() + ":" + step.input();
      if (seen.contains(factKey)) continue;
      seen.add(factKey);

      String outputPreview =
          step.output() != null
              ? step.output().substring(0, Math.min(100, step.output().length()))
              : "";
      String fact = "使用 %s 处理 '%s' 得到: %s".formatted(step.action(), step.input(), outputPreview);
      facts.add(fact);
    }

    return facts;
  }

  /** 从 Trace 中提取成功模式。 模式 = 连续 MIN_SUCCESS_RUN 个以上成功的步骤序列。 */
  private List<String> extractSuccessPatterns(List<Step> trace) {
    List<String> patterns = new ArrayList<>();

    int runStart = -1;
    for (int i = 0; i < trace.size(); i++) {
      Step step = trace.get(i);
      if (step.success()) {
        if (runStart == -1) runStart = i;
      } else {
        if (runStart >= 0 && (i - runStart) >= MIN_SUCCESS_RUN) {
          patterns.add(buildPattern(trace.subList(runStart, i)));
        }
        runStart = -1;
      }
    }
    // 检查末尾的连续成功
    if (runStart >= 0 && (trace.size() - runStart) >= MIN_SUCCESS_RUN) {
      patterns.add(buildPattern(trace.subList(runStart, trace.size())));
    }

    return patterns;
  }

  /** 将一段连续的步骤序列构建为模式文本。 */
  private String buildPattern(List<Step> run) {
    StringBuilder sb = new StringBuilder("成功模式: ");
    for (int i = 0; i < run.size(); i++) {
      if (i > 0) sb.append(" → ");
      sb.append(run.get(i).action());
    }
    return sb.toString();
  }
}
