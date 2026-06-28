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
    // Load templates directly via Class.getResource (JPMS-compatible)
    try {
      CORE_LOOP = loadTemplate("core_loop.ftl");
      MICRO_LOOP = loadTemplate("micro_loop.ftl");
      MICRO_LOOP_ERROR = loadTemplate("micro_loop_error.ftl");
      PLANNER = loadTemplate("planner.ftl");
    } catch (IOException e) {
      log.error("[PromptManager] Failed to load FreeMarker templates", e);
      throw new RuntimeException("Failed to load prompt templates", e);
    }
  }

  private static Template loadTemplate(String name) throws IOException {
    java.net.URL url = PromptManager.class.getResource(name);
    if (url == null) {
      throw new IOException("Template not found: " + name);
    }
    try {
      return new Template(
          name, new java.io.InputStreamReader(url.openStream(), "UTF-8"), FREEMARKER);
    } catch (IOException e) {
      throw new IOException("Failed to load template: " + name, e);
    }
  }

  private static String systemPromptCache; // lazily extracted from core_loop.ftl
  private static String microLoopSystemCache; // lazily rendered from micro_loop.ftl

  private PromptManager() {}

  // ========================================================================
  // System Prompt (从 core_loop.ftl 提取)
  // ========================================================================

  /**
   * 从 core_loop.ftl 模板中提取 {@code <system>...</system>} 块内容。
   *
   * <p>系统提示在模板中是静态的（不包含 FreeMarker 变量），因此只需提取一次并缓存。 返回的字符串可用作 WAL 中 {@code role: "system"} 消息的内容。
   *
   * @return 系统提示文本（不含 {@code <system>} 标签本身）
   */
  public static String buildSystemPrompt() {
    if (systemPromptCache != null) {
      return systemPromptCache;
    }

    // 将模板源码渲染一次（使用空数据），再从输出中提取 <system> 块
    String rendered;
    try (StringWriter out = new StringWriter()) {
      CORE_LOOP.process(Map.of("userTask", ""), out);
      rendered = out.toString();
    } catch (TemplateException | IOException e) {
      log.warn("[PromptManager] Failed to extract system prompt, falling back to default", e);
      systemPromptCache = "You are Alice, an AI coding assistant.";
      return systemPromptCache;
    }

    // 提取 <system>...</system> 块
    int sysStart = rendered.indexOf("<system>");
    int sysEnd = rendered.indexOf("</system>");
    if (sysStart >= 0 && sysEnd > sysStart) {
      systemPromptCache = rendered.substring(sysStart + 8, sysEnd).trim();
    } else {
      log.warn("[PromptManager] No <system> block found in core_loop.ftl");
      systemPromptCache = "You are Alice, an AI coding assistant.";
    }
    return systemPromptCache;
  }

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
  // Micro Loop System Prompt (静态 system role 内容)
  // ========================================================================

  /**
   * 构建 Micro-ReAct 微观循环的 system prompt（静态内容，无变量）。
   *
   * <p>返回 micro_loop.ftl 渲染结果，用作 {@code role: "system"} 消息。 模板中不含 FreeMarker 变量，渲染一次后缓存。
   *
   * @return 完整的 micro loop system prompt 文本
   */
  public static String buildMicroLoopSystemPrompt() {
    if (microLoopSystemCache != null) {
      return microLoopSystemCache;
    }
    try (StringWriter out = new StringWriter()) {
      MICRO_LOOP.process(Map.of(), out);
      microLoopSystemCache = out.toString().trim();
    } catch (TemplateException | IOException e) {
      log.warn("[PromptManager] Failed to render micro_loop.ftl, falling back", e);
      microLoopSystemCache = "<rules><rule>Use tools to complete the task.</rule></rules>";
    }
    return microLoopSystemCache;
  }

  // ========================================================================
  // Micro Loop User Content (user role 内容，含变量)
  // ========================================================================

  /**
   * 构建 Micro-ReAct 的 user role 内容（含已读文件列表、工具结果等变量）。
   *
   * @param toolResult 工具执行结果
   * @param userTask 原始用户任务
   * @param alreadyReadFiles 已读取过的文件路径列表
   * @return user role 内容字符串
   */
  public static String buildMicroUserContent(
      String toolResult, String userTask, java.util.Set<String> alreadyReadFiles) {
    StringBuilder sb = new StringBuilder();
    if (alreadyReadFiles != null && !alreadyReadFiles.isEmpty()) {
      sb.append("<read_files>\n");
      for (String f : alreadyReadFiles) {
        sb.append(f).append('\n');
      }
      sb.append("</read_files>\n\n");
    }
    if (userTask != null && !userTask.isBlank()) {
      sb.append("<user_task>\n").append(userTask).append("\n</user_task>\n\n");
    }
    sb.append("<tool_result>\n");
    sb.append(toolResult != null && !toolResult.isBlank() ? toolResult : "<empty>");
    sb.append("\n</tool_result>");
    return sb.toString();
  }

  /**
   * 构建工具执行失败后的 user role 内容。
   *
   * @param toolName 失败的工具名
   * @param errorMessage 错误消息
   * @param userTask 原始用户任务
   * @return user role 内容字符串
   */
  public static String buildMicroLoopErrorContent(
      String toolName, String errorMessage, String userTask) {
    StringBuilder sb = new StringBuilder();
    if (userTask != null && !userTask.isBlank()) {
      sb.append("<user_task>\n").append(userTask).append("\n</user_task>\n\n");
    }
    sb.append("<tool_error>\n");
    sb.append("  <tool>").append(toolName != null ? toolName : "unknown").append("</tool>\n");
    sb.append("  <message>")
        .append(errorMessage != null ? errorMessage : "unknown error")
        .append("</message>\n");
    sb.append("</tool_error>");
    return sb.toString();
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
