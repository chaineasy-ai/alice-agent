package org.cland.alice.core.agent.wal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.cland.alice.core.agent.wal.RecoveryEngine.RecoveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WAL Session — facade integrating WAL + Checkpoint + Recovery into the Agent memory system.
 *
 * <p>Encapsulates {@link WalStore}, {@link WalAppender}, {@link CheckpointManager}, {@link
 * RecoveryEngine}, and {@link PromptMelter} to provide a unified dual-track WAL operation entry
 * point.
 *
 * <p>Relationship with AgentSession:
 *
 * <ul>
 *   <li>WalSession supplements AgentSession's short-term memory with crash recovery
 *   <li>AgentSession.getShortTerm() can be populated via WAL full replay
 *   <li>AgentSession.persist() can be implemented via WalAppender
 * </ul>
 *
 * <p>Each {@code WalSession} instance maintains a {@link SnowflakeIdGenerator} that generates
 * unique {@code spanId} values per message. A single {@code traceId} is generated per session
 * instance to tie all messages in the same dialogue turn together. For sub-agent traces, callers
 * should set {@code parentSpanId} explicitly via the metadata overload.
 */
public final class WalSession {

  private static final Logger log = LoggerFactory.getLogger(WalSession.class);

  private final WalStore store;
  private final WalAppender appender;
  private final CheckpointManager checkpointManager;
  private final RecoveryEngine recoveryEngine;
  private final PromptMelter promptMelter;
  private final SftDataExporter sftExporter;

  /** Snowflake generator for per-message spanId. */
  private final SnowflakeIdGenerator idGenerator;

  /** Trace ID for the current dialogue turn, generated once per WalSession instance. */
  private final String traceId;

  /** Current recovery result (set after restart). */
  private RecoveryResult lastRecoveryResult;

  public WalSession() {
    this(new InMemoryWalStore());
  }

