package org.cland.alice.memory;

import java.util.Objects;

/**
 * 标准操作程序（SOP）——ProcedureVault 中存储的基本单元。
 *
 * <p>表示一个"最佳实践"、工具使用 schema 或可重复执行的成功路径模式。 支持模式匹配和版本控制。
 */
public final class SOP {

  private final String sopId;
  private final String name;
  private final String pattern;
  private final String procedure;
  private final String toolName;
  private final String version;
  private final long createdAt;
  private final long updatedAt;

  private SOP(Builder builder) {
    this.sopId = Objects.requireNonNull(builder.sopId, "sopId must not be null");
    this.name = Objects.requireNonNull(builder.name, "name must not be null");
    this.pattern = Objects.requireNonNull(builder.pattern, "pattern must not be null");
    this.procedure = Objects.requireNonNull(builder.procedure, "procedure must not be null");
    this.toolName = builder.toolName;
    this.version = builder.version != null ? builder.version : "0.1.0";
    this.createdAt = builder.createdAt > 0 ? builder.createdAt : System.currentTimeMillis();
    this.updatedAt = builder.updatedAt > 0 ? builder.updatedAt : this.createdAt;
  }

  public String sopId() {
    return sopId;
  }

  public String name() {
    return name;
  }

  public String pattern() {
    return pattern;
  }

  public String procedure() {
    return procedure;
  }

  public String toolName() {
    return toolName;
  }

  public String version() {
    return version;
  }

  public long createdAt() {
    return createdAt;
  }

  public long updatedAt() {
    return updatedAt;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof SOP sop)) return false;
    return sopId.equals(sop.sopId);
  }

  @Override
  public int hashCode() {
    return sopId.hashCode();
  }

  @Override
  public String toString() {
    return "SOP{id='%s', name='%s', tool='%s', version='%s'}"
        .formatted(sopId, name, toolName, version);
  }

  // ---------------------------------------------------------------

  public static final class Builder {
    private String sopId;
    private String name;
    private String pattern;
    private String procedure;
    private String toolName;
    private String version;
    private long createdAt;
    private long updatedAt;

    private Builder() {}

    public Builder sopId(String sopId) {
      this.sopId = sopId;
      return this;
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder pattern(String pattern) {
      this.pattern = pattern;
      return this;
    }

    public Builder procedure(String procedure) {
      this.procedure = procedure;
      return this;
    }

    public Builder toolName(String toolName) {
      this.toolName = toolName;
      return this;
    }

    public Builder version(String version) {
      this.version = version;
      return this;
    }

    public Builder createdAt(long createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    public Builder updatedAt(long updatedAt) {
      this.updatedAt = updatedAt;
      return this;
    }

    public SOP build() {
      return new SOP(this);
    }
  }
}
