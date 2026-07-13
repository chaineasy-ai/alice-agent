package org.cland.alice.core.agent.guardrail;

import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.guardrail.AuditResult;
import org.cland.alice.guardrail.GuardrailService;
import org.cland.alice.guardrail.PostValidator;
import org.cland.alice.guardrail.validators.ToolExistenceValidator;
import org.cland.alice.guardrail.validators.ToolMicroLoopValidator;
import org.cland.alice.guardrail.validators.ToolResultValidator;
import org.cland.alice.tool.gateway.ToolRegistry;
import org.cland.alice.tool.gateway.engine.ExecutionEngine;
import org.cland.alice.tool.gateway.engine.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具调用守卫代理 (Proxy Pattern) — 在 {@link ExecutionEngine#invoke} 前后插入 Guardrail 检查。
 *
 * <p><b>职责：</b>
 *
 * <ul>
 *   <li><b>Pre-check:</b> 每次工具调用前，运行 {@link ToolExistenceValidator}（工具是否存在）、 {@link
 *       ToolMicroLoopValidator}（微循环检测）等 PreValidator 链
 *   <li><b>Execute:</b> 通过 {@link ExecutionEngine} 执行工具
 *   <li><b>Post-check:</b> 执行后运行 {@link ToolResultValidator}（结果一致性校验）
 *   <li><b>History:</b> 自动将调用记录注入 {@link ToolMicroLoopValidator#recordCall}， 供后续跨周期微循环检测使用
 * </ul>
 *
 * <p><b>使用方式：</b>
 *
 * <pre>{@code
 * // 方式一：全自动（内置所有默认验证器）
 * var proxy = GuardrailToolProxy.createDefault(toolRegistry, executionEngine);
 * ToolResult result = proxy.invoke("web_search", Map.of("query", "java"));
 *
 * // 方式二：注入自定义 GuardrailService
 * GuardrailService customGs = new GuardrailService();
 * customGs.registerPreValidator(...);
 * var proxy = new GuardrailToolProxy(executionEngine, customGs, toolRegistry);
 * }</pre>
 */
public final class GuardrailToolProxy {

  private static final Logger logger = LoggerFactory.getLogger(GuardrailToolProxy.class);

  private final ExecutionEngine executionEngine;
  private final GuardrailService guardrailService;
  private final ToolMicroLoopValidator microLoopValidator;

  /**
   * 全构造。
   *
   * @param executionEngine 工具执行引擎（不能为 null）
   * @param guardrailService Guardrail 服务（不能为 null）
   * @param microLoopValidator 微循环检测器（可为 null，不启用微循环检测）
   */
  public GuardrailToolProxy(
      ExecutionEngine executionEngine,
      GuardrailService guardrailService,
      ToolMicroLoopValidator microLoopValidator) {
    this.executionEngine =
        Objects.requireNonNull(executionEngine, "executionEngine must not be null");
    this.guardrailService =
        Objects.requireNonNull(guardrailService, "guardrailService must not be null");
    this.microLoopValidator = microLoopValidator;
  }

  /**
   * 快捷工厂：创建默认配置的代理，自动注册：
   *
   * <ul>
   *   <li>{@link ToolExistenceValidator} — 工具存在性检查
   *   <li>{@link ToolMicroLoopValidator} — 微循环检测
   *   <li>{@link ToolResultValidator} — 结果一致性校验
   * </ul>
   *
   * @param toolRegistry 工具注册表
   * @param executionEngine 工具执行引擎
   * @return 配置完整的 GuardrailToolProxy
   */
  public static GuardrailToolProxy createDefault(
      ToolRegistry toolRegistry, ExecutionEngine executionEngine) {
    Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
    Objects.requireNonNull(executionEngine, "executionEngine must not be null");

    GuardrailService gs = new GuardrailService();

    // Pre-Validators
    ToolExistenceValidator existenceValidator = new ToolExistenceValidator(toolRegistry);
    gs.registerPreValidator(existenceValidator);

    ToolMicroLoopValidator loopValidator = new ToolMicroLoopValidator(toolRegistry);
    gs.registerPreValidator(loopValidator);

    // Post-Validators
    ToolResultValidator resultValidator = new ToolResultValidator(toolRegistry);
    gs.registerPostValidator(resultValidator);

    logger.info("[GuardrailToolProxy] Created default proxy with 2 PreValidators, 1 PostValidator");

    return new GuardrailToolProxy(executionEngine, gs, loopValidator);
  }

  // ========================================================================
  // 核心代理方法
  // ========================================================================

  /**
   * 代理工具调用 — 前置检查 → 执行 → 后置检查 → 记录历史。
   *
   * <p>任何阶段失败都会返回对应的 {@link ToolResult#failure}。
   *
   * @param toolName 工具名称
   * @param params 工具参数
   * @return 执行结果或拦截结果
   */
  public ToolResult invoke(String toolName, Map<String, Object> params) {
    if (toolName == null || toolName.isBlank()) {
      return ToolResult.failure("GuardrailToolProxy: toolName is null or empty");
    }

    // ====================================================================
    // Phase 1: Pre-check — 构建单步 Plan 并运行 PreValidator 链
    // ====================================================================
    Plan prePlan = buildSingleStepPlan(toolName, params);
    AuditResult preResult = guardrailService.verifyPlan(prePlan);

    if (!preResult.isPassed()) {
      String reason = preResult.reason() != null ? preResult.reason() : "Pre-check blocked";
      logger.warn("[GuardrailToolProxy] Pre-check BLOCKED: tool={} reason={}", toolName, reason);

      if (preResult.needsManualConfirm()) {
        return ToolResult.builder()
            .status(ToolResult.Status.FAILURE)
            .summary("Pre-check requires manual confirmation: " + reason)
            .rawData("")
            .metadata(
                Map.of("toolName", toolName, "guardrailAction", "MANUAL_CONFIRM", "reason", reason))
            .build();
      }

      return ToolResult.failure("Guardrail pre-check rejected: " + reason);
    }

    logger.debug("[GuardrailToolProxy] Pre-check ALLOW: tool={}", toolName);

    // ====================================================================
    // Phase 2: Execute — 委托给 ExecutionEngine
    // ====================================================================
    ToolResult execResult = executionEngine.invoke(toolName, params != null ? params : Map.of());

    // ====================================================================
    // Phase 3: Post-check — 运行 PostValidator 链
    // ====================================================================
    Map<String, Object> obsMap = buildObservationMap(toolName, execResult);
    AuditResult postResult = guardrailService.verifyResult(obsMap, prePlan);

    if (!postResult.isPassed()) {
      String reason = postResult.reason() != null ? postResult.reason() : "Post-check failed";
      logger.warn("[GuardrailToolProxy] Post-check FAILED: tool={} reason={}", toolName, reason);

      return ToolResult.builder()
          .status(ToolResult.Status.FAILURE)
          .summary("Guardrail post-check rejected: " + reason)
          .rawData(execResult.rawData() != null ? execResult.rawData() : "")
          .metadata(
              Map.of(
                  "toolName",
                  toolName,
                  "guardrailAction",
                  "POST_REJECT",
                  "reason",
                  reason,
                  "originalStatus",
                  execResult.status().name()))
          .build();
    }

    // ====================================================================
    // Phase 4: Record history — 供后续微循环检测使用
    // ====================================================================
    if (microLoopValidator != null) {
      microLoopValidator.recordCall(toolName, params);
    }

    logger.debug("[GuardrailToolProxy] Post-check ALLOW: tool={}", toolName);
    return execResult;
  }

  // ========================================================================
  // 内部方法
  // ========================================================================

  /**
   * 为单次工具调用构建单步 Plan，供 PreValidator/PostValidator 链使用。
   *
   * <p>Plan 只包含一个 TOOL_CALL 步骤，携带工具名和参数。
   */
  private static Plan buildSingleStepPlan(String toolName, Map<String, Object> params) {
    return Plan.builder()
        .type(Plan.Type.FAST_PATH)
        .summary("GuardrailToolProxy: " + toolName)
        .addStep(Plan.Step.of(Plan.Intent.SEARCH, toolName, params != null ? params : Map.of()))
        .build();
  }

  /**
   * 将 {@link ExecutionEngine} 的执行结果转换为 PostValidator 可识别的观测 Map。
   *
   * <p>格式与 {@link org.cland.alice.guardrail.PostValidator#check} 的输入一致。
   */
  private static Map<String, Object> buildObservationMap(String toolName, ToolResult result) {
    var map = new java.util.LinkedHashMap<String, Object>();

    // 状态映射
    PostValidator.ObsStatus obsStatus =
        switch (result.status()) {
          case SUCCESS -> PostValidator.ObsStatus.SUCCESS;
          case FAILURE -> PostValidator.ObsStatus.FAILURE;
          case TIMEOUT -> PostValidator.ObsStatus.TIMEOUT;
        };
    map.put("status", obsStatus);
    map.put("summary", result.summary() != null ? result.summary() : "");
    map.put("rawData", result.rawData() != null ? result.rawData() : "");

    // 合并元数据
    var meta = new java.util.LinkedHashMap<String, Object>();
    meta.put("toolName", toolName);
    if (result.metadata() != null) {
      meta.putAll(result.metadata());
    }
    map.put("metadata", Map.copyOf(meta));

    return Map.copyOf(map);
  }

  // ========================================================================
  // 访问器
  // ========================================================================

  /** 获取内部的 GuardrailService（可直接注册/卸载额外验证器）。 */
  public GuardrailService guardrailService() {
    return guardrailService;
  }

  /** 获取微循环检测器（可为 null）。 */
  public ToolMicroLoopValidator microLoopValidator() {
    return microLoopValidator;
  }

  /** 重置微循环检测器历史。 */
  public void resetLoopHistory() {
    if (microLoopValidator != null) {
      microLoopValidator.resetHistory();
    }
  }
}
