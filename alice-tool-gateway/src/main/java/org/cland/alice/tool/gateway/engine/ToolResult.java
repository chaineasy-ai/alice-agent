package org.cland.alice.tool.gateway.engine;

import java.util.Map;
import java.util.Objects;

/**
 * 工具执行结果 — ExecutionEngine 的输出。
 *
 * <p>轻量级结果类型，避免对 alice-core-agent 的 {@code Observation} 产生编译依赖。 上层（AgentCore）负责将其转换为 {@code
 * Observation}。
 */
public final class ToolResult {

  /** 执行状态 */
  public enum Status {
    SUCCESS,
    FAILURE,
    TIMEOUT
  }

  private final Status status;
  private final String summary;
  private final String rawData;
  private final Map<String, Object> metadata;
  private final long timestampMs;

  private ToolResult(Builder builder) {
    this.status = Objects.requireNonNull(builder.status, "status must not be null");
    this.summary = builder.summary;
    this.rawData = builder.rawData;
    this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : Map.of();
    this.timestampMs = builder.timestampMs > 0 ? builder.timestampMs : System.currentTimeMillis();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static ToolResult success(String summary) {
    return builder().status(Status.SUCCESS).summary(summary).build();
  }

  public static ToolResult success(String summary, String rawData) {
    return builder().status(Status.SUCCESS).summary(summary).rawData(rawData).build();
  }

  public static ToolResult failure(String summary) {
    return builder().status(Status.FAILURE).summary(summary).build();
  }

  public static ToolResult timeout(String target) {
    return builder().status(Status.TIMEOUT).summary("Timeout on: " + target).build();
  }

  // ========== Getters ==========

  public Status status() {
    return status;
  }

  public String summary() {
    return summary;
  }

  public String rawData() {
    return rawData;
  }

  public Map<String, Object> metadata() {
    return metadata;
  }

  public long timestampMs() {
    return timestampMs;
  }

  public boolean isSuccess() {
    return status == Status.SUCCESS;
  }

  public boolean isFailure() {
    return status == Status.FAILURE;
  }

  public boolean isTimeout() {
    return status == Status.TIMEOUT;
  }

  @Override
  public String toString() {
    return "ToolResult{status=" + status + ", summary='" + summary + "'}";
  }

  // ========== Builder ==========

  public static final class Builder {
    private Status status;
    private String summary;
    private String rawData;
    private Map<String, Object> metadata;
    private long timestampMs;

    private Builder() {}

    public Builder status(Status status) {
      this.status = status;
      return this;
    }

    public Builder summary(String summary) {
      this.summary = summary;
      return this;
    }

    public Builder rawData(String rawData) {
      this.rawData = rawData;
      return this;
    }

    public Builder metadata(Map<String, Object> metadata) {
      this.metadata = metadata;
      return this;
    }

    public Builder timestampMs(long timestampMs) {
      this.timestampMs = timestampMs;
      return this;
    }

    public ToolResult build() {
      return new ToolResult(this);
    }
  }
}
