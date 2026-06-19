package org.cland.alice.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 调用的生命周期实例，承载请求参数、执行状态及性能指标。 对应设计文档中的 Call 领域对象。 */
public final class Call {

  /** 全局唯一跟踪 ID */
  private final String traceId;

  /** 当前状态 */
  private volatile CallStatus status;

  /** 请求负载 */
  private final Payload payload;

  /** 响应结果 */
  private volatile Response result;

  /** 性能指标 */
  private final Metrics metrics;

  /** 扩展上下文 */
  private final Map<String, Object> attributes;

  private Call(Builder builder) {
    this.traceId = builder.traceId != null ? builder.traceId : UUID.randomUUID().toString();
    this.status = CallStatus.CREATED;
    this.payload = Objects.requireNonNull(builder.payload, "payload must not be null");
    this.result = null;
    this.metrics = new Metrics();
    this.attributes =
        builder.attributes != null
            ? new ConcurrentHashMap<>(builder.attributes)
            : new ConcurrentHashMap<>();
  }

  public static Builder builder() {
    return new Builder();
  }

  // ========== 状态转换 ==========

  /** 安全地转换状态，违反状态机规则时抛出 IllegalStateException。 */
  public synchronized void transitionTo(CallStatus target) {
    if (!status.canTransitionTo(target)) {
      throw new IllegalStateException(
          "Invalid state transition: " + status + " -> " + target + " (traceId: " + traceId + ")");
    }
    this.status = target;
  }

  // ========== Getter / Setter ==========

  public String traceId() {
    return traceId;
  }

  public CallStatus status() {
    return status;
  }

  public Payload payload() {
    return payload;
  }

  public Response result() {
    return result;
  }

  public Metrics metrics() {
    return metrics;
  }

  public Map<String, Object> attributes() {
    return attributes;
  }

  public void updateResult(Response response) {
    this.result = Objects.requireNonNull(response, "response must not be null");
    if (response.tokenUsage() != null) {
      this.metrics.tokenUsage(response.tokenUsage());
    }
  }

  // ========== 内部类型 ==========

  /** 请求负载 */
  public record Payload(String modelId, String prompt, Map<String, Object> parameters) {
    public Payload {
      Objects.requireNonNull(modelId, "modelId must not be null");
      Objects.requireNonNull(prompt, "prompt must not be null");
      parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
  }

  /** 工具调用（Function Calling 结果） */
  public record ToolCall(String name, String arguments) {
    public ToolCall {
      Objects.requireNonNull(name, "name must not be null");
      arguments = arguments == null ? "{}" : arguments;
    }
  }

  /** 响应结果 */
  public record Response(
      String content,
      TokenUsage tokenUsage,
      Map<String, Object> metadata,
      java.util.List<ToolCall> toolCalls) {
    public Response {
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
      toolCalls = toolCalls == null ? java.util.List.of() : java.util.List.copyOf(toolCalls);
    }

    /** 创建不含 toolCalls 的响应（向后兼容）。 */
    public static Response textOnly(
        String content, TokenUsage tokenUsage, Map<String, Object> metadata) {
      return new Response(content, tokenUsage, metadata, java.util.List.of());
    }
  }

  /** Token 消耗记录 */
  public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
    public int promptTokens() {
      return promptTokens;
    }

    public int completionTokens() {
      return completionTokens;
    }

    public int totalTokens() {
      return promptTokens + completionTokens;
    }
  }

  /** 性能指标 */
  public static final class Metrics {
    private volatile Instant startTime;
    private volatile Instant endTime;
    private volatile TokenUsage tokenUsage;

    public void start() {
      this.startTime = Instant.now();
    }

    public void stop() {
      this.endTime = Instant.now();
    }

    public void tokenUsage(TokenUsage usage) {
      this.tokenUsage = usage;
    }

    /** 毫秒级延迟 */
    public long latencyMs() {
      if (startTime == null || endTime == null) return -1;
      return Duration.between(startTime, endTime).toMillis();
    }

    public Instant startTime() {
      return startTime;
    }

    public Instant endTime() {
      return endTime;
    }

    public TokenUsage tokenUsage() {
      return tokenUsage;
    }
  }

  // ========== Builder ==========

  public static final class Builder {
    private String traceId;
    private Payload payload;
    private Map<String, Object> attributes;

    private Builder() {}

    public Builder traceId(String traceId) {
      this.traceId = traceId;
      return this;
    }

    public Builder payload(Payload payload) {
      this.payload = payload;
      return this;
    }

    public Builder attribute(String key, Object value) {
      if (this.attributes == null) {
        this.attributes = new ConcurrentHashMap<>();
      }
      this.attributes.put(key, value);
      return this;
    }

    public Builder attributes(Map<String, Object> attributes) {
      this.attributes = attributes;
      return this;
    }

    public Call build() {
      return new Call(this);
    }
  }
}
