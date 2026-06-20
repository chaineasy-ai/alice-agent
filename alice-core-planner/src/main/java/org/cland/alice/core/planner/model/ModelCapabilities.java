package org.cland.alice.core.planner.model;

import org.cland.alice.model.Model.Capability;

/**
 * 规划器模型能力抽象，桥接 alice-model 的 {@link Capability}。
 *
 * <p>定义模型的能力标签，用于策略路由选择合适模型。 底层委托给 {@link Capability} 的位掩码实现。
 */
public enum ModelCapabilities {

  /** 无特殊能力 */
  NONE(Capability.NONE),
  /** 函数调用 */
  FUNCTION_CALL(Capability.FUNCTION_CALL),
  /** 视觉识别 */
  VISION(Capability.VISION),
  /** 流式输出 */
  STREAMING(Capability.STREAMING),
  /** 全功能 */
  ALL(Capability.ALL);

  private final Capability delegate;

  ModelCapabilities(Capability delegate) {
    this.delegate = delegate;
  }

  /** 底层 Capability 位掩码。 */
  public Capability delegate() {
    return delegate;
  }

  /** 是否支持函数调用。 */
  public boolean supportsFunctionCall() {
    return delegate.supports(Capability.FUNCTION_CALL);
  }

  /** 是否支持流式输出。 */
  public boolean supportsStreaming() {
    return delegate.supports(Capability.STREAMING);
  }

  /** 是否支持视觉识别。 */
  public boolean supportsVision() {
    return delegate.supports(Capability.VISION);
  }

  /** 从 alice-model 的 Capability 转换。 */
  public static ModelCapabilities fromCapability(Capability cap) {
    if (cap == null) return NONE;
    for (ModelCapabilities mc : values()) {
      if (mc.delegate == cap) return mc;
    }
    boolean func = cap.supports(Capability.FUNCTION_CALL);
    boolean stream = cap.supports(Capability.STREAMING);
    boolean vision = cap.supports(Capability.VISION);
    if (func && stream && vision) return ALL;
    if (func && stream) return ALL;
    if (func) return FUNCTION_CALL;
    if (stream) return STREAMING;
    if (vision) return VISION;
    return NONE;
  }
}
