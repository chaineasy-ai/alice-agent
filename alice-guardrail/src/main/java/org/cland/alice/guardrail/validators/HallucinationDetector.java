package org.cland.alice.guardrail.validators;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.guardrail.AuditResult;
import org.cland.alice.guardrail.CorrectionSuggestion;
import org.cland.alice.guardrail.PostValidator;

/**
 * 幻觉检测验证器 (Post-Validator)。
 *
 * <p>对应设计文档中 "幻觉检测" 的具体实现。 在工具执行完成后，对返回的 Observation 进行一致性评估。
 *
 * <p><b>检查规则（#522 结构性判定）：</b>
 *
 * <ul>
 *   <li><b>状态：</b>观测 status=FAILURE ⇒ 直接 INVALID
 *   <li><b>工具类型门：</b>只读/检视类工具（read_file/grep/list_dir/search_file…）的 rawData 是“数据”
 *       （源码/目录/搜索结果），**不做**空结果/错误词扫描，避免裸子串误报
 *   <li><b>结构化模式：</b>执行类观测按**错误行**（行首 Error/Exception/Traceback/Failed to…、 或真实错误短语 timed out /
 *       connection refused / command not found…）与**短语式空结果** （no results found / no data / empty
 *       result…）判定；不再用裸 `timeout`/`null`/`not found` 子串
 *   <li><b>类型一致性：</b>根据 Plan 的步骤类型，检查返回数据格式的合理性
 * </ul>
 *
 * <p><b>设计说明：</b> 当前实现基于确定性规则。对于深度语义级别的幻觉检测， 建议使用轻量级 LLM（如 Qwen-7B）作为 "独立评审员" 进行二次确认， 实现 "模型验证模型"
 * 的架构（设计文档第5.1节）。
 */
public final class HallucinationDetector implements PostValidator {

  /** 短语式空结果模式（#522：完整短语，非裸词）。 */
  private static final Pattern EMPTY_RESULT_PATTERN =
      Pattern.compile(
          "(?im)\\b(?:no results? found|no data(?: available)?|empty result|no matches found|nothing found)\\b");

  /**
   * 结构化错误模式（#522）：只认**错误行**或**真实错误短语**，不认裸子串。
   *
   * <p>行首错误：Error/Exception/Traceback/Fatal/Panic/Failed to/Unable to/…Error 类； 错误短语：timed out /
   * connection refused / command not found / no such file… 等。
   */
  private static final Pattern ERROR_LINE_PATTERN =
      Pattern.compile(
          "(?im)"
              + "^\\s*(?:error|exception|traceback|fatal|panic|failed to|unable to|assertionerror"
              + "|timeouterror|permissionerror|filenotfounderror|connectionerror)\\b"
              + "|\\b(?:timed out|timeout after|deadline exceeded|connection refused|permission denied"
              + "|access denied|command not found|no such file or directory|segmentation fault"
              + "|out of memory|traceback \\(most recent call last\\))\\b");

  /**
   * 只读/检视类工具（#522）：其 rawData 是数据本身，不得按错误/空结果词判据。
   *
   * <p>这些工具的“疑似错误”应由 Agent 从内容中自行判断，而非 Post-Validator 判为幻觉。
   */
  private static final Set<String> INSPECTION_TOOLS =
      Set.of(
          "read_file",
          "grep",
          "list_dir",
          "search_file",
          "read",
          "cat",
          "head",
          "tail",
          "ls",
          "find");

