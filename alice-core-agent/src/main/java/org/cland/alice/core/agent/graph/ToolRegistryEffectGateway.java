package org.cland.alice.core.agent.graph;

import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.agent.kernel.graph.EffectGateway;
import org.cland.alice.core.agent.kernel.graph.EffectOutcome;
import org.cland.alice.core.agent.kernel.graph.ToolCallReq;
import org.cland.alice.tool.gateway.ToolRegistry;
import org.cland.alice.tool.gateway.engine.ExecutionEngine;
import org.cland.alice.tool.gateway.engine.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TAO Action 效果网关的生产适配（经 ExecutionEngine 执行工具）。
 *
 * <p>效果写权边界（D11：默认无结构写权；GuardrailToolProxy 校验）由外层接线装配 —— 本网关只做 执行 + observation 回流（rawData
 * 优先、summary 兜底），与 legacy 效果回流语义一致。
 *
 * <p><b>#665 护栏接线</b>：图内核的副作用不经过 {@code AgentExecutor}，因此必须在本网关内注入 {@link PreVerifyHook}（装配方注入
 * {@code Agent.verifyToolCall}，与 legacy 同一口径）。钩子返回非 null ⇒ <b>不调用</b> {@link
 * ExecutionEngine#invoke}，直接返回失败 observation，语义等同 legacy 的 blocked。
 */
public final class ToolRegistryEffectGateway implements EffectGateway {

  private static final Logger logger = LoggerFactory.getLogger(ToolRegistryEffectGateway.class);

  /**
   * 效果执行前的护栏钩子（#665）。
   *
   * <p>实现方应复用 {@code Agent.verifyPre} 的判据（一概念一实现），不得在此重复黑名单。
   */
  public interface PreVerifyHook {

    /**
     * @param toolName 工具名
     * @param args 工具参数（危险命令/路径可能藏在其中）
     * @return null 表示放行；非 null 为拦截原因
     */
    String preVerify(String toolName, Map<String, Object> args);
  }

  /** 拦截 observation 文案（与 legacy pre-verify blocked 语义一致）。 */
  static final String BLOCKED_OBSERVATION =
      "Blocked by guardrail: forbidden command/path in tool parameters";

  private final ExecutionEngine engine;
  private final PreVerifyHook preVerifyHook;

  /**
   * 带护栏钩子的生产装配（推荐）。
   *
   * @param registry 工具注册中心（工具实际执行源）
   * @param preVerifyHook 执行前护栏钩子，非 null
   */
  public ToolRegistryEffectGateway(ToolRegistry registry, PreVerifyHook preVerifyHook) {
    this(ExecutionEngine.builder().registry(registry).build(), preVerifyHook);
  }

  /**
   * 兼容构造：<b>无护栏实例</b>。
   *
   * <p><b>警告</b>：本构造器不注入 {@link PreVerifyHook}，效果执行前不做任何 pre-verify 检查， 危险命令/路径会直接落到工具执行层。生产装配一律使用
   * {@link #ToolRegistryEffectGateway(ToolRegistry, PreVerifyHook)}；此处仅为旧调用方/离线测试保留，并会打印显式告警。
   *
   * @param registry 工具注册中心（工具实际执行源）
   */
  public ToolRegistryEffectGateway(ToolRegistry registry) {
    this(ExecutionEngine.builder().registry(registry).build(), null);
    logger.warn(
        "[ToolRegistryEffectGateway] 无护栏实例（no PreVerifyHook）：效果执行前不做 pre-verify，"
            + "生产装配必须注入 Agent.verifyToolCall");
  }

  /** 测试/高级装配：直接注入执行引擎（用于替换执行源与调用计数断言）。 */
  ToolRegistryEffectGateway(ExecutionEngine engine, PreVerifyHook preVerifyHook) {
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
    this.preVerifyHook = preVerifyHook;
  }

  @Override
  public EffectOutcome invoke(ToolCallReq call) {
    PreVerifyHook hook = preVerifyHook;
    if (hook != null) {
      String blockedReason;
      try {
        blockedReason = hook.preVerify(call.name(), call.arguments());
      } catch (Exception e) {
        // fail-closed：护栏自身异常不得放行副作用
        logger.warn(
            "[ToolRegistryEffectGateway] pre-verify hook failed for tool {}: {}",
            call.name(),
            e.getMessage());
        blockedReason = "Blocked by guardrail: pre-verify error (" + e.getMessage() + ")";
      }
      if (blockedReason != null) {
        logger.warn(
            "[ToolRegistryEffectGateway] pre-verify blocked tool={} params={} reason={}",
            call.name(),
            call.arguments(),
            blockedReason);
        return EffectOutcome.fail(blockedReason);
      }
    }
    try {
      ToolResult result = engine.invoke(call.name(), call.arguments());
      String text =
          result.rawData() != null && !result.rawData().isBlank()
              ? result.rawData()
              : (result.summary() != null ? result.summary() : "");
      boolean ok = result.status() == ToolResult.Status.SUCCESS;
      logger.debug("[ToolRegistryEffectGateway] tool={} ok={}", call.name(), ok);
      return ok ? EffectOutcome.ok(text) : EffectOutcome.fail(text);
    } catch (Exception e) {
      logger.warn("[ToolRegistryEffectGateway] tool {} failed: {}", call.name(), e.getMessage());
      return EffectOutcome.fail(
          e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }
  }
}
