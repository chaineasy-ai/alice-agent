package org.cland.alice.core.agent.wal;

/**
 * Enumerated span categories for the {@link MetadataKeys#SPAN_TYPE} metadata key.
 *
 * <p>Each value maps to a specific execution span type in the WAL specification (§2.3).
 */
public enum SpanType {
  USER_INPUT("user_input"),
  SYSTEM_PROMPT_INIT("system_prompt_init"),
  HISTORY_COMPACT("history_compact"),
  LLM_THINK("llm_think"),
  LLM_FINAL_RESPONSE("llm_final_response"),
  SUB_AGENT_CONTAINER("sub_agent_container"),
  LLM_SUB_RESPONSE("llm_sub_response"),
  TOOL_CALL("tool_call"),
  TOOL_CALL_RESULT("tool_call_result"),
  TOOL_REGISTER("tool_register");

  private final String value;

  SpanType(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }
}
