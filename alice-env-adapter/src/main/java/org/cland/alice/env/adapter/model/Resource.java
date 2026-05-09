package org.cland.alice.env.adapter.model;

import java.util.Objects;

/**
 * MCP 2.0 Resource descriptor, representing an external resource that can be read or subscribed to
 * via an MCP server.
 *
 * <p>Resources are the primary mechanism for the environment to expose stateful data (files,
 * database entries, API responses) to the Agent.
 */
public final class Resource {

  private final String uri;
  private final String mimeType;
  private final String name;
  private final String description;

  private Resource(Builder builder) {
    this.uri = Objects.requireNonNull(builder.uri, "uri must not be null");
    this.mimeType = builder.mimeType;
    this.name = builder.name;
    this.description = builder.description;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Resource of(String uri, String mimeType) {
    return builder().uri(uri).mimeType(mimeType).build();
  }

  // ========== Getters ==========

  public String uri() {
    return uri;
  }

  public String mimeType() {
    return mimeType;
  }

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  @Override
  public String toString() {
    return "Resource{uri='" + uri + "', mimeType='" + mimeType + "'}";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Resource resource)) return false;
    return uri.equals(resource.uri);
  }

  @Override
  public int hashCode() {
    return uri.hashCode();
  }

  // ========== Builder ==========

  public static final class Builder {
    private String uri;
    private String mimeType;
    private String name;
    private String description;

    private Builder() {}

    public Builder uri(String uri) {
      this.uri = uri;
      return this;
    }

    public Builder mimeType(String mimeType) {
      this.mimeType = mimeType;
      return this;
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Resource build() {
      return new Resource(this);
    }
  }
}
