package org.cland.alice.core.agent.wal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WAL Appender — Write-Ahead Log writer.
 *
 * <p>Responsible for sequentially appending RawMessage records to the WAL store during Agent
 * runtime. Provides streaming append, batch flush, and message ID allocation capabilities.
 *
 * <p>Message IDs are guaranteed to be strictly monotonically increasing within each session. Writes
 * are immediately readable (Read-Your-Writes).
 *
 * <p>Per the WAL specification (§3.3), streaming partial fragments must never be persisted — only
 * fully assembled complete payloads are appended.
 */
public final class WalAppender {

  private static final Logger log = LoggerFactory.getLogger(WalAppender.class);

  private final WalStore store;

  public WalAppender(WalStore store) {
    this.store = Objects.requireNonNull(store, "store must not be null");
  }

  // ============================================================
  // Single Append (Metadata-Aware)
  // ============================================================

  /**
   * Appends a system message with metadata.
   *
   * @param sessionId session identifier
   * @param content system message content
   * @param metadata extended metadata (trace, span, etc.)
   * @return assigned message ID
   */
  public long appendSystem(String sessionId, String content, Map<String, Object> metadata) {
    RawMessage msg = RawMessage.system(0, sessionId, content, metadata);
    long id = store.appendMessage(msg);
    log.debug("Appended SYSTEM message id={} session={}", id, sessionId);
    return id;
  }

  /** Appends a system message (no metadata). */
  public long appendSystem(String sessionId, String content) {
    return appendSystem(sessionId, content, Map.of());
  }

  /**
   * Appends a user message with metadata.
   *
   * @param sessionId session identifier
   * @param content user input
   * @param metadata extended metadata (must include traceId, spanType=user_input,
   *     isUserVisible=true)
   * @return assigned message ID
   */
  public long appendUser(String sessionId, String content, Map<String, Object> metadata) {
    RawMessage msg = RawMessage.user(0, sessionId, content, metadata);
    long id = store.appendMessage(msg);
    log.debug("Appended USER message id={} session={}", id, sessionId);
    return id;
  }

  /** Appends a user message (no metadata). */
  public long appendUser(String sessionId, String content) {
    return appendUser(sessionId, content, Map.of());
  }

  /**
   * Appends a user message with name and metadata.
   *
   * @param sessionId session identifier
   * @param content user input
   * @param name user identifier name
   * @param metadata extended metadata
   * @return assigned message ID
   */
  public long appendUser(
      String sessionId, String content, String name, Map<String, Object> metadata) {
    RawMessage msg = RawMessage.userWithName(0, sessionId, content, name, metadata);
    long id = store.appendMessage(msg);
    log.debug("Appended USER message id={} session={} name={}", id, sessionId, name);
    return id;
  }

  /** Appends a user message with name (no metadata). */
  public long appendUser(String sessionId, String content, String name) {
    return appendUser(sessionId, content, name, Map.of());
  }

  /**
   * Appends an assistant message (plaintext reply) with metadata.
   *
   * @param sessionId session identifier
   * @param content assistant reply content
   * @param metadata extended metadata (spanType, isUserVisible, etc.)
   * @return assigned message ID
   */
  public long appendAssistant(String sessionId, String content, Map<String, Object> metadata) {
    RawMessage msg = RawMessage.assistant(0, sessionId, content, metadata);
    long id = store.appendMessage(msg);
    log.debug("Appended ASSISTANT message id={} session={}", id, sessionId);
    return id;
  }

  /** Appends an assistant message (no metadata). */
  public long appendAssistant(String sessionId, String content) {
    return appendAssistant(sessionId, content, Map.of());
  }

  /**
   * Appends an assistant message (tool call instructions) with metadata.
   *
   * @param sessionId session identifier
   * @param toolCalls tool call list
   * @param metadata extended metadata (traceId, spanId, spanType, isUserVisible, etc.)
   * @return assigned message ID
   */
  public long appendAssistantToolCalls(
      String sessionId, List<ToolCall> toolCalls, Map<String, Object> metadata) {
    RawMessage msg = RawMessage.assistantWithToolCalls(0, sessionId, toolCalls, metadata);
    long id = store.appendMessage(msg);
    log.debug(
        "Appended ASSISTANT(tool_calls) message id={} session={} calls={}",
        id,
        sessionId,
        toolCalls.size());
    return id;
  }

  /** Appends an assistant message (tool calls, no metadata). */
  public long appendAssistantToolCalls(String sessionId, List<ToolCall> toolCalls) {
    return appendAssistantToolCalls(sessionId, toolCalls, Map.of());
  }