  public WalSession(WalStore store) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.appender = new WalAppender(store);
    this.checkpointManager = new CheckpointManager(store, appender);
    this.recoveryEngine = new RecoveryEngine(store, checkpointManager);
    this.promptMelter = new PromptMelter(store);
    this.sftExporter = new SftDataExporter(store);
    this.idGenerator = SnowflakeIdGenerator.getInstance();
    this.traceId = SnowflakeIdGenerator.generateSessionId();
    this.lastRecoveryResult = null;
  }

  // ============================================================
  // WAL Append Operations (Metadata-Aware)
  // ============================================================

  /** Appends a system message with default spanType=system_prompt_init. */
  public long system(String sessionId, String content) {
    return system(sessionId, content, autoMetadata(SpanType.SYSTEM_PROMPT_INIT, false));
  }

  /** Appends a system message with metadata. */
  public long system(String sessionId, String content, Map<String, Object> metadata) {
    return appender.appendSystem(sessionId, content, metadata);
  }

  /** Appends a user message with default spanType=user_input. */
  public long user(String sessionId, String content) {
    return user(sessionId, content, autoMetadata(SpanType.USER_INPUT, true));
  }

  /** Appends a user message with metadata. */
  public long user(String sessionId, String content, Map<String, Object> metadata) {
    return appender.appendUser(sessionId, content, metadata);
  }

  /** Appends an assistant reply without metadata (caller should set spanType explicitly). */
  public long assistant(String sessionId, String content) {
    return appender.appendAssistant(sessionId, content);
  }

  /** Appends an assistant reply with metadata. */
  public long assistant(String sessionId, String content, Map<String, Object> metadata) {
    return appender.appendAssistant(sessionId, content, metadata);
  }

  /** Appends a chain-of-thought reasoning message with spanType=llm_think, userVisible=false. */
  public long think(String sessionId, String content) {
    return appender.appendAssistant(sessionId, content, autoMetadata(SpanType.LLM_THINK, false));
  }

  /** Appends a final assistant response with spanType=llm_final_response, userVisible=true. */
  public long finalAnswer(String sessionId, String content) {
    return appender.appendAssistant(
        sessionId, content, autoMetadata(SpanType.LLM_FINAL_RESPONSE, true));
  }

  /** Appends assistant tool calls with default spanType=tool_call, userVisible=false. */
  public long assistantToolCalls(String sessionId, List<ToolCall> toolCalls) {
    return assistantToolCalls(sessionId, toolCalls, autoMetadata(SpanType.TOOL_CALL, false));
  }

  /** Appends assistant tool calls with metadata. */
  public long assistantToolCalls(
      String sessionId, List<ToolCall> toolCalls, Map<String, Object> metadata) {
    return appender.appendAssistantToolCalls(sessionId, toolCalls, metadata);
  }

  /** Appends a tool execution result with default spanType=tool_call_result. */
  public long toolResult(String sessionId, String toolCallId, String content) {
    return toolResult(
        sessionId, toolCallId, content, autoMetadata(SpanType.TOOL_CALL_RESULT, true));
  }

  /** Appends a tool execution result with metadata. */
  public long toolResult(
      String sessionId, String toolCallId, String content, Map<String, Object> metadata) {
    return appender.appendToolResult(sessionId, toolCallId, content, metadata);
  }

  /** Appends a tool_register message with default spanType=tool_register, userVisible=false. */
  public long toolRegister(String sessionId, String content) {
    return appender.appendToolRegister(
        sessionId, content, autoMetadata(SpanType.TOOL_REGISTER, false));
  }

  /** Appends a tool_register message with metadata. */
  public long toolRegister(String sessionId, String content, Map<String, Object> metadata) {
    return appender.appendToolRegister(sessionId, content, metadata);
  }

  /**
   * Appends a planner prompt message (the input sent to the intent classification model).
   *
   * @param sessionId session identifier
   * @param promptText the rendered planner prompt
   * @param metadata additional metadata
   * @return assigned message ID
   */
  public long plannerPrompt(String sessionId, String promptText, Map<String, Object> metadata) {
    var meta = new java.util.LinkedHashMap<String, Object>();
    meta.put("spanType", "planner_prompt");
    meta.put("isUserVisible", false);
    if (metadata != null) meta.putAll(metadata);
    return appender.appendPlanner(sessionId, promptText, Map.copyOf(meta));
  }

  /**
   * Appends a planner intent message (the classification result from the model).
   *
   * @param sessionId session identifier
   * @param rawResponse the raw text returned by the intent classification model
   * @param intent the parsed Plan.Intent name
   * @param metadata additional metadata
   * @return assigned message ID
   */
  public long plannerIntent(
      String sessionId, String rawResponse, String intent, Map<String, Object> metadata) {
    var meta = new java.util.LinkedHashMap<String, Object>();
    meta.put("spanType", "planner_intent");
    meta.put("intent", intent);
    meta.put("isUserVisible", false);
    if (metadata != null) meta.putAll(metadata);
    return appender.appendPlanner(sessionId, rawResponse, Map.copyOf(meta));
  }

  /**
   * Appends a compact summary message with proper metadata.
   *
   * <p>Per the WAL specification (§3.5), the compact record automatically sets:
   *
   * <ul>
   *   <li>spanType = history_compact
   *   <li>isUserVisible = false
   * </ul>
   *
   * @param sessionId session identifier
   * @param content compressed summary text
   * @param traceId the triggering trace's traceId
   * @param rootSpanId the root spanId of the trace that initiated compression
   * @param additionalMetadata any additional metadata (may override defaults)
   * @return assigned message ID
   */
  public long compact(
      String sessionId,
      String content,
      String traceId,
      String rootSpanId,
      Map<String, Object> additionalMetadata) {
    var metadata = new java.util.LinkedHashMap<String, Object>();
    if (additionalMetadata != null) {
      metadata.putAll(additionalMetadata);
    }
    // Apply spec-mandated metadata
    metadata.putIfAbsent(MetadataKeys.SPAN_TYPE, SpanType.HISTORY_COMPACT.value());
    metadata.putIfAbsent(MetadataKeys.IS_USER_VISIBLE, false);
    metadata.putIfAbsent(MetadataKeys.TRACE_ID, traceId);
    if (rootSpanId != null) {
      metadata.putIfAbsent(MetadataKeys.PARENT_SPAN_ID, rootSpanId);
    }
    return appender.appendCompact(sessionId, content, Map.copyOf(metadata));
  }

  /** Appends a compact summary message (simple version, no metadata). */
  public long compact(String sessionId, String content) {
    return compact(sessionId, content, null, null, Map.of());
  }

  // ============================================================
  // Checkpoint Operations
  // ============================================================

  /** Triggers a checkpoint at ReAct cycle end. */
  public long checkpointOnReActEnd(
      String sessionId, String stateNode, Map<String, Object> variables, String planSnapshot) {
    return checkpointManager.onReActCycleEnd(sessionId, stateNode, variables, planSnapshot);
  }

  /** Creates default metadata with spanType, isUserVisible, traceId, and spanId. */
  private Map<String, Object> autoMetadata(SpanType spanType, boolean userVisible) {
    var meta = new java.util.LinkedHashMap<String, Object>();
    meta.put(MetadataKeys.SPAN_TYPE, spanType.value());
    meta.put(MetadataKeys.IS_USER_VISIBLE, userVisible);
    meta.put(MetadataKeys.TRACE_ID, traceId);
    meta.put(MetadataKeys.SPAN_ID, Long.toHexString(idGenerator.nextId()));
    return Map.copyOf(meta);
  }

  /** Creates default metadata with an optional parentSpanId for sub-agent traces. */
  private Map<String, Object> autoMetadata(
      SpanType spanType, boolean userVisible, String parentSpanId) {
    var meta = new java.util.LinkedHashMap<String, Object>();
    meta.put(MetadataKeys.SPAN_TYPE, spanType.value());
    meta.put(MetadataKeys.IS_USER_VISIBLE, userVisible);
    meta.put(MetadataKeys.TRACE_ID, traceId);
    meta.put(MetadataKeys.SPAN_ID, Long.toHexString(idGenerator.nextId()));
    if (parentSpanId != null) {
      meta.put(MetadataKeys.PARENT_SPAN_ID, parentSpanId);
    }
    return Map.copyOf(meta);
  }

  /** Triggers a checkpoint on user input. */
  public long checkpointOnUserInput(String sessionId) {
    return checkpointManager.onUserInput(sessionId);
  }

  /** Triggers a checkpoint on tool return. */
  public long checkpointOnToolReturn(String sessionId, String toolName, boolean success) {
    return checkpointManager.onToolReturn(sessionId, toolName, success);
  }

  /** Triggers a checkpoint on error. */
  public long checkpointOnError(String sessionId, String errorNode, String errorMsg) {
    return checkpointManager.onError(sessionId, errorNode, errorMsg);
  }

  // ============================================================
  // Recovery
  // ============================================================

  /**
   * Executes the recovery process (call after restart).
   *
   * @param sessionId session identifier
   * @return recovery result
   */
  public RecoveryResult recover(String sessionId) {
    RecoveryResult result = recoveryEngine.recover(sessionId);
    this.lastRecoveryResult = result;
    log.info("[WalSession] Recovery result: {} (session={})", result.summary(), sessionId);
    return result;
  }

  /** Returns the last recovery result. */
  public Optional<RecoveryResult> lastRecoveryResult() {
    return Optional.ofNullable(lastRecoveryResult);
  }

  // ============================================================
  // Prompt Melting
  // ============================================================

  /**
   * Melts the three-segment prompt.
   *
   * @param sessionId session identifier
   * @param staticTrunk static trunk (System Prompt + SOP + Tool Schemas)
   * @return melted prompt
   */
  public PromptMelter.MeltedPrompt melt(String sessionId, String staticTrunk) {
    return promptMelter.melt(sessionId, staticTrunk);
  }

  // ============================================================
  // SFT Export
  // ============================================================

  /**
   * Exports SFT training samples for a session.
   *
   * @param sessionId the session to export
   * @param format 1 = Native Embedded Role, 2 = Top-Level Independent Field
   * @param scenario "A" = Standard Dialogue, "B" = Tool &amp; CoT
   * @return list of SFT samples
   */
  public List<SftDataExporter.SftSample> exportSft(String sessionId, int format, String scenario) {
    return sftExporter.exportSession(sessionId, format, scenario);
  }

  // ============================================================
  // Message Query
  // ============================================================

  /** Returns all messages for a session. */
  public List<RawMessage> getAllMessages(String sessionId) {
    return appender.getAllMessages(sessionId);
  }

  /** Returns the message count for a session. */
  public int messageCount(String sessionId) {
    return appender.messageCount(sessionId);
  }

  /** Validates message chain integrity. */
  public WalAppender.LinkageValidation validateLinkage(String sessionId) {
    return appender.validateLinkage(sessionId);
  }

  /** Returns the latest checkpoint. */
  public Optional<Checkpoint> getLatestCheckpoint(String sessionId) {
    return checkpointManager.getLatestCheckpoint(sessionId);
  }

  // ============================================================
  // Lifecycle
  // ============================================================

  /** Clears all data for a session. */
  public void clearSession(String sessionId) {
    store.clearSession(sessionId);
    checkpointManager.resetState();
    log.debug("[WalSession] Cleared session={}", sessionId);
  }

  /** Clears all data. */
  public void clearAll() {
    store.clearAll();
    checkpointManager.resetState();
    lastRecoveryResult = null;
    log.debug("[WalSession] Cleared all sessions");
  }

  // ============================================================
  // Accessors
  // ============================================================

  /** Returns the underlying WalStore. */
  public WalStore store() {
    return store;
  }

  /** Returns the WalAppender. */
  public WalAppender appender() {
    return appender;
  }

  /** Returns the CheckpointManager. */
  public CheckpointManager checkpointManager() {
    return checkpointManager;
  }
}
