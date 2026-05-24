package org.cland.alice.memory.core;

/**
 * 一次交互步骤的原始记录，是 EpisodicVault 的基本存储单元。
 *
 * <p>对应设计文档中 Trace 的组成元素 step。
 */
public final class Step {

  private final String stepId;
  private final String action;
  private final String input;
  private final String output;
  private final long timestamp;
  private final boolean success;
  private final double importance;

  private Step(Builder builder) {
    this.stepId = builder.stepId;
    this.action = builder.action;
    this.input = builder.input;
    this.output = builder.output;
    this.timestamp = builder.timestamp;
    this.success = builder.success;
    this.importance = builder.importance;
  }

  public String stepId() {
    return stepId;
  }

  public String action() {
    return action;
  }

  public String input() {
    return input;
  }

  public String output() {
    return output;
  }

  public long timestamp() {
    return timestamp;
  }

  public boolean success() {
    return success;
  }

  public double importance() {
    return importance;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** 从已有 Step 创建 Builder（用于修改部分字段，如重要度）。 */
  public static Builder builder(Step from) {
    return new Builder(from);
  }

  @Override
  public String toString() {
    return "Step{stepId='%s', action='%s', success=%s, importance=%.2f}"
        .formatted(stepId, action, success, importance);
  }

  // ---------------------------------------------------------------

  public static final class Builder {
    private String stepId;
    private String action;
    private String input;
    private String output;
    private long timestamp;
    private boolean success = true;
    private double importance = 0.5;

    private Builder() {}

    /** 从已有 Step 复制字段 */
    private Builder(Step from) {
      this.stepId = from.stepId;
      this.action = from.action;
      this.input = from.input;
      this.output = from.output;
      this.timestamp = from.timestamp;
      this.success = from.success;
      this.importance = from.importance;
    }

    public Builder stepId(String stepId) {
      this.stepId = stepId;
      return this;
    }

    public Builder action(String action) {
      this.action = action;
      return this;
    }

    public Builder input(String input) {
      this.input = input;
      return this;
    }

    public Builder output(String output) {
      this.output = output;
      return this;
    }

    public Builder timestamp(long timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public Builder success(boolean success) {
      this.success = success;
      return this;
    }

    public Builder importance(double importance) {
      this.importance = Math.max(0.0, Math.min(1.0, importance));
      return this;
    }

    public Step build() {
      return new Step(this);
    }
  }
}
