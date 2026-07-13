package org.cland.alice.core.agent.wal;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RAW Message Entity — single record in the WAL (Write-Ahead Log).
 *
 * <p>Follows the OpenAI Chat Completions message specification, supporting plaintext, multimodal,
 * and tool call scenarios. The Java Record type is natively immutable; defensive copies of mutable
 * collections are enforced at construction time to guarantee complete immutability after WAL
 * persistence.
 *
 * <p>Each RawMessage is the smallest atomic unit of Agent runtime interaction, appended to the WAL
 * via append-only writes.
 *
 * <p>Role Definitions:
 *
 * <ul>
 *   <li>{@code system} — System/developer message; defines Agent persona, rules, and global tool
 *       constraints
 *   <li>{@code user} — End-user input (plaintext or multimodal), marks the start of a new trace
 *       turn
 *   <li>{@code assistant} — Assistant response, chain-of-thought reasoning, tool call instructions,
 *       or sub-agent output
 *   <li>{@code tool} — Return payload from tool execution; paired with upstream assistant tool
 *       calls via toolCallId
 *   <li>{@code compact} — Compressed summary; condensed text for evicted historical dialogue turns,
 *       used internally only for inference and training
 * </ul>
 *
 * @param messageId Distributed session-wide unique ordered ID (Snowflake)
 * @param sessionId Top-level user session identifier
 * @param role Message role: system | user | assistant | tool | compact
 * @param content Plaintext payload; may be null if toolCalls is populated (assistant role only)
 * @param toolCalls List of tool call instructions (valid only for assistant roles)
 * @param toolCallId Pairing ID for tool return payloads (valid only for tool roles)
 * @param name Optional identifier to label primary or sub-agent identities
 * @param timestamp Millisecond-level persistence timestamp for fallback chronological sorting
 * @param metadata Extended metadata carrying trace identifiers, sub-agent tags, visibility flags,
 *     token consumption metrics, and other business attributes
 */
