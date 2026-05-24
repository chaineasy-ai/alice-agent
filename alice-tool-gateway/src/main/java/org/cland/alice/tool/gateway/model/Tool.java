package org.cland.alice.tool.gateway.model;

import java.util.Map;
import java.util.Objects;

/**
 * Abstract tool descriptor — describes a callable tool by its name, description, and input schema.
 *
 * <p>This is the universal tool representation used across the agent system. It is produced by:
 *
 * <ul>
 *   <li>{@code alice-env-adapter} — when discovering tools from MCP servers via {@code tools/list}
 *   <li>Java annotation scanning — when registering {@code @AgentTool} annotated methods
 * </ul>
 *
 * <p>Once obtained, these descriptors are converted into {@link
 * org.cland.alice.tool.gateway.metadata.ToolMetadata} and registered into the {@link
 * org.cland.alice.tool.gateway.ToolRegistry} for runtime execution.
 */
public final class Tool {

  private final String name;
  private final String description;
  private final Map<String, Object> inputSchema;

  private Tool(Builder builder) {
    this.name = Objects.requireNonNull(builder.name, "name must not be null");
    this.description = builder.description;
    this.inputSchema = builder.inputSchema != null ? Map.copyOf(builder.inputSchema) : Map.of();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static Tool of(String name, String description) {
    return builder().name(name).description(description).build();
  }

  // ========== Getters ==========

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public Map<String, Object> inputSchema() {
    return inputSchema;
  }

  @Override
  public String toString() {
    return "Tool{name='" + name + "'}";
  }

  // ========== Builder ==========

  public static final class Builder {
    private String name;
    private String description;
    private Map<String, Object> inputSchema;

    private Builder() {}

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder inputSchema(Map<String, Object> inputSchema) {
      this.inputSchema = inputSchema;
      return this;
    }

    public Tool build() {
      return new Tool(this);
    }
  }
}
