package org.cland.alice.core.agent.wal;

/**
 * Standardized metadata key constants for {@link RawMessage#metadata()}.
 *
 * <p>Per the WAL specification (§2.3), all tracing, training, and sub-agent identifiers are
 * encapsulated within metadata only — no new top-level RawMessage fields shall be introduced.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * RawMessage msg = RawMessage.create(...,
 *     Map.of(
 *         MetadataKeys.TRACE_ID, "turn_t001",
 *         MetadataKeys.SPAN_TYPE, SpanType.LLM_FINAL_RESPONSE,
 *         MetadataKeys.IS_USER_VISIBLE, true
 *     ));
 * }</pre>
 */
public final class MetadataKeys {

  private MetadataKeys() {}

  // ============================================================
  // Traceability
  // ============================================================

  /** Root trace ID for the current dialogue turn (unique turn identifier). */
  public static final String TRACE_ID = "traceId";

  /** Unique identifier for the current execution span. */
  public static final String SPAN_ID = "spanId";

  /**
   * Enumerated span category. Values defined in {@link SpanType}. One of: user_input |
   * system_prompt_init | history_compact | llm_think | llm_final_response | sub_agent_container |
   * llm_sub_response | tool_call
   */
  public static final String SPAN_TYPE = "spanType";

  /**
   * Parent span ID for reconstructing nested sub-agent call trees. Empty for root spans. For
   * compact messages, points to the root spanId of the triggering trace.
   */
  public static final String PARENT_SPAN_ID = "parentSpanId";

  // ============================================================
  // Training & Visibility
  // ============================================================

  /**
   * Boolean flag: true = rendered to end users, eligible for primary SFT assistant samples. false =
   * intermediate reasoning, sub-agent internal output, or compressed summaries (observability or
   * CoT fine-tuning only).
   */
  public static final String IS_USER_VISIBLE = "isUserVisible";

  // ============================================================
  // Sub-Agent
  // ============================================================

  /** Private sub-agent session tag; present exclusively on sub-agent-related records. */
  public static final String SUB_AGENT_LOCAL_SESSION_ID = "subAgentLocalSessionId";

  /** Human-readable sub-agent label, paired with subAgentLocalSessionId. */
  public static final String SUB_AGENT_NAME = "subAgentName";

  // ============================================================
  // Token Metrics
  // ============================================================

  /** Input token consumption for LLM invocations. */
  public static final String TOKEN_INPUT = "token_input";

  /** Output token consumption for LLM invocations. */
  public static final String TOKEN_OUTPUT = "token_output";
}