public record RawMessage(
    long messageId,
    String sessionId,
    String role,
    String content,
    List<ToolCall> toolCalls,
    String toolCallId,
    String name,
    long timestamp,
    Map<String, Object> metadata) {

  /** Valid role enumeration set. */
  public static final List<String> VALID_ROLES =
      List.of("system", "user", "assistant", "tool", "compact", "tool_register", "planner");

  public RawMessage {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(role, "role must not be null");
    if (!VALID_ROLES.contains(role)) {
      throw new IllegalArgumentException(
          "Invalid role: " + role + ". Must be one of " + VALID_ROLES);
    }
    if (content == null
        && (toolCalls == null || toolCalls.isEmpty())
        && !"tool".equals(role)
        && !"tool_register".equals(role)) {
      throw new IllegalArgumentException(
          "content must not be null when toolCalls is empty for role: " + role);
    }
    if ("tool".equals(role) && toolCallId == null) {
      throw new IllegalArgumentException("toolCallId must not be null for role: tool");
    }
    if (timestamp <= 0) {
      timestamp = System.currentTimeMillis();
    }
    if (metadata == null) {
      metadata = Map.of();
    }
  }

  // ========== Static Factory (Defensive Copies) ==========

  /**
   * Static factory method enforcing defensive immutable copies during construction.
   *
   * <p>All upstream services MUST use this factory to instantiate RawMessage; direct {@code new
   * RawMessage(...)} is permitted but the factory ensures full immutable copies for Map/List to
   * eliminate mutation risks after persistence.
   *
   * @return a new RawMessage with defensively copied collections
   */
  public static RawMessage create(
      long messageId,
      String sessionId,
      String role,
      String content,
      List<ToolCall> toolCalls,
      String toolCallId,
      String name,
      long timestamp,
      Map<String, Object> metadata) {
    // Defensive copy: preserve null semantics for toolCalls (null = no tool calls,
    // empty list = explicit no tool calls). The compact constructor distinguishes these.
    List<ToolCall> safeTools = toolCalls == null ? null : List.copyOf(toolCalls);
    Map<String, Object> safeMeta = metadata == null ? Map.of() : Map.copyOf(metadata);
    return new RawMessage(
        messageId, sessionId, role, content, safeTools, toolCallId, name, timestamp, safeMeta);
  }

  // ========== Metadata Helper Methods ==========

  /** Returns the traceId from metadata, or null if not present. */
  public String traceId() {
    Object val = metadata.get(MetadataKeys.TRACE_ID);
    return val instanceof String s ? s : null;
  }

  /** Returns the spanId from metadata, or null if not present. */
  public String spanId() {
    Object val = metadata.get(MetadataKeys.SPAN_ID);
    return val instanceof String s ? s : null;
  }

  /** Returns the spanType from metadata, or null if not present. */
  public String spanType() {
    Object val = metadata.get(MetadataKeys.SPAN_TYPE);
    return val instanceof String s ? s : null;
  }

  /** Returns the parentSpanId from metadata, or null if not present. */
  public String parentSpanId() {
    Object val = metadata.get(MetadataKeys.PARENT_SPAN_ID);
    return val instanceof String s ? s : null;
  }

  /** Returns the isUserVisible flag from metadata. Defaults to true if not explicitly set. */
  public boolean isUserVisible() {
    Object val = metadata.get(MetadataKeys.IS_USER_VISIBLE);
    if (val instanceof Boolean b) return b;
    return true; // default: visible
  }

  /** Returns the subAgentLocalSessionId from metadata, or null if not present. */
  public String subAgentLocalSessionId() {
    Object val = metadata.get(MetadataKeys.SUB_AGENT_LOCAL_SESSION_ID);
    return val instanceof String s ? s : null;
  }

  // ========== Convenience Factory Methods ==========

  /** Creates a system message with metadata. */
  public static RawMessage system(
      long messageId, String sessionId, String content, Map<String, Object> metadata) {
    return create(messageId, sessionId, "system", content, null, null, null, 0, metadata);
  }

  /** Creates a system message (no metadata). */
  public static RawMessage system(long messageId, String sessionId, String content) {
    return system(messageId, sessionId, content, Map.of());
  }

  /** Creates a user message with metadata. */
  public static RawMessage user(
      long messageId, String sessionId, String content, Map<String, Object> metadata) {
    return create(messageId, sessionId, "user", content, null, null, null, 0, metadata);
  }

  /** Creates a user message (no metadata). */
  public static RawMessage user(long messageId, String sessionId, String content) {
    return user(messageId, sessionId, content, Map.of());
  }

  /** Creates an assistant message (plaintext reply) with metadata. */
  public static RawMessage assistant(
      long messageId, String sessionId, String content, Map<String, Object> metadata) {
    return create(messageId, sessionId, "assistant", content, null, null, null, 0, metadata);
  }

  /** Creates an assistant message (no metadata). */
  public static RawMessage assistant(long messageId, String sessionId, String content) {
    return assistant(messageId, sessionId, content, Map.of());
  }

  /** Creates an assistant message (tool calls) with metadata. */
  public static RawMessage assistantWithToolCalls(
      long messageId, String sessionId, List<ToolCall> toolCalls, Map<String, Object> metadata) {
    return create(messageId, sessionId, "assistant", null, toolCalls, null, null, 0, metadata);
  }

  /** Creates an assistant message (tool calls, no metadata). */
  public static RawMessage assistantWithToolCalls(
      long messageId, String sessionId, List<ToolCall> toolCalls) {
    return assistantWithToolCalls(messageId, sessionId, toolCalls, Map.of());
  }

  /** Creates a tool result message with metadata. */
  public static RawMessage toolResult(
      long messageId,
      String sessionId,
      String toolCallId,
      String content,
      Map<String, Object> metadata) {
    return create(messageId, sessionId, "tool", content, null, toolCallId, null, 0, metadata);
  }

  /** Creates a tool result message (no metadata). */
  public static RawMessage toolResult(
      long messageId, String sessionId, String toolCallId, String content) {
    return toolResult(messageId, sessionId, toolCallId, content, Map.of());
  }

  /** Creates a user message with name and metadata. */
  public static RawMessage userWithName(
      long messageId, String sessionId, String content, String name, Map<String, Object> metadata) {
    return create(messageId, sessionId, "user", content, null, null, name, 0, metadata);
  }

  /** Creates a user message with name (no metadata). */
  public static RawMessage userWithName(
      long messageId, String sessionId, String content, String name) {
    return userWithName(messageId, sessionId, content, name, Map.of());
  }

  /** Creates a compact message with metadata. */
  public static RawMessage compact(
      long messageId, String sessionId, String content, Map<String, Object> metadata) {
    return create(messageId, sessionId, "compact", content, null, null, null, 0, metadata);
  }

  /** Creates a compact message (no metadata). */
  public static RawMessage compact(long messageId, String sessionId, String content) {
    return compact(messageId, sessionId, content, Map.of());
  }

  /** Creates a tool_register message with metadata. */
  public static RawMessage toolRegister(
      long messageId, String sessionId, String content, Map<String, Object> metadata) {
    return create(messageId, sessionId, "tool_register", content, null, null, null, 0, metadata);
  }

  // ========== Object ==========

  @Override
  public String toString() {
    return "RawMessage{id=%d, session='%s', role=%s, hasContent=%s, hasToolCalls=%s}"
        .formatted(
            messageId, sessionId, role, content != null, toolCalls != null && !toolCalls.isEmpty());
  }
}
