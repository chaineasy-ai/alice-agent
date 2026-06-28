package org.cland.alice.core.agent;

import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.planner.model.ModelSession;
import org.cland.alice.core.planner.model.PlannerModelSupplier;
import org.cland.alice.model.Call;
import org.cland.alice.model.ModelProvider;

/**
 * 默认 {@link PlannerModelSupplier} 实现 — 桥接 {@link ModelProvider} 到 Planner 双路径模型。
 *
 * <p>FastPath 对应轻量指令模型 (System 1)，SlowPath 对应高性能推理模型 (System 2)。
 *
 * <p>以 {@code instructionModelId} 和 {@code reasoningModelId} 区分双路径：
 *
 * <pre>
 *   DefaultPlannerModelSupplier supplier = DefaultPlannerModelSupplier.builder()
 *       .provider(ModelProvider.getInstance())
 *       .instructionModelId("deepseek-v4-flash")
 *       .reasoningModelId("deepseek-v4-flash")
 *       .build();
 * </pre>
 */
public final class DefaultPlannerModelSupplier implements PlannerModelSupplier {

  private final ModelProvider provider;
  private final String instructionModelId;
  private final String reasoningModelId;

  private DefaultPlannerModelSupplier(Builder builder) {
    this.provider = Objects.requireNonNull(builder.provider, "provider must not be null");
    this.instructionModelId =
        Objects.requireNonNull(builder.instructionModelId, "instructionModelId must not be null");
    this.reasoningModelId =
        Objects.requireNonNull(builder.reasoningModelId, "reasoningModelId must not be null");
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public ModelSession getInstructionModel() {
    return ModelSession.of(instructionModelId, "", defaultParameters());
  }

  @Override
  public ModelSession getReasoningModel() {
    return ModelSession.of(reasoningModelId, "", defaultParameters());
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

  private static Map<String, Object> defaultParameters() {
    return Map.of("temperature", 0.7, "max_tokens", 4096);
  }

  // ========== Builder ==========

  public static final class Builder {
    private ModelProvider provider;
    private String instructionModelId;
    private String reasoningModelId;

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

    public DefaultPlannerModelSupplier build() {
      return new DefaultPlannerModelSupplier(this);
    }
  }
}