  @Override
  public AuditResult check(Map<String, Object> observationMap, Plan originalPlan) {
    // 1. 提取观测状态
    ObsStatus status = extractStatus(observationMap);
    if (status == ObsStatus.FAILURE) {
      String summary = extractString(observationMap, "summary");
      return AuditResult.invalid(
          "Observation has FAILURE status: " + summary,
          CorrectionSuggestion.replan("Retry or choose an alternative approach"));
    }

    // 2. 检查 rawData 是否存在空结果或错误模式（#522：只读/检视工具的 rawData 是数据，跳过）
    String rawData = extractString(observationMap, "rawData");
    if (rawData != null
        && !rawData.isBlank()
        && !isInspectionTool(extractToolName(observationMap))) {
      // 空结果检测
      Optional<String> emptyResult = detectPattern(rawData, EMPTY_RESULT_PATTERN);
      if (emptyResult.isPresent()) {
        return AuditResult.invalid(
            "Hallucination detected: " + emptyResult.get(),
            CorrectionSuggestion.modifyParameters(
                "The tool returned an empty/non-existent result pattern. "
                    + "Consider refining the query or checking data source availability.",
                observationMap.get("metadata")));
      }

      // 错误模式检测（结构化：错误行/真实错误短语）
      Optional<String> errorDetected = detectPattern(rawData, ERROR_LINE_PATTERN);
      if (errorDetected.isPresent()) {
        return AuditResult.invalid(
            "Error pattern detected in observation: " + errorDetected.get(),
            CorrectionSuggestion.replan(
                "Tool execution encountered an error. Consider retrying with different parameters."));
      }
    }

    // 3. 类型一致性校验
    AuditResult consistencyResult = checkTypeConsistency(rawData, originalPlan);
    if (!consistencyResult.isPassed()) {
      return consistencyResult;
    }

    return AuditResult.allow();
  }

  // ========================================================================
  // 内部方法
  // ========================================================================

  /** 从观测 Map 中提取状态枚举。 */
  @SuppressWarnings("unchecked")
  private ObsStatus extractStatus(Map<String, Object> map) {
    if (map == null) return ObsStatus.FAILURE;
    Object statusObj = map.get("status");
    if (statusObj instanceof ObsStatus obs) {
      return obs;
    }
    if (statusObj instanceof String s) {
      try {
        return ObsStatus.valueOf(s.toUpperCase());
      } catch (IllegalArgumentException e) {
        return ObsStatus.FAILURE;
      }
    }
    return ObsStatus.SUCCESS;
  }

  /** 从观测 Map 中提取字符串字段。 */
  private String extractString(Map<String, Object> map, String key) {
    if (map == null) return null;
    Object val = map.get(key);
    return val instanceof String s ? s : null;
  }

  /** 在数据中检测是否命中给定**结构化**模式（#522）。 */
  private Optional<String> detectPattern(String data, Pattern pattern) {
    Matcher matcher = pattern.matcher(data);
    if (matcher.find()) {
      return Optional.of("Pattern detected: '" + matcher.group().trim() + "'");
    }
    return Optional.empty();
  }

  /** 从观测 metadata 中取工具名（可能为 null）。 */
  private String extractToolName(Map<String, Object> map) {
    if (map == null) return null;
    Object meta = map.get("metadata");
    if (meta instanceof Map<?, ?> m) {
      Object tool = m.get("toolName");
      return tool != null ? String.valueOf(tool) : null;
    }
    return null;
  }

  /** 是否只读/检视类工具（#522：其 rawData 是数据，不做错误/空结果词判据）。 */
  private boolean isInspectionTool(String toolName) {
    return toolName != null && INSPECTION_TOOLS.contains(toolName.toLowerCase());
  }

  /** 类型一致性校验。 根据 Plan 中步骤的类型，粗略检查返回格式的合理性。 */
  private AuditResult checkTypeConsistency(String rawData, Plan plan) {
    if (plan.steps() == null || plan.steps().isEmpty()) {
      return AuditResult.allow();
    }

    Plan.Step lastStep = plan.steps().get(plan.steps().size() - 1);

    switch (lastStep.actionType()) {
      case "TOOL_CALL":
        if (rawData == null || rawData.isBlank()) {
          return AuditResult.invalid(
              "Type consistency: TOOL_CALL returned empty data, expected structured output",
              CorrectionSuggestion.replan("Tool returned empty result. Check tool availability."));
        }
        break;

      case "LLM_INFERENCE":
        if (rawData == null || rawData.isBlank()) {
          return AuditResult.invalid(
              "Type consistency: LLM_INFERENCE returned empty response",
              CorrectionSuggestion.replan("Model returned empty response. Retry inference."));
        }
        break;

      default:
        break;
    }

    return AuditResult.allow();
  }
}
