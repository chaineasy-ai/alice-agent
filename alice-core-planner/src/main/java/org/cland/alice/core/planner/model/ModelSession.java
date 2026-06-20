package org.cland.alice.core.planner.model;

import java.util.Map;
import org.cland.alice.model.Call;
import org.cland.alice.model.Call.Payload;
import org.cland.alice.model.Call.Response;
import org.cland.alice.model.Call.TokenUsage;
import org.cland.alice.model.CallStatus;

/**
 * 规划器模型会话抽象，封装 alice-model 的 {@link Call} 调用生命周期。
 *
 * <p>提供 {@code complete()} / {@code fail()} 的便捷接口。 底层通过 {@link Call} 完成实际的模型调用跟踪。
 */
public final class ModelSession {

  private final Call call;

  private ModelSession(Builder builder) {
    Payload payload = new Payload(builder.modelId, builder.prompt, builder.parameters);
    this.call = Call.builder().payload(payload).build();
    // Call 构造后状态为 CREATED，无需额外 transition
  }

  // ========== 构造工厂 ==========

  public static Builder builder() {
    return new Builder();
  }

  /** 快速创建会话。 */
  public static ModelSession of(String modelId, String prompt) {
    return builder().modelId(modelId).prompt(prompt).build();
  }

  /** 快速创建会话（含参数）。 */
  public static ModelSession of(String modelId, String prompt, Map<String, Object> parameters) {
    return builder().modelId(modelId).prompt(prompt).parameters(parameters).build();
  }

  // ========== 委托给 Call ==========

  /** 底层 {@link Call} 对象（用于 alice-model ModelSupplier 调用）。 */
  public Call call() {
    return call;
  }

  /** 模型 ID。 */
  public String modelId() {
    return call.payload().modelId();
  }

  /** 提示文本。 */
  public String prompt() {
    return call.payload().prompt();
  }

  /** 调用参数。 */
  public Map<String, Object> parameters() {
    return call.payload().parameters();
  }

  /** 响应文本。 */
  public String response() {
    Response result = call.result();
    return result != null ? result.content() : null;
  }

  /** 错误信息。 */
  public Throwable error() {
    return null; // Call 不直接存储 Throwable，由调用者管理
  }

  /** 是否已完成（成功或失败）。 */
  public boolean completed() {
    return call.status() == CallStatus.FINISHED
        || call.status() == CallStatus.FAILED
        || call.status() == CallStatus.ABORTED;
  }

  /** 标记会话完成并设置响应。 */
  public ModelSession complete(String response) {
    call.transitionTo(CallStatus.PENDING);
    call.transitionTo(CallStatus.RUNNING);
    call.updateResult(Response.textOnly(response, new TokenUsage(0, 0, 0), Map.of()));
    call.transitionTo(CallStatus.FINISHED);
    return this;
  }

  /** 标记会话失败。 */
  public ModelSession fail(Throwable error) {
    call.transitionTo(CallStatus.ABORTED);
    return this;
  }

  // ========== Builder ==========

  public static final class Builder {
    private String modelId;
    private String prompt;
    private Map<String, Object> parameters;

    private Builder() {}

    public Builder modelId(String modelId) {
      this.modelId = modelId;
      return this;
    }

    public Builder prompt(String prompt) {
      this.prompt = prompt;
      return this;
    }

    public Builder parameters(Map<String, Object> p) {
      this.parameters = p;
      return this;
    }

    public ModelSession build() {
      return new ModelSession(this);
    }
  }
}
