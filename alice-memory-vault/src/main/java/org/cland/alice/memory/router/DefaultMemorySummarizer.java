package org.cland.alice.memory.router;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.cland.alice.memory.core.Step;
import org.cland.alice.memory.core.Summary;

/**
 * 默认记忆提炼器实现。
 *
 * <p>从原始会话 Trace 中提炼：
 *
 * <ul>
 *   <li><b>Facts</b>：从成功的步骤中提取事实性陈述（引入重要度过滤与去重）
 *   <li><b>Success Patterns</b>：从连续成功的步骤序列中提取可复用的模式
 * </ul>
 */
public final class DefaultMemorySummarizer implements MemorySummarizer {

  /** 连续成功步骤的最小数量，才能构成一个 Success Pattern */
  private static final int MIN_SUCCESS_RUN = 3;

  /** 纳入事实提取的最小步骤重要度阈值 */
  private static final double FACT_IMPORTANCE_THRESHOLD = 0.3;

  /** 事实描述中 Output 预览的最大截断长度 */
  private static final int OUTPUT_PREVIEW_LENGTH = 100;

  /** Session ID 链路分隔符 */
  private static final String SESSION_SEPARATOR = "::";

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

    String sessionId = resolveSessionId(trace.get(0).stepId());
    List<String> facts = extractFacts(trace);
    List<String> patterns = extractSuccessPatterns(trace);

    return Summary.builder()
        .sessionId(sessionId)
        .facts(facts)
        .successPatterns(patterns)
        .stepCount(trace.size())
        .createdAt(System.currentTimeMillis())
        .build();
  }

  /** 解析会话 ID。从 stepId (如 "session123::step456") 中截取前缀 */
  private String resolveSessionId(String stepId) {
    if (stepId == null || stepId.isBlank()) {
      return "session-" + System.currentTimeMillis();
    }
    int index = stepId.indexOf(SESSION_SEPARATOR);
    // 确保分隔符存在且不在首位，否则直接降级返回整个 stepId
    return index > 0 ? stepId.substring(0, index) : stepId;
  }

  /** 从 Trace 中提取事实性陈述 */
  private List<String> extractFacts(List<Step> trace) {
    List<String> facts = new ArrayList<>();
    Set<String> seen = new HashSet<>();

    for (Step step : trace) {
      if (step == null || !step.success() || step.importance() < FACT_IMPORTANCE_THRESHOLD) {
        continue;
      }

      String action = Objects.requireNonNullElse(step.action(), "UNKNOWN_ACTION");
      String input = Objects.requireNonNullElse(step.input(), "UNKNOWN_INPUT");

      // 业务唯一性去重键
      String factKey = action + ":" + input;
      if (!seen.add(factKey)) {
        continue;
      }

      String output = step.output();
      String outputPreview =
          (output != null)
              ? output.substring(0, Math.min(OUTPUT_PREVIEW_LENGTH, output.length()))
              : "";

      facts.add("使用 %s 处理 '%s' 得到: %s".formatted(action, input, outputPreview));
    }

    return facts;
  }

  /** 从 Trace 中提取成功模式 */
  private List<String> extractSuccessPatterns(List<Step> trace) {
    List<String> patterns = new ArrayList<>();
    List<Step> currentRun = new ArrayList<>();

    for (Step step : trace) {
      if (step != null && step.success()) {
        currentRun.add(step);
      } else {
        // 遭遇失败节点或坏数据，立即尝试结算并斩断链路
        结算当前序列(currentRun, patterns);
      }
    }
    // 循环结束，结算末尾残留的成功序列
    结算当前序列(currentRun, patterns);

    return patterns;
  }

  /** 检查并结算满足长度要求的模式 */
  private void 结算当前序列(List<Step> currentRun, List<String> patterns) {
    if (currentRun.size() >= MIN_SUCCESS_RUN) {
      patterns.add(buildPattern(currentRun));
    }
    currentRun.clear(); // 强制清空
  }

  /** 将一段连续的步骤序列构建为模式文本 */
  private String buildPattern(List<Step> run) {
    StringBuilder sb = new StringBuilder("成功模式: ");
    for (int i = 0; i < run.size(); i++) {
      if (i > 0) {
        sb.append(" → ");
      }
      sb.append(Objects.requireNonNullElse(run.get(i).action(), "UNKNOWN"));
    }
    return sb.toString();
  }
}