  /**
   * Appends a tool result message with metadata.
   *
   * @param sessionId session identifier
   * @param toolCallId paired tool call ID
   * @param content tool execution result content
   * @param metadata extended metadata (traceId, spanType=tool_call, isUserVisible, etc.)
   * @return assigned message ID
   */
  public long appendToolResult(
      String sessionId, String toolCallId, String content, Map<String, Object> metadata) {
    RawMessage msg = RawMessage.toolResult(0, sessionId, toolCallId, content, metadata);
    long id = store.appendMessage(msg);
    log.debug("Appended TOOL message id={} session={} toolCallId={}", id, sessionId, toolCallId);
    return id;
  }

  /** Appends a tool result message (no metadata). */
  public long appendToolResult(String sessionId, String toolCallId, String content) {
    return appendToolResult(sessionId, toolCallId, content, Map.of());
  }

  /**
   * Appends a compact summary message with metadata.
   *
   * <p>Per the WAL specification (§3.5), each compact record must have:
   *
   * <ul>
   *   <li>{@code spanType=history_compact}
   *   <li>{@code isUserVisible=false}
   *   <li>{@code parentSpanId} set to the root spanId of the triggering trace
   * </ul>
   *
   * @param sessionId session identifier
   * @param content compressed summary text
   * @param metadata extended metadata (must include parentSpanId, traceId)
   * @return assigned message ID
   */
  public long appendCompact(String sessionId, String content, Map<String, Object> metadata) {
    RawMessage msg = RawMessage.compact(0, sessionId, content, metadata);
    long id = store.appendMessage(msg);
    log.debug("Appended COMPACT message id={} session={}", id, sessionId);
    return id;
  }

  /**
   * Appends a planner message with metadata.
   *
   * @param sessionId session identifier
   * @param content planner prompt or response text
   * @param metadata extended metadata (spanType=planner_prompt, etc.)
   * @return assigned message ID
   */
  public long appendPlanner(String sessionId, String content, Map<String, Object> metadata) {
    RawMessage msg =
        RawMessage.create(0, sessionId, "planner", content, null, null, null, 0, metadata);
    long id = store.appendMessage(msg);
    log.debug("Appended PLANNER message id={} session={}", id, sessionId);
    return id;
  }

  /**
   * Appends a tool_register message with metadata.
   *
   * @param sessionId session identifier
   * @param content serialized tool definitions JSON
   * @param metadata extended metadata (spanType=tool_register, etc.)
   * @return assigned message ID
   */
  public long appendToolRegister(String sessionId, String content, Map<String, Object> metadata) {
    RawMessage msg = RawMessage.toolRegister(0, sessionId, content, metadata);
    long id = store.appendMessage(msg);
    log.debug("Appended TOOL_REGISTER message id={} session={}", id, sessionId);
    return id;
  }

  // ============================================================
  // Batch Append
  // ============================================================

  /**
   * Appends multiple messages in batch.
   *
   * @param messages messages to append
   * @return last message ID
   */
  public long appendBatch(List<RawMessage> messages) {
    if (messages == null || messages.isEmpty()) return -1;
    long lastId = store.appendMessages(messages);
    log.debug("Batch appended {} messages, lastId={}", messages.size(), lastId);
    return lastId;
  }

  // ============================================================
  // Read Operations
  // ============================================================

  /** Returns all messages for a session. */
  public List<RawMessage> getAllMessages(String sessionId) {
    return store.getAllMessages(sessionId);
  }

  /** Returns messages after a given ID (delta read). */
  public List<RawMessage> getMessagesAfter(String sessionId, long afterId) {
    return store.getMessagesAfter(sessionId, afterId, 0);
  }

  /** Returns the message count for a session. */
  public int messageCount(String sessionId) {
    return store.messageCount(sessionId);
  }

  // ============================================================
  // Message Chain Validation
  // ============================================================

  /**
   * Validates message chain integrity for a session. Checks that every assistant.tool_calls has a
   * corresponding tool response.
   *
   * @param sessionId session identifier
   * @return validation result describing missing pairings
   */
  public LinkageValidation validateLinkage(String sessionId) {
    var msgs = store.getAllMessages(sessionId);
    var missing = new java.util.ArrayList<String>();

    for (RawMessage msg : msgs) {
      if ("assistant".equals(msg.role()) && msg.toolCalls() != null) {
        for (ToolCall tc : msg.toolCalls()) {
          boolean found =
              msgs.stream()
                  .anyMatch(m -> "tool".equals(m.role()) && tc.id().equals(m.toolCallId()));
          if (!found) {
            missing.add(tc.id());
          }
        }
      }
    }

    return new LinkageValidation(msgs.size(), missing.size(), missing);
  }

  /** Message chain validation result. */
  public record LinkageValidation(
      int totalMessages, int missingToolCallResponses, List<String> missingToolCallIds) {

    public boolean isComplete() {
      return missingToolCallResponses == 0;
    }
  }
}
