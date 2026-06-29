package org.cland.alice.guardrail.validators;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.guardrail.AuditResult;
import org.cland.alice.guardrail.CorrectionSuggestion;
import org.cland.alice.guardrail.PreValidator;
import org.cland.alice.tool.gateway.ToolRegistry;

/**
 * 工具微循环检测验证器 (Pre-Validator) — 跨 ReAct 周期的工具调用循环检测。
 *
 * <p>与 {@link LogicSanityValidator} 不同，后者只检测<b>单个 Plan 内连续重复</b>步骤。 此验证器跨迭代跟踪历史工具调用，检测 Agent
 * 是否反复调用同一工具（相同或相似参数）， 陷入跨周期的微循环 (micro-loop)。
 *
 * <p><b>检测策略：</b>
 *
 * <ul>
 *   <li><b>精确重复</b> — 同一 (toolName + 参数指纹) 连续出现超过阈值（默认 3 次）
 *   <li><b>相同工具高频调用</b> — 同一工具在历史中被调用总次数超过上限（默认 10 次）
 *   <li><b>Plan 内局部微循环</b> — 当前 Plan 中同一工具被调用 ≥ 3 次
 * </ul>
 *
 * <p><b>使用方式：</b>
 *
 * <pre>{@code
 * ToolRegistry registry = ...;
 * var loopDetector = new ToolMicroLoopValidator(registry);
 * guardrail.registerPreValidator(loopDetector);
 *
 * // 在每次工具调用后记录
 * loopDetector.recordCall("web_search", Map.of("query", "java"));
 * }</pre>
 */
public final class ToolMicroLoopValidator implements PreValidator {

  /** 默认精确重复阈值（连续相同工具+参数指纹超过此次数即判定为循环） */
  private static final int DEFAULT_REPEAT_THRESHOLD = 3;

  /** 默认同一工具总调用上限 */
  private static final int DEFAULT_TOTAL_CALL_LIMIT = 10;

  /** 默认 Plan 内同一工具最大调用次数 */
  private static final int DEFAULT_PLAN_INNER_LIMIT = 3;

  private final ToolRegistry toolRegistry;
  private final int repeatThreshold;
  private final int totalCallLimit;
  private final int planInnerLimit;

  /** 历史调用记录。每个条目是 (toolName, parameterFingerprint) 的复合键。 使用线程安全的 ConcurrentHashMap 以支持并发访问。 */
  private final List<String> callHistory;

  public ToolMicroLoopValidator(ToolRegistry toolRegistry) {
    this(
        toolRegistry, DEFAULT_REPEAT_THRESHOLD, DEFAULT_TOTAL_CALL_LIMIT, DEFAULT_PLAN_INNER_LIMIT);
  }

  public ToolMicroLoopValidator(
      ToolRegistry toolRegistry, int repeatThreshold, int totalCallLimit, int planInnerLimit) {
    this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
    if (repeatThreshold < 1) throw new IllegalArgumentException("repeatThreshold must be >= 1");
    if (totalCallLimit < 1) throw new IllegalArgumentException("totalCallLimit must be >= 1");
    if (planInnerLimit < 1) throw new IllegalArgumentException("planInnerLimit must be >= 1");
    this.repeatThreshold = repeatThreshold;
    this.totalCallLimit = totalCallLimit;
    this.planInnerLimit = planInnerLimit;
    this.callHistory = Collections.synchronizedList(new ArrayList<>());
  }

  // ========================================================================
  // 历史记录 API
  // ========================================================================

  /**
   * 记录一次工具调用。每次工具执行完成后由调用方（如 AgentExecutor / ReAct）调用， 为下一次 {@link #check(Plan)} 提供历史依据。
   *
   * @param toolName 被调用的工具名称
   * @param params 调用参数（可为空），用于生成参数指纹
   */
  public void recordCall(String toolName, Map<String, Object> params) {
    Objects.requireNonNull(toolName, "toolName must not be null");
    String fingerprint = fingerprint(toolName, params);
    callHistory.add(fingerprint);
  }

  /**
   * 记录一次简化的工具调用（无参数）。
   *
   * @param toolName 被调用的工具名称
   */
  public void recordCall(String toolName) {
    recordCall(toolName, Map.of());
  }

  /** 重置历史记录。在 Agent 启动新会话或手动中断循环时调用。 */
  public void resetHistory() {
    callHistory.clear();
  }

  /** 获取当前历史调用次数（只读快照）。 */
  public int historySize() {
    return callHistory.size();
  }

  // ========================================================================
  // PreValidator 接口
  // ========================================================================

