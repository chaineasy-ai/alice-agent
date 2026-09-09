package org.cland.alice.core.agent.graph;

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
 */
public final class ToolRegistryEffectGateway implements EffectGateway {

  private static final Logger logger = LoggerFactory.getLogger(ToolRegistryEffectGateway.class);

  private final ExecutionEngine engine;

  /**
   * @param registry 工具注册中心（工具实际执行源）
   */
  public ToolRegistryEffectGateway(ToolRegistry registry) {
    Objects.requireNonNull(registry, "registry must not be null");
    this.engine = ExecutionEngine.builder().registry(registry).build();
  }

  @Override
  public EffectOutcome invoke(ToolCallReq call) {
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
