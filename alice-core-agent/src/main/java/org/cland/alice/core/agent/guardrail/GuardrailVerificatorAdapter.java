package org.cland.alice.core.agent.guardrail;

import java.util.LinkedHashMap;
import java.util.Map;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.guardrail.AuditResult;
import org.cland.alice.guardrail.GuardrailService;
import org.cland.alice.guardrail.PostValidator;
import org.cland.alice.guardrail.Verificator;
import org.cland.alice.guardrail.validators.HallucinationDetector;
import org.cland.alice.guardrail.validators.LogicSanityValidator;
import org.cland.alice.guardrail.validators.PermissionSandboxValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Verificator} 适配器 — 桥接 AgentExecutor 与 {@link GuardrailService}。
 *
 * <p><b>问题：</b>
 *
 * <ul>
 *   <li>{@link org.cland.alice.core.agent.Agent Agent} 使用 {@link Verificator} 接口 ({@link
 *       #intercept(Map)} / {@link #audit(Object)}，返回 boolean）
 *   <li>{@link GuardrailService} 使用 {@link org.cland.alice.guardrail.PreValidator
 *       PreValidator}/{@link PostValidator} 链 ({@link GuardrailService#verifyPlan(Plan)} / {@link
 *       GuardrailService#verifyResult(Map, Plan)}，返回 {@link AuditResult} 状态机）
 * </ul>
 *
 * <p>本适配器将两者的签名桥接起来，并在构造时自动注册内置 Validator：
 *
 * <ul>
 *   <li>{@link LogicSanityValidator} — Pre: 逻辑闭环/死循环检测
 *   <li>{@link PermissionSandboxValidator} — Pre: 权限沙箱/系统路径黑名单
 *   <li>{@link HallucinationDetector} — Post: 幻觉检测/空结果/错误模式
 * </ul>
 *
 * <p>使用方式：
 *
 * <pre>{@code
 * Agent.createDefault(config)
 *     .withGuardrail(new GuardrailVerificatorAdapter());
 * }</pre>
 */
public final class GuardrailVerificatorAdapter implements Verificator {

  private static final Logger logger = LoggerFactory.getLogger(GuardrailVerificatorAdapter.class);

  private final GuardrailService guardrailService;

  /** 上一次 intercept 调用中生成的 Plan，供 audit 阶段引用。 */
  private Plan lastPlan;

  /** 是否记录详细的验证日志。 */
  private final boolean verbose;

  public GuardrailVerificatorAdapter() {
    this(true);
  }

  public GuardrailVerificatorAdapter(boolean verbose) {
    this.guardrailService = new GuardrailService();
    this.verbose = verbose;
    this.lastPlan = null;
    registerDefaultValidators();
  }

  // ========================================================================
  // 默认验证器注册
  // ========================================================================

  /** 注册预置的 PreValidator + PostValidator 到 GuardrailService。 */
  private void registerDefaultValidators() {
    // Pre-Validators
    guardrailService.registerPreValidator(new LogicSanityValidator());
    guardrailService.registerPreValidator(new PermissionSandboxValidator());
    if (verbose) {
      logger.info("[GuardrailAdapter] Registered 2 PreValidators: LogicSanity, PermissionSandbox");
    }

    // Post-Validators
    guardrailService.registerPostValidator(new HallucinationDetector());
    if (verbose) {
      logger.info("[GuardrailAdapter] Registered 1 PostValidator: HallucinationDetector");
    }
  }

  // ========================================================================
  // Verificator 接口实现
  // ========================================================================

  /**
   * Pre-Verify: 将 Action Map 转为 Plan 后委托给 {@link GuardrailService#verifyPlan(Plan)}。
   *
   * @param action Action 属性 Map（包含 type、target、actionId 等 key）
   * @return true 表示通过，false 表示被拦截
   */
  @Override
  public boolean intercept(Map<String, Object> action) {
    if (action == null || action.isEmpty()) {
      logger.warn("[GuardrailAdapter] intercept: null/empty action");
      return false;
    }

    // 1. 从 Map 构建 Plan
    String typeStr = safeString(action.get("type"));
    String targetStr = safeString(action.get("target"));
    String actionId = safeString(action.get("actionId"));

    if (typeStr == null || typeStr.isBlank()) {
      logger.warn("[GuardrailAdapter] intercept: missing 'type' in action map");
      return false;
    }

    // 纠正 Plan 动作类型（Action.Type → Plan.Step 兼容字符串）
    String planTarget = targetStr != null ? targetStr : "";
    Plan plan = Plan.fastPath("Guardrail pre-verify: " + typeStr, toIntent(typeStr), planTarget);

    // 保留 metadata 用于审计跟踪
    Plan enrichedPlan =
        Plan.builder()
            .type(plan.type())
            .summary(plan.summary())
            .steps(plan.steps())
            .metadata(Map.of("actionId", actionId != null ? actionId : "", "source", "intercept"))
            .build();

    // 2. 委托 GuardrailService
    AuditResult result = guardrailService.verifyPlan(enrichedPlan);

    // 3. 缓存 Plan 供 audit 使用
    this.lastPlan = enrichedPlan;

    // 4. 日志 & 返回
    if (verbose) {
      if (result.isPassed()) {
        logger.debug("[GuardrailAdapter] Pre-verify ALLOW: {} -> {}", typeStr, planTarget);
      } else if (result.needsManualConfirm()) {
        logger.warn("[GuardrailAdapter] Pre-verify MANUAL_CONFIRM: {}", result.reason());
      } else {
        logger.warn(
            "[GuardrailAdapter] Pre-verify REJECT: {} reason={}", result.status(), result.reason());
      }
    }

    return result.isPassed();
  }

  /**
   * Post-Verify: 将 StepResult 转为观测 Map 后委托给 {@link GuardrailService#verifyResult(Map, Plan)}。
   *
   * @param stepResult PPAO 循环中产生的 StepResult 对象
   * @return true 表示审计通过，false 表示需要 Revision
   */
  @Override
  public boolean audit(Object stepResult) {
    if (stepResult == null) {
      logger.warn("[GuardrailAdapter] audit: null stepResult");
      return false;
    }
    if (!(stepResult instanceof StepResult sr)) {
      logger.warn("[GuardrailAdapter] audit: unexpected type {}", stepResult.getClass().getName());
      return false;
    }

    // 1. StepResult → observationMap
    Map<String, Object> obsMap = convertStepResultToObsMap(sr);

    // 2. 委托 GuardrailService（如果没有缓存的 Plan，用空 Plan）
    Plan plan =
        lastPlan != null
            ? lastPlan
            : Plan.fastPath("Guardrail post-verify (no pre-plan)", Plan.Intent.ANALYZE, "");

    AuditResult result = guardrailService.verifyResult(obsMap, plan);

    // 3. 日志 & 返回
    if (verbose) {
      if (result.isPassed()) {
        logger.debug("[GuardrailAdapter] Post-verify ALLOW");
      } else {
        logger.warn(
            "[GuardrailAdapter] Post-verify {}: reason={}", result.status(), result.reason());
      }
    }

    return result.isPassed();
  }

  // ========================================================================
  // 内部方法
  // ========================================================================

  /** 将 StepResult 转换为 GuardrailService 可识别的观测 Map。 */
  private static Map<String, Object> convertStepResultToObsMap(StepResult sr) {
    Map<String, Object> map = new LinkedHashMap<>();

    switch (sr) {
      case StepResult.Finish finish -> {
        map.put("status", PostValidator.ObsStatus.SUCCESS.name());
        map.put("summary", finish.answer() != null ? finish.answer() : "");
        map.put("rawData", finish.answer() != null ? finish.answer() : "");
        map.put("metadata", Map.of("stepType", "FINISH"));
      }
      case StepResult.Failure failure -> {
        map.put("status", PostValidator.ObsStatus.FAILURE.name());
        map.put(
            "summary",
            failure.errorMessage() != null ? failure.errorMessage() : "Unspecified failure");
        map.put("rawData", "");
        map.put("metadata", Map.of("stepType", "FAILURE"));
      }
      case StepResult.Continue cont -> {
        // Continue 可能携带 Observation
        if (cont.observation() != null) {
          var obs = cont.observation();
          map.put("status", obs.status().name());
          map.put("summary", obs.summary() != null ? obs.summary() : "");
          map.put("rawData", obs.rawData() != null ? obs.rawData() : "");
          map.put("metadata", obs.metadata());
        } else {
          map.put("status", PostValidator.ObsStatus.SUCCESS.name());
          map.put("summary", "Continue without observation");
          map.put("rawData", "");
          map.put("metadata", Map.of("stepType", "CONTINUE"));
        }
      }
    }

    return Map.copyOf(map);
  }

  /** 从 Map 中安全获取字符串值。 */
  private static String safeString(Object value) {
    if (value == null) return null;
    if (value instanceof String s) return s;
    return String.valueOf(value);
  }

  // ========================================================================
  // 访问器
  // ========================================================================

  /** 获取内部的 GuardrailService 实例（可直接注册/卸载验证器）。 */
  public GuardrailService guardrailService() {
    return guardrailService;
  }

  /** Map string action type to Plan.Intent. */
  private static Plan.Intent toIntent(String type) {
    return switch (type) {
      case "FINISH" -> Plan.Intent.FINISH;
      case "TOOL_CALL" -> Plan.Intent.SEARCH;
      case "REVISION" -> Plan.Intent.REVISION;
      default -> Plan.Intent.ANALYZE;
    };
  }
}