  @Override
  public AuditResult check(Plan plan) {
    if (plan.steps() == null || plan.steps().isEmpty()) {
      return AuditResult.allow();
    }

    // --- 检查 1：Plan 内局部微循环 ---
    // 统计当前 Plan 中每个工具被调用的次数
    java.util.Map<String, Integer> planToolCounts = new java.util.HashMap<>();
    for (Plan.Step step : plan.steps()) {
      if (!"TOOL_CALL".equals(step.actionType())) continue;
      String toolName = step.target();
      if (toolName == null || toolName.isBlank()) continue;
      planToolCounts.merge(toolName, 1, Integer::sum);
    }
    for (var entry : planToolCounts.entrySet()) {
      if (entry.getValue() >= planInnerLimit) {
        return AuditResult.reject(
            "Micro-loop detected within Plan: tool '"
                + entry.getKey()
                + "' is called "
                + entry.getValue()
                + " times in this plan (limit: "
                + planInnerLimit
                + ")",
            CorrectionSuggestion.replan(
                "Consolidate repeated calls to '"
                    + entry.getKey()
                    + "' into a single call. "
                    + "If you need multiple results, batch them together."));
      }
    }

    // --- 检查 2：跨周期精确重复 ---
    // 对 Plan 中每个 TOOL_CALL 步骤，检查如果执行后是否会形成精确重复
    if (!callHistory.isEmpty()) {
      for (Plan.Step step : plan.steps()) {
        if (!"TOOL_CALL".equals(step.actionType())) continue;

        String toolName = step.target();
        if (toolName == null || toolName.isBlank()) continue;

        // 检查工具是否注册（利用已有的 ToolRegistry 依赖）
        if (!toolRegistry.hasTool(toolName)) {
          // 不存在的工具由 ToolExistenceValidator 处理，此处跳过
          continue;
        }

        // 检查在历史记录末尾加上此调用后是否形成精确重复链
        String prospectiveFingerprint =
            fingerprint(toolName, step.parameters() != null ? step.parameters() : Map.of());

        // 从历史末尾向前扫描连续重复
        int consecutiveRepeats = 0;
        for (int i = callHistory.size() - 1; i >= 0; i--) {
          if (callHistory.get(i).equals(prospectiveFingerprint)) {
            consecutiveRepeats++;
          } else {
            break;
          }
        }
        // 加上本次即将执行的调用
        consecutiveRepeats++;

        if (consecutiveRepeats > repeatThreshold) {
          return AuditResult.reject(
              "Micro-loop (exact repeat) detected: tool '"
                  + toolName
                  + "' with same parameters has been called "
                  + consecutiveRepeats
                  + " times consecutively (threshold: "
                  + repeatThreshold
                  + ")",
              CorrectionSuggestion.replan(
                  "The agent is repeating the same tool call. Try a different approach "
                      + "or consolidate the repeated calls."));
        }
      }
    }

    // --- 检查 3：跨周期同一工具总调用次数 ---
    for (Plan.Step step : plan.steps()) {
      if (!"TOOL_CALL".equals(step.actionType())) continue;
      String toolName = step.target();
      if (toolName == null || toolName.isBlank()) continue;

      long totalCallsForTool =
          callHistory.stream().filter(f -> f.startsWith(toolName + "::")).count();
      // 加上本次
      totalCallsForTool++;

      if (totalCallsForTool > totalCallLimit) {
        return AuditResult.reject(
            "Micro-loop (excessive calls) detected: tool '"
                + toolName
                + "' has been called "
                + totalCallsForTool
                + " times total (limit: "
                + totalCallLimit
                + ")",
            CorrectionSuggestion.replan(
                "Too many calls to '"
                    + toolName
                    + "'. Consider:\n"
                    + "  1. Combining multiple queries into one batched call\n"
                    + "  2. Using a different tool\n"
                    + "  3. Terminating the current task"));
      }
    }

    return AuditResult.allow();
  }

  // ========================================================================
  // 内部方法
  // ========================================================================

  /**
   * 生成工具调用的参数指纹。
   *
   * <p>格式：{@code toolName::key1=val1|key2=val2|...}
   *
   * <p>参数按 key 排序以保证指纹稳定。 null 值被编码为 "<null>"。
   */
  static String fingerprint(String toolName, Map<String, Object> params) {
    if (params == null || params.isEmpty()) {
      return toolName + "::(no-params)";
    }
    var keys = new ArrayList<>(params.keySet());
    java.util.Collections.sort(keys);
    var sb = new StringBuilder(toolName).append("::");
    boolean first = true;
    for (String key : keys) {
      if (!first) sb.append('|');
      first = false;
      sb.append(key).append('=');
      Object val = params.get(key);
      if (val == null) {
        sb.append("<null>");
      } else {
        sb.append(val);
      }
    }
    return sb.toString();
  }
}
