package org.cland.alice.core.agent;

import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.planner.model.ModelSession;
import org.cland.alice.core.planner.model.PlannerModelSupplier;
import org.cland.alice.model.Call;
import org.cland.alice.model.ModelConfigLoader.PathThinkingConfig;
import org.cland.alice.model.ModelConfigLoader.PlannerConfig;
import org.cland.alice.model.ModelProvider;

/**
 * 默认 {@link PlannerModelSupplier} 实现 — 桥接 {@link ModelProvider} 到 Planner 双路径模型。
 *
 * <p>FastPath 对应轻量指令模型 (System 1)，默认关闭 LLM 内部推理（enable_thinking=false）。 SlowPath 对应高性能推理模型 (System
 * 2)，默认开启深度推理（enable_thinking=true）。
 *
 * <p>thinking 参数可通过 {@link PlannerConfig} 配置：见 {@code ~/.alice/model.json} 的 {@code
 * planner.instruction} 和 {@code planner.reasoning} 子节。
 *
 * <pre>
 *   DefaultPlannerModelSupplier supplier = DefaultPlannerModelSupplier.builder()
 *       .provider(ModelProvider.getInstance())
 *       .instructionModelId("deepseek-v4-flash")
 *       .reasoningModelId("deepseek-v4-flash")
 *       .plannerConfig(configLoader.getPlannerConfig())
 *       .build();
 * </pre>
 */
public final class DefaultPlannerModelSupplier implements PlannerModelSupplier {

  private final ModelProvider provider;
  private final String instructionModelId;
  private final String reasoningModelId;

  /** 快路径 thinking 参数（默认关闭推理） */
  private final boolean instructionEnableThinking;

  private final String instructionReasoningEffort;

  /** 慢路径 thinking 参数（默认开启推理） */
  private final boolean reasoningEnableThinking;

  private final String reasoningReasoningEffort;

  private DefaultPlannerModelSupplier(Builder builder) {
    this.provider = Objects.requireNonNull(builder.provider, "provider must not be null");
    this.instructionModelId =
        Objects.requireNonNull(builder.instructionModelId, "instructionModelId must not be null");
    this.reasoningModelId =
        Objects.requireNonNull(builder.reasoningModelId, "reasoningModelId must not be null");

    // 从 builder 或 PlannerConfig 读取 thinking 参数
    PathThinkingConfig instrCfg = builder.instructionConfig;
    PathThinkingConfig reasCfg = builder.reasoningConfig;

    this.instructionEnableThinking =
        builder.instructionEnableThinking != null
            ? builder.instructionEnableThinking
            : (instrCfg != null ? instrCfg.enableThinking() : false);
    this.instructionReasoningEffort =
        builder.instructionReasoningEffort != null
            ? builder.instructionReasoningEffort
            : (instrCfg != null ? instrCfg.reasoningEffort() : "low");

    this.reasoningEnableThinking =
        builder.reasoningEnableThinking != null
            ? builder.reasoningEnableThinking
            : (reasCfg != null ? reasCfg.enableThinking() : true);
    this.reasoningReasoningEffort =
        builder.reasoningReasoningEffort != null
            ? builder.reasoningReasoningEffort
            : (reasCfg != null ? reasCfg.reasoningEffort() : "high");
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * 获取指令模型会话（FastPath / 快思考）。
   *
   * <p>默认关闭 LLM 内部推理（enable_thinking=false），模拟 Observe 快速响应。
   */
  @Override
  public ModelSession getInstructionModel() {
    return ModelSession.of(
        instructionModelId,
        "",
        Map.of(
            "temperature",
            0.7,
            "max_tokens",
            4096,
            "enable_thinking",
            instructionEnableThinking,
            "reasoning_effort",
            instructionReasoningEffort));
  }

  /**
   * 获取推理模型会话（SlowPath / 慢思考）。
   *
   * <p>默认开启 LLM 深度推理（enable_thinking=true），用于复杂规划。
   */
  @Override
  public ModelSession getReasoningModel() {
    return ModelSession.of(
        reasoningModelId,
        "",
        Map.of(
            "temperature",
            0.7,
            "max_tokens",
            4096,
            "enable_thinking",
            reasoningEnableThinking,
            "reasoning_effort",
            reasoningReasoningEffort));
  }

  @Override
  public Call.Response request(Call call) throws Exception {
    var supplier = provider.getSupplier(call.payload().modelId());
    if (supplier == null) {
      throw new IllegalStateException(
          "No model supplier found for modelId: " + call.payload().modelId());
    }
    return supplier.request(call);
  }

  // ========== Builder ==========

  public static final class Builder {
    private ModelProvider provider;
    private String instructionModelId;
    private String reasoningModelId;

    // 直接指定 thinking 参数（优先级高于 plannerConfig）
    private Boolean instructionEnableThinking;
    private String instructionReasoningEffort;
    private Boolean reasoningEnableThinking;
    private String reasoningReasoningEffort;

    // 从 PlannerConfig 提取 thinking 参数
    private PathThinkingConfig instructionConfig;
    private PathThinkingConfig reasoningConfig;

    private Builder() {}

    /** ModelProvider 实例（通常为 {@code ModelProvider.getInstance()}）。 */
    public Builder provider(ModelProvider provider) {
      this.provider = provider;
      return this;
    }

    /** FastPath 使用的轻量指令模型 ID。 */
    public Builder instructionModelId(String modelId) {
      this.instructionModelId = modelId;
      return this;
    }

    /** SlowPath 使用的高性能推理模型 ID。 */
    public Builder reasoningModelId(String modelId) {
      this.reasoningModelId = modelId;
      return this;
    }

    /** 从 {@link PlannerConfig} 批量设置 thinking 参数。 */
    public Builder plannerConfig(PlannerConfig cfg) {
      if (cfg != null) {
        if (cfg.instruction() != null) {
          this.instructionConfig = cfg.instruction();
        }
        if (cfg.reasoning() != null) {
          this.reasoningConfig = cfg.reasoning();
        }
        // 模型 ID：仅当 builder 未显式设置时才从 config 读取
        if (cfg.instructionModelId() != null && this.instructionModelId == null) {
          this.instructionModelId = cfg.instructionModelId();
        }
        if (cfg.reasoningModelId() != null && this.reasoningModelId == null) {
          this.reasoningModelId = cfg.reasoningModelId();
        }
      }
      return this;
    }

    /** 覆盖指令路径的 enable_thinking（优先级最高）。 */
    public Builder instructionEnableThinking(boolean v) {
      this.instructionEnableThinking = v;
      return this;
    }

    /** 覆盖推理路径的 enable_thinking（优先级最高）。 */
    public Builder reasoningEnableThinking(boolean v) {
      this.reasoningEnableThinking = v;
      return this;
    }

    public DefaultPlannerModelSupplier build() {
      return new DefaultPlannerModelSupplier(this);
    }
  }
}
