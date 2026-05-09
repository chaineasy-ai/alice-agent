package org.cland.alice.model;

import java.util.Map;
import java.util.Objects;

/** 模型元数据定义，包含能力标签及成本权重。 对应设计文档中的 Model 领域对象。 */
public final class Model {

  private final String modelId;
  private final String supplierName;
  private final Capability capability;
  private final Pricing pricing;
  private final Map<String, Object> config;

  private Model(Builder builder) {
    this.modelId = Objects.requireNonNull(builder.modelId, "modelId must not be null");
    this.supplierName =
        Objects.requireNonNull(builder.supplierName, "supplierName must not be null");
    this.capability = builder.capability != null ? builder.capability : Capability.NONE;
    this.pricing = builder.pricing != null ? builder.pricing : Pricing.ZERO;
    this.config = builder.config != null ? Map.copyOf(builder.config) : Map.of();
  }

  public static Builder builder() {
    return new Builder();
  }

  // ========== 能力标签 ==========

  /** 模型能力位掩码。 */
  public enum Capability {
    /** 仅文本 */
    NONE(0),
    /** 函数调用 (Function Calling) */
    FUNCTION_CALL(1),
    /** 视觉识别 (Vision) */
    VISION(1 << 1),
    /** 流式输出 (Streaming) */
    STREAMING(1 << 2),
    /** 全功能 */
    ALL(FUNCTION_CALL.mask | VISION.mask | STREAMING.mask);

    final int mask;

    Capability(int mask) {
      this.mask = mask;
    }

    public boolean supports(Capability other) {
      return (this.mask & other.mask) != 0;
    }

    /** 从位掩码创建 Capability。 */
    public static Capability fromMask(int mask) {
      for (Capability c : values()) {
        if (c.mask == mask) return c;
      }
      // 未精确匹配时按位组合
      int m = 0;
      if ((mask & FUNCTION_CALL.mask) != 0) m |= FUNCTION_CALL.mask;
      if ((mask & VISION.mask) != 0) m |= VISION.mask;
      if ((mask & STREAMING.mask) != 0) m |= STREAMING.mask;
      if (m == FUNCTION_CALL.mask) return FUNCTION_CALL;
      if (m == VISION.mask) return VISION;
      if (m == STREAMING.mask) return STREAMING;
      if (m == (FUNCTION_CALL.mask | VISION.mask)) return FUNCTION_CALL; // fallback
      return ALL;
    }
  }

  // ========== 成本定价 ==========

  /** 模型成本定价（单位：美元/1K tokens）。 */
  public record Pricing(double inputPer1K, double outputPer1K) {
    public static final Pricing ZERO = new Pricing(0, 0);

    /** 估算本次调用的成本 */
    public double estimateCost(int inputTokens, int outputTokens) {
      return (inputTokens / 1000.0 * inputPer1K) + (outputTokens / 1000.0 * outputPer1K);
    }
  }

  // ========== Getter ==========

  public String modelId() {
    return modelId;
  }

  public String supplierName() {
    return supplierName;
  }

  public Capability capability() {
    return capability;
  }

  public Pricing pricing() {
    return pricing;
  }

  public Map<String, Object> config() {
    return config;
  }

  // ========== Builder ==========

  public static final class Builder {
    private String modelId;
    private String supplierName;
    private Capability capability;
    private Pricing pricing;
    private Map<String, Object> config;

    private Builder() {}

    public Builder modelId(String modelId) {
      this.modelId = modelId;
      return this;
    }

    public Builder supplierName(String supplierName) {
      this.supplierName = supplierName;
      return this;
    }

    public Builder capability(Capability capability) {
      this.capability = capability;
      return this;
    }

    public Builder pricing(Pricing pricing) {
      this.pricing = pricing;
      return this;
    }

    public Builder config(Map<String, Object> config) {
      this.config = config;
      return this;
    }

    public Model build() {
      return new Model(this);
    }
  }

  @Override
  public String toString() {
    return "Model{modelId='" + modelId + "', supplier='" + supplierName + "'}";
  }
}
