package org.cland.alice.model.common;

/** 预定义的模型枚举，对应设计文档中的 Model 元数据集合。 */
public enum ModelEnum {

  // ======== OpenAI ========
  GPT_4O("gpt-4o", "openai", Capability.ALL, 2.50, 10.00),
  GPT_4O_MINI("gpt-4o-mini", "openai", Capability.ALL, 0.15, 0.60),
  GPT_4_TURBO("gpt-4-turbo", "openai", Capability.FC_VISION, 10.00, 30.00),
  O1("o1", "openai", Capability.ALL, 15.00, 60.00),
  O1_MINI("o1-mini", "openai", Capability.ALL, 1.10, 4.40),

  // ======== Anthropic ========
  CLAUDE_3_5_SONNET("claude-3-5-sonnet-latest", "anthropic", Capability.ALL, 3.00, 15.00),
  CLAUDE_3_HAIKU("claude-3-haiku", "anthropic", Capability.ALL, 0.25, 1.25),
  CLAUDE_3_OPUS("claude-3-opus", "anthropic", Capability.ALL, 15.00, 75.00),

  // ======== Google ========
  GEMINI_2_0_FLASH("gemini-2.0-flash", "google", Capability.ALL, 0.10, 0.40),
  GEMINI_2_0_PRO("gemini-2.0-pro", "google", Capability.ALL, 1.25, 5.00),

  // ======== Gemma 4 (Local, OpenAI-compatible) ========
  GEMMA_4("gemma-4", "gemma4", Capability.ALL, 0.0, 0.0),

  // ======== DeepSeek ========
  DEEPSEEK_V3("deepseek-v4-flash", "deepseek", Capability.FC, 0.27, 1.10),
  DEEPSEEK_R1("deepseek-reasoner", "deepseek", Capability.NONE, 0.55, 2.19),

  // ======== Qwen ========
  QWEN_MAX("qwen-max", "alibaba", Capability.ALL, 2.00, 6.00),
  QWEN_PLUS("qwen-plus", "alibaba", Capability.ALL, 0.80, 2.00);

  private final String modelId;
  private final String supplierName;
  private final Capability capability;
  private final double inputPricePer1K;
  private final double outputPricePer1K;

  ModelEnum(
      String modelId,
      String supplierName,
      Capability capability,
      double inputPricePer1K,
      double outputPricePer1K) {
    this.modelId = modelId;
    this.supplierName = supplierName;
    this.capability = capability;
    this.inputPricePer1K = inputPricePer1K;
    this.outputPricePer1K = outputPricePer1K;
  }

  public String modelId() {
    return modelId;
  }

  public String supplierName() {
    return supplierName;
  }

  public Capability capability() {
    return capability;
  }

  public double inputPricePer1K() {
    return inputPricePer1K;
  }

  public double outputPricePer1K() {
    return outputPricePer1K;
  }

  /** 根据 modelId 查找枚举，不区分大小写。 */
  public static ModelEnum fromModelId(String modelId) {
    for (ModelEnum m : values()) {
      if (m.modelId.equalsIgnoreCase(modelId)) {
        return m;
      }
    }
    throw new IllegalArgumentException("Unknown modelId: " + modelId);
  }

  /** 模型能力标签（与 Model.Capability 位掩码兼容）。 */
  public enum Capability {
    NONE(0),
    FC(1), // Function Call
    VISION(1 << 1),
    STREAMING(1 << 2),
    FC_VISION(1 | (1 << 1)), // FC + Vision
    ALL((1 << 3) - 1);

    final int mask;

    Capability(int mask) {
      this.mask = mask;
    }

    public boolean supports(Capability other) {
      return (this.mask & other.mask) != 0;
    }

    public int mask() {
      return mask;
    }

    /** 将 ModelEnum.Capability 转换为 Model.Capability 位值。 */
    public int toModelCapabilityMask() {
      return mask;
    }
  }
}
