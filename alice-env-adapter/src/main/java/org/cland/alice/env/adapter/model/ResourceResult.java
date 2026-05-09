package org.cland.alice.env.adapter.model;

import java.util.Map;
import java.util.Objects;

/**
 * Result of reading a resource from an MCP server.
 *
 * <p>Contains the resource content (as text or structured data), metadata, and the MIME type of the
 * resource.
 */
public final class ResourceResult {

  private final String uri;
  private final String mimeType;
  private final String text;
  private final Map<String, Object> data;
  private final long sizeBytes;

  private ResourceResult(Builder builder) {
    this.uri = Objects.requireNonNull(builder.uri, "uri must not be null");
    this.mimeType = Objects.requireNonNull(builder.mimeType, "mimeType must not be null");
    this.text = builder.text;
    this.data = builder.data != null ? Map.copyOf(builder.data) : Map.of();
    this.sizeBytes = builder.sizeBytes;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Quick factory for a text resource. */
  public static ResourceResult text(String uri, String mimeType, String text) {
    return builder()
        .uri(uri)
        .mimeType(mimeType)
        .text(text)
        .sizeBytes(text != null ? text.length() : 0)
        .build();
  }

  // ========== Getters ==========

  public String uri() {
    return uri;
  }

  public String mimeType() {
    return mimeType;
  }

  public String text() {
    return text;
  }

  public Map<String, Object> data() {
    return data;
  }

  public long sizeBytes() {
    return sizeBytes;
  }

  @Override
  public String toString() {
    return "ResourceResult{uri='" + uri + "', mimeType='" + mimeType + "', size=" + sizeBytes + "}";
  }

  // ========== Builder ==========

  public static final class Builder {
    private String uri;
    private String mimeType;
    private String text;
    private Map<String, Object> data;
    private long sizeBytes;

    private Builder() {}

    public Builder uri(String uri) {
      this.uri = uri;
      return this;
    }

    public Builder mimeType(String mimeType) {
      this.mimeType = mimeType;
      return this;
    }

    public Builder text(String text) {
      this.text = text;
      return this;
    }

    public Builder data(Map<String, Object> data) {
      this.data = data;
      return this;
    }

    public Builder sizeBytes(long sizeBytes) {
      this.sizeBytes = sizeBytes;
      return this;
    }

    public ResourceResult build() {
      return new ResourceResult(this);
    }
  }
}
