package org.cland.alice.core.agent.kernel.graph;

/**
 * 效果网关（Effect 源：工具/文件/IO）—— 执行副作用。
 *
 * <p>效果的写权在 effect 边界经 GuardrailToolProxy 校验（默认无）；工具不得绕过 decision/仲裁点改结构 —— 账本无通用写入口即结构保证。返回
 * observation 供决策点回流。
 */
public interface EffectGateway {

  /** 执行一次效果。 */
  EffectOutcome invoke(ToolCallReq call);
}
