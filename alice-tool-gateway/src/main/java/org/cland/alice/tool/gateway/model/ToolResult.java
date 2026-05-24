package org.cland.alice.tool.gateway.model;

import java.util.Map;
import java.util.Objects;

/**
 * Tool call result — represents the response from invoking a tool, regardless of the invocation
 * mechanism (direct Java method call, MCP {@code tools/call}, HTTP, etc.).
 *
 * <p>This is the universal result type used across the agent system. It carries:
 *
 * <ul>
 *   <li>{@code status} — SUCCESS, ERROR, or TIMEOUT
 *   <li>{@code content} — structured key-value data
 *   <li>{@code text} — optional plain-text summary
 *   <li>{@code error} — error message if applicable
 * </ul>
 *
 * <p>Distinct from {@link org.cland.alice.tool.gateway.engine.ToolResult} which is the
 * execution-engine-specific result with {@code summary} / {@code rawData} / {@code metadata}
 * fields. The model-level {@code ToolResult} is the MCP wire-format compatible representation.
 */
public final class ToolResult {

  /** Status of the tool call */
  public enum Status {
    SUCCESS,
    ERROR,
    TIMEOUT
  }

  private final Status status;
  private final Map<String, Object> content;
  private final String text;
  private final String error;
  private final boolean isError;

  private ToolResult(Builder builder) {
    this.status = Objects.requireNonNull(builder.status, "status must not be null");
    this.content = builder.content != null ? Map.copyOf(builder.content) : Map.of();
    this.text = builder.text;
    this.error = builder.error;
    this.isError = builder.isError;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Quick factory for a successful result with text content. */
  public static ToolResult success(String text) {
    return builder().status(Status.SUCCESS).text(text).build();
  }

  /** Quick factory for an error result. */
  public static ToolResult error(String message) {
    return builder().status(Status.ERROR).error(message).isError(true).build();
  }

  // ========== Getters ==========

  public Status status() {
    return status;
  }

  public Map<String, Object> content() {
    return content;
  }

  public String text() {
    return text;
  }

  public String error() {
    return error;
  }

  public boolean isError() {
    return isError;
  }

  /** Convenience: true if status is SUCCESS. */
  public boolean isSuccess() {
    return status == Status.SUCCESS;
  }

  @Override
  public String toString() {
    return "ToolResult{status=" + status + ", text='" + text + "'}";
  }

  // ========== Builder ==========

  public static final class Builder {
    private Status status;
    private Map<String, Object> content;
    private String text;
    private String error;
    private boolean isError;

    private Builder() {}

    public Builder status(Status status) {
      this.status = status;
      return this;
    }

    public Builder content(Map<String, Object> content) {
      this.content = content;
      return this;
    }

    public Builder text(String text) {
      this.text = text;
      return this;
    }

    public Builder error(String error) {
      this.error = error;
      return this;
    }

    public Builder isError(boolean isError) {
      this.isError = isError;
      return this;
    }

    public ToolResult build() {
      return new ToolResult(this);
    }
  }
}
