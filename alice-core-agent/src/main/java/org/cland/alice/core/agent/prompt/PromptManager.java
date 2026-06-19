package org.cland.alice.core.agent.prompt;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prompt Manager — 统一管理 Agent 三层层级的 prompt 构建。
 *
 * <p>所有 prompt 模板均以 FreeMarker {@code .ftl} 文件存储于 classpath: {@code
 * /org/cland/alice/core/agent/prompt/}。
 *
 * <pre>
 *   1. Planner Prompt  (planner.ftl)     — 由 PlannerService/Strategy 构建
 *   2. Core Loop Prompt (core_loop.ftl)  — PPAO 宏观循环
 *   3. Micro Loop Prompt (micro_loop.ftl) — Micro-ReAct 微观循环
 *   4. Micro Loop Error (micro_loop_error.ftl) — 工具失败时
 * </pre>
 *
 * <p>示例用法：
 *
 * <pre>{@code
 * String prompt = PromptManager.buildCoreLoopPrompt(userTask, lastObs, lastFb);
 * }</pre>
 */
public final class PromptManager {

  private static final Logger log = LoggerFactory.getLogger(PromptManager.class);

  private static final Configuration FREEMARKER;
  private static final Template CORE_LOOP;
  private static final Template MICRO_LOOP;
  private static final Template MICRO_LOOP_ERROR;
  private static final Template PLANNER;

  static {
    FREEMARKER = new Configuration(Configuration.VERSION_2_3_34);
    FREEMARKER.setClassLoaderForTemplateLoading(
        PromptManager.class.getClassLoader(), "org/cland/alice/core/agent/prompt");
    FREEMARKER.setDefaultEncoding("UTF-8");
    try {
      CORE_LOOP = FREEMARKER.getTemplate("core_loop.ftl");
      MICRO_LOOP = FREEMARKER.getTemplate("micro_loop.ftl");
      MICRO_LOOP_ERROR = FREEMARKER.getTemplate("micro_loop_error.ftl");
      PLANNER = FREEMARKER.getTemplate("planner.ftl");
    } catch (IOException e) {
      log.error("[PromptManager] Failed to load FreeMarker templates", e);
      throw new RuntimeException("Failed to load prompt templates", e);
    }
  }

  private PromptManager() {}

  // ========================================================================
  // Core Loop Prompt (PPAO 宏观循环)
  // ========================================================================

  /**
   * 构建 PPAO 宏观循环的完整 prompt。
   *
   * @param rawPrompt 用户原始需求
   * @param lastObservation 上一轮执行结果（可为空）
   * @param lastFeedback 上一轮修正反馈（可为空）
   * @return 完整 prompt 字符串
   */
  public static String buildCoreLoopPrompt(
      String rawPrompt, String lastObservation, String lastFeedback) {
    Map<String, Object> data = new HashMap<>();
    data.put("userTask", rawPrompt != null ? rawPrompt : "");
    if (lastObservation != null && !lastObservation.isBlank()) {
      data.put("lastObservation", lastObservation);
    }
    if (lastFeedback != null && !lastFeedback.isBlank()) {
      data.put("lastFeedback", lastFeedback);
    }
    return render(CORE_LOOP, data);
  }

  // ========================================================================
  // Micro Loop Prompt (Micro-ReAct 微观循环)
  // ========================================================================

  /**
   * 构建 Micro-ReAct 微观循环的 prompt。
   *
   * @param toolResult 工具执行结果（文件内容、命令输出等）
   * @param userTask 原始用户任务（当前未使用，保留接口一致性）
   * @return 完整 prompt
   */
  public static String buildMicroLoopPrompt(String toolResult, String userTask) {
    Map<String, Object> data = new HashMap<>();
    data.put("toolResult", toolResult != null && !toolResult.isBlank() ? toolResult : "<empty>");
    if (userTask != null && !userTask.isBlank()) {
      data.put("userTask", userTask);
    }
    return render(MICRO_LOOP, data);
  }

  /**
   * 构建工具执行失败后的 Micro-ReAct 循环 prompt。
   *
   * @param toolName 失败的工具名
   * @param errorMessage 错误消息
   * @param userTask 原始用户任务（当前未使用，保留接口一致性）
   * @return 完整 prompt
   */
  public static String buildMicroLoopErrorPrompt(
      String toolName, String errorMessage, String userTask) {
    Map<String, Object> data = new HashMap<>();
    data.put("toolName", toolName != null ? toolName : "unknown");
    data.put("errorMessage", errorMessage != null ? errorMessage : "unknown error");
    return render(MICRO_LOOP_ERROR, data);
  }

  // ========================================================================
  // Planner Prompt (由 PlannerService/Strategy 注入)
  // ========================================================================

  /**
   * 构造 Planner 用的 prompt。
   *
   * @param userTask 用户原始需求
   * @param context 上下文快照
   * @return 规划 prompt
   */
  public static String buildPlannerPrompt(String userTask, Map<String, Object> context) {
    Map<String, Object> data = new HashMap<>();
    data.put("userTask", userTask != null ? userTask : "");

    if (context.containsKey("lastObservation")) {
      data.put("lastObservation", context.get("lastObservation").toString());
    }
    if (context.containsKey("lastActionResult")) {
      data.put("lastActionResult", context.get("lastActionResult").toString());
    }
    if (context.containsKey("error")) {
      data.put("error", context.get("error").toString());
    }

    return render(PLANNER, data);
  }

  // ========================================================================
  // 内部渲染
  // ========================================================================

  private static String render(Template template, Map<String, Object> data) {
    try (StringWriter out = new StringWriter()) {
      template.process(data, out);
      return out.toString();
    } catch (TemplateException | IOException e) {
      log.error("[PromptManager] Failed to render template: {}", template.getName(), e);
      throw new RuntimeException("Failed to render prompt template: " + template.getName(), e);
    }
  }
}
