package org.cland.alice.facade.cmd.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 运行配置，封装 CLI 解析后的所有参数。
 *
 * <p>对应设计文档中 {@code RunConfig} 实体，由 {@code CommandParser} 创建， 传递给 {@code ExecutionCoordinator} 驱动
 * Agent 执行。
 *
 * <pre>
 *   RunConfig {
 *     String task;          // 任务描述（必填）
 *     String model;         // 模型 ID（可选，覆盖默认）
 *     boolean jsonOutput;   // JSON 输出模式
 *     boolean verbose;      // 详细输出
 *     long timeoutSeconds;  // 超时时间
 *     Map&lt;String,String&gt; envVars; // 额外环境变量
 *   }
 * </pre>
 */
public final class RunConfig {

  /** 默认模型 */
  public static final String DEFAULT_MODEL = "gpt-4o-mini";

  /** 默认超时（180 秒） */
  public static final long DEFAULT_TIMEOUT_SECONDS = 180;

  private final String task;
  private final String model;
  private final boolean chat;
  private final boolean jsonOutput;
  private final boolean verbose;
  private final long timeoutSeconds;
  private final Map<String, String> envVars;

  private RunConfig(Builder builder) {
    this.task = Objects.requireNonNull(builder.task, "task must not be null");
    this.model = builder.model != null ? builder.model : DEFAULT_MODEL;
    this.chat = builder.chat;
    this.jsonOutput = builder.jsonOutput;
    this.verbose = builder.verbose;
    this.timeoutSeconds =
        builder.timeoutSeconds > 0 ? builder.timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
    this.envVars = builder.envVars != null ? Map.copyOf(builder.envVars) : Map.of();
  }

  // ========== Getters ==========

  /** 任务描述 */
  public String task() {
    return task;
  }

  /** 模型 ID */
  public String model() {
    return model;
  }

  /** 是否进入交互式 chat 模式 */
  public boolean chat() {
    return chat;
  }

  /** 是否启用 JSON 输出 */
  public boolean jsonOutput() {
    return jsonOutput;
  }

  /** 是否打印详细信息 */
  public boolean verbose() {
    return verbose;
  }

  /** 任务超时（秒） */
  public long timeoutSeconds() {
    return timeoutSeconds;
  }

  /** 额外环境变量 */
  public Map<String, String> envVars() {
    return envVars;
  }

  // ========== Builder ==========

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String task;
    private String model;
    private boolean chat;
    private boolean jsonOutput;
    private boolean verbose;
    private long timeoutSeconds;
    private Map<String, String> envVars;

    private Builder() {}

    public Builder task(String task) {
      this.task = task;
      return this;
    }

    public Builder model(String model) {
      this.model = model;
      return this;
    }

    public Builder chat(boolean chat) {
      this.chat = chat;
      return this;
    }

    public Builder jsonOutput(boolean jsonOutput) {
      this.jsonOutput = jsonOutput;
      return this;
    }

    public Builder verbose(boolean verbose) {
      this.verbose = verbose;
      return this;
    }

    public Builder timeoutSeconds(long timeoutSeconds) {
      this.timeoutSeconds = timeoutSeconds;
      return this;
    }

    public Builder envVars(Map<String, String> envVars) {
      this.envVars = envVars;
      return this;
    }

    public Builder envVar(String key, String value) {
      if (this.envVars == null) {
        this.envVars = new HashMap<>();
      }
      this.envVars.put(key, value);
      return this;
    }

    public RunConfig build() {
      return new RunConfig(this);
    }
  }

  @Override
  public String toString() {
    return "RunConfig{task='"
        + task
        + "', model='"
        + model
        + "', jsonOutput="
        + jsonOutput
        + ", verbose="
        + verbose
        + ", timeout="
        + timeoutSeconds
        + "s}";
  }
}
