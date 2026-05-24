package org.cland.alice.memory.core;

import java.util.Objects;

/**
 * 语义知识条目，存储在 SemanticVault 中的基本单元。
 *
 * <p>包含唯一 ID、内容文本、来源标签、以及可选的向量嵌入。
 */
public final class Knowledge {

  private final String knowledgeId;
  private final String content;
  private final String source;
  private final String collection;
  private final long createdAt;
  private final float[] embedding;

  private Knowledge(Builder builder) {
    this.knowledgeId = Objects.requireNonNull(builder.knowledgeId, "knowledgeId must not be null");
    this.content = Objects.requireNonNull(builder.content, "content must not be null");
    this.source = builder.source;
    this.collection = builder.collection;
    this.createdAt = builder.createdAt > 0 ? builder.createdAt : System.currentTimeMillis();
    this.embedding = builder.embedding; // nullable
  }

  public String knowledgeId() {
    return knowledgeId;
  }

  public String content() {
    return content;
  }

  public String source() {
    return source;
  }

  public String collection() {
    return collection;
  }

  public long createdAt() {
    return createdAt;
  }

  public float[] embedding() {
    return embedding;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Knowledge knowledge)) return false;
    return knowledgeId.equals(knowledge.knowledgeId);
  }

  @Override
  public int hashCode() {
    return knowledgeId.hashCode();
  }

  @Override
  public String toString() {
    return "Knowledge{id='%s', source='%s', collection='%s', len=%d}"
        .formatted(knowledgeId, source, collection, content.length());
  }

  // ---------------------------------------------------------------

  public static final class Builder {
    private String knowledgeId;
    private String content;
    private String source;
    private String collection;
    private long createdAt;
    private float[] embedding;

    private Builder() {}

    public Builder knowledgeId(String knowledgeId) {
      this.knowledgeId = knowledgeId;
      return this;
    }

    public Builder content(String content) {
      this.content = content;
      return this;
    }

    public Builder source(String source) {
      this.source = source;
      return this;
    }

    public Builder collection(String collection) {
      this.collection = collection;
      return this;
    }

    public Builder createdAt(long createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    public Builder embedding(float[] embedding) {
      this.embedding = embedding;
      return this;
    }

    public Knowledge build() {
      return new Knowledge(this);
    }
  }
}
