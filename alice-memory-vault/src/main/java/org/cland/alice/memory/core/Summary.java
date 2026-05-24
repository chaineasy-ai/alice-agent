package org.cland.alice.memory.core;

import java.util.List;
import java.util.Objects;

/**
 * 记忆摘要，由 MemorySummarizer 从原始会话 Trace 中提炼产生。
 *
 * <p>包含从交互中萃取的事实（Facts）和成功模式（Success Patterns）， 分别用于填充 SemanticVault 和 ProceduralVault。
 */
public final class Summary {

  private final String sessionId;
  private final List<String> facts;
  private final List<String> successPatterns;
  private final int stepCount;
  private final long createdAt;

  private Summary(Builder builder) {
    this.sessionId = Objects.requireNonNull(builder.sessionId, "sessionId must not be null");
    this.facts = builder.facts != null ? List.copyOf(builder.facts) : List.of();
    this.successPatterns =
        builder.successPatterns != null ? List.copyOf(builder.successPatterns) : List.of();
    this.stepCount = builder.stepCount;
    this.createdAt = builder.createdAt > 0 ? builder.createdAt : System.currentTimeMillis();
  }

  public String sessionId() {
    return sessionId;
  }

  public List<String> facts() {
    return facts;
  }

  public List<String> successPatterns() {
    return successPatterns;
  }

  public int stepCount() {
    return stepCount;
  }

  public long createdAt() {
    return createdAt;
  }

  public boolean isEmpty() {
    return facts.isEmpty() && successPatterns.isEmpty();
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String toString() {
    return "Summary{sessionId='%s', facts=%d, patterns=%d, steps=%d}"
        .formatted(sessionId, facts.size(), successPatterns.size(), stepCount);
  }

  // ---------------------------------------------------------------

  public static final class Builder {
    private String sessionId;
    private List<String> facts;
    private List<String> successPatterns;
    private int stepCount;
    private long createdAt;

    private Builder() {}

    public Builder sessionId(String sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public Builder facts(List<String> facts) {
      this.facts = facts;
      return this;
    }

    public Builder successPatterns(List<String> successPatterns) {
      this.successPatterns = successPatterns;
      return this;
    }

    public Builder stepCount(int stepCount) {
      this.stepCount = stepCount;
      return this;
    }

    public Builder createdAt(long createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    public Summary build() {
      return new Summary(this);
    }
  }
}
