package org.cland.alice.core.agent.wal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SFT Training Data ETL Exporter.
 *
 * <p>Implements the WAL specification (§6) for exporting SFT training samples from WAL RawMessage
 * records. Supports two output formats and two filtering pipelines:
 *
 * <ul>
 *   <li><b>Scenario A</b> — Standard End-User Dialogue SFT
 *   <li><b>Scenario B</b> — Tool &amp; Multi-Agent CoT Fine-Tuning
 * </ul>
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Sliding-window multi-turn slicing (§6.4)
 *   <li>Compact backtracking via parentSpanId (§6.4.3)
 *   <li>Parallel tool record reordering (§6.5.2 / §3.4)
 *   <li>System prompt awareness with dirty data detection (§6.2)
 *   <li>Safe concatenation template for legacy formats (§6.3)
 * </ul>
 */
public final class SftDataExporter {

  private static final Logger log = LoggerFactory.getLogger(SftDataExporter.class);

  /** Default sliding window size (number of user-assistant pairs). */
  public static final int DEFAULT_WINDOW_SIZE = 5;

  /** The safe separator between system prompt and compact summary in legacy format. */
  public static final String CONTEXT_HISTORY_SEPARATOR =
      "\n\n===== CONTEXT HISTORY SUMMARY =====\n";

  public static final String CONTEXT_HISTORY_FOOTER = "\n===================================\n";

  // ============================================================
  // SFT Sample Record
  // ============================================================

  /**
   * A single SFT training sample, corresponding to one traceId.
   *
   * @param systemPrompt the active system prompt for this trace (may be null if dirty)
   * @param compactText the compact summary text (may be null)
   * @param messages the ordered list of training messages
   * @param traceId the associated trace identifier
   * @param isDirty true if this sample has missing required data (e.g., no system prompt)
   */
  public record SftSample(
      String systemPrompt,
      String compactText,
      List<Map<String, Object>> messages,
      String traceId,
      boolean isDirty) {

    /**
     * Exports to Format 1: Native Embedded Role. Compact exists as an independent entry in
     * messages.
     */
    public Map<String, Object> toFormat1() {
      var result = new LinkedHashMap<String, Object>();
      var msgs = new ArrayList<Map<String, Object>>();

      if (systemPrompt != null) {
        msgs.add(Map.of("role", "system", "content", systemPrompt));
      }
      if (compactText != null) {
        msgs.add(Map.of("role", "compact", "content", compactText));
      }
      msgs.addAll(messages);

      result.put("messages", msgs);
      return result;
    }

    /**
     * Exports to Format 2: Top-Level Independent Field (legacy compat). Uses the safe concatenation
     * template to prevent system prompt contamination.
     */
    public Map<String, Object> toFormat2() {
      var result = new LinkedHashMap<String, Object>();

      if (systemPrompt != null && compactText != null) {
        result.put("system_prompt", systemPrompt);
        result.put("history_compact_summary", compactText);
        // Apply safe concatenation template
        result.put(
            "_system_with_history",
            systemPrompt + CONTEXT_HISTORY_SEPARATOR + compactText + CONTEXT_HISTORY_FOOTER);
      } else if (systemPrompt != null) {
        result.put("system_prompt", systemPrompt);
      } else if (compactText != null) {
        result.put("history_compact_summary", compactText);
      }

      result.put("messages", messages);
      return result;
    }
  }

  // ============================================================
  // Window Slice
  // ============================================================

  /**
   * A sliding window slice of session messages associated with a single trace.
   *
   * @param associatedTraceId the trace ID anchoring this slice
   * @param records the message records in this window
   * @param windowStartIndex the starting index in the session message list
   * @param windowEndIndex the ending index in the session message list
   */
  public record WindowSlice(
      String associatedTraceId,
      List<RawMessage> records,
      int windowStartIndex,
      int windowEndIndex) {}

  // ============================================================
  // Main Export Method
  // ============================================================

  private final WalStore store;

  public SftDataExporter(WalStore store) {
    this.store = Objects.requireNonNull(store, "store must not be null");
  }

  /**
   * Exports SFT samples for a given session using default window size.
   *
   * @param sessionId the session to export
   * @param format 1 = Native Embedded Role, 2 = Top-Level Independent Field
   * @param scenario "A" = Standard Dialogue, "B" = Tool &amp; CoT
   * @return list of SFT samples (dirty samples marked with isDirty=true)
   */
  public List<SftSample> exportSession(String sessionId, int format, String scenario) {
    return exportSession(sessionId, format, scenario, DEFAULT_WINDOW_SIZE);
  }

  /**
   * Exports SFT samples for a given session.
   *
   * @param sessionId the session to export
   * @param format 1 = Native Embedded Role, 2 = Top-Level Independent Field
   * @param scenario "A" = Standard Dialogue, "B" = Tool &amp; CoT
   * @param windowSize number of user-assistant pairs per sliding window
   * @return list of SFT samples (dirty samples marked with isDirty=true)
   */
  public List<SftSample> exportSession(
      String sessionId, int format, String scenario, int windowSize) {

    // 1. Load all session records sorted by messageId
    List<RawMessage> allMessages =
        store.getAllMessages(sessionId).stream()
            .sorted(Comparator.comparingLong(RawMessage::messageId))
            .collect(Collectors.toList());

    if (allMessages.isEmpty()) {
      log.info("[SFT Exporter] No messages for session={}", sessionId);
      return List.of();
    }

    // 2. Single pass: build system, compact, tool source mappings
    String currentGlobalSystem = null;
    Map<String, String> traceSystemMap = new HashMap<>();
    Map<String, String> subAgentSystemMap = new HashMap<>();
    Map<String, RawMessage> traceCompactMap = new HashMap<>();
    Map<String, RawMessage> traceToolSourceMap = new HashMap<>();

    for (RawMessage msg : allMessages) {
      String traceId = msg.traceId();
      if ("system".equals(msg.role())) {
        String subSess = msg.subAgentLocalSessionId();
        if (subSess != null) {
          subAgentSystemMap.put(subSess, msg.content());
        } else {
          currentGlobalSystem = msg.content();
        }
        continue;
      }
      if ("user".equals(msg.role())) {
        if (traceId != null) {
          traceSystemMap.put(traceId, currentGlobalSystem);
        }
        continue;
      }
      if ("assistant".equals(msg.role()) && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
        if (traceId != null) {
          traceToolSourceMap.put(traceId, msg);
        }
        continue;
      }
      if ("compact".equals(msg.role())) {
        // Bind compact to its triggering trace via parentSpanId
        String parentSpanId = msg.parentSpanId();
        // Find the traceId whose root span matches this parentSpanId
        String triggeringTraceId = resolveTraceIdByParentSpan(allMessages, msg);
        if (triggeringTraceId != null) {
          traceCompactMap.put(triggeringTraceId, msg);
        } else if (traceId != null) {
          // Fallback: use the compact's own traceId
          traceCompactMap.put(traceId, msg);
        }
        continue;
      }
    }

    // 3. Generate sliding window slices
    List<WindowSlice> slices = generateSlidingWindowSlices(allMessages, windowSize);

    // 4. Build SFT samples from each slice
    List<SftSample> samples = new ArrayList<>();
    for (WindowSlice slice : slices) {
      String traceId = slice.associatedTraceId();
      String systemText = traceSystemMap.get(traceId);

      // Retrieve latest compact record anchored to this trace
      RawMessage compactMsg = traceCompactMap.get(traceId);
      String compactText = compactMsg == null ? null : compactMsg.content();

      // Reorder parallel async tool return records
      List<RawMessage> windowRecords = slice.records();
      RawMessage toolSourceAssistant = traceToolSourceMap.get(traceId);
      if (toolSourceAssistant != null) {
        windowRecords =
            ToolRecordReorderingUtil.reorderToolMessages(
                windowRecords, toolSourceAssistant.toolCalls());
      }

      // Filter messages based on scenario
      List<Map<String, Object>> trainingMessages;
      if ("B".equalsIgnoreCase(scenario)) {
        trainingMessages = filterScenarioB(windowRecords);
      } else {
        trainingMessages = filterScenarioA(windowRecords);
      }

      boolean isDirty = systemText == null;
      samples.add(new SftSample(systemText, compactText, trainingMessages, traceId, isDirty));
    }

    log.info(
        "[SFT Exporter] Exported {} samples for session={} (format={}, scenario={})",
        samples.size(),
        sessionId,
        format,
        scenario);
    return samples;
  }

  // ============================================================
  // Sliding Window Generation
  // ============================================================

  /**
   * Generates sliding window slices from the session message list. Each window is centered around a
   * user message trace boundary.
   *
   * <p>Per §6.4:
   *
   * <ul>
   *   <li>Single-turn-only export is prohibited
   *   <li>Each window retains only the most recent N groups of user-assistant pairs
   * </ul>
   */
  static List<WindowSlice> generateSlidingWindowSlices(
      List<RawMessage> allMessages, int windowSize) {

    if (allMessages == null || allMessages.isEmpty()) return List.of();
    if (windowSize < 1) windowSize = DEFAULT_WINDOW_SIZE;

    List<WindowSlice> slices = new ArrayList<>();

    // Find all user message indices (trace boundaries)
    List<Integer> userIndices = new ArrayList<>();
    for (int i = 0; i < allMessages.size(); i++) {
      if ("user".equals(allMessages.get(i).role())) {
        userIndices.add(i);
      }
    }

    if (userIndices.isEmpty()) {
      // No user messages — return entire session as one slice
      String firstTraceId = allMessages.get(0).traceId();
      slices.add(new WindowSlice(firstTraceId, List.copyOf(allMessages), 0, allMessages.size()));
      return slices;
    }

    for (int userIdx : userIndices) {
      String traceId = allMessages.get(userIdx).traceId();

      // Calculate window range: end at the next user message (or end of list)
      int windowEnd;
      int nextUserIdx = -1;
      for (int j = userIdx + 1; j < allMessages.size(); j++) {
        if ("user".equals(allMessages.get(j).role())) {
          nextUserIdx = j;
          break;
        }
      }
      windowEnd = nextUserIdx > 0 ? nextUserIdx : allMessages.size();

      // Window start: go back N user-assistant groups
      int groupsFound = 0;
      int windowStart = 0;
      for (int k = userIdx; k >= 0; k--) {
        if ("user".equals(allMessages.get(k).role())) {
          groupsFound++;
          if (groupsFound > windowSize) {
            windowStart = k + 1; // start after the (N+1)th user message
            break;
          }
        }
      }

      List<RawMessage> windowRecords = allMessages.subList(windowStart, windowEnd);
      slices.add(
          new WindowSlice(
              traceId != null ? traceId : "unknown",
              List.copyOf(windowRecords),
              windowStart,
              windowEnd));
    }

    return slices;
  }

  // ============================================================
  // Message Filtering
  // ============================================================

  /**
   * Scenario A: Standard End-User Dialogue SFT.
   *
   * <p>Filtered out: llm_think records, all sub-agent internal messages, full tool call interaction
   * chains. Retained: system, compact, user, and assistant records with isUserVisible=true
   * &amp;&amp; spanType=llm_final_response.
   */
  static List<Map<String, Object>> filterScenarioA(List<RawMessage> records) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (RawMessage msg : records) {
      String role = msg.role();
      String spanType = msg.spanType();

      // Skip intermediate reasoning and sub-agent internals
      if ("tool".equals(role)) continue;
      if (SpanType.LLM_THINK.value().equals(spanType)) continue;
      if (SpanType.SUB_AGENT_CONTAINER.value().equals(spanType)) continue;
      if (SpanType.LLM_SUB_RESPONSE.value().equals(spanType)) continue;
      if (SpanType.TOOL_CALL.value().equals(spanType)) continue;
      if (SpanType.HISTORY_COMPACT.value().equals(spanType)) continue;
      if (SpanType.SYSTEM_PROMPT_INIT.value().equals(spanType)) continue;

      // Assistant: only keep final responses that are user-visible
      if ("assistant".equals(role)) {
        if (!msg.isUserVisible()) continue;
        if (spanType != null && !SpanType.LLM_FINAL_RESPONSE.value().equals(spanType)) continue;
      }

      Map<String, Object> entry = messageToMap(msg);
      if (entry != null) {
        result.add(entry);
      }
    }
    return result;
  }

  /**
   * Scenario B: Tool &amp; Multi-Agent CoT Fine-Tuning.
   *
   * <p>All interaction records are preserved unmodified: system, compact, user, assistant (all
   * spanTypes), tool. Parallel tool records are re-sorted to match upstream toolCall order before
   * sample assembly.
   */
  static List<Map<String, Object>> filterScenarioB(List<RawMessage> records) {
    List<Map<String, Object>> result = new ArrayList<>();
    // Skip system records here — they're handled at the SftSample level
    for (RawMessage msg : records) {
      if ("system".equals(msg.role())) continue;
      Map<String, Object> entry = messageToMap(msg);
      if (entry != null) {
        result.add(entry);
      }
    }
    return result;
  }

  // ============================================================
  // Message → Map Conversion
  // ============================================================

  /** Converts a RawMessage to a Map suitable for JSON serialization. */
  static Map<String, Object> messageToMap(RawMessage msg) {
    var map = new LinkedHashMap<String, Object>();
    map.put("role", msg.role());
    if (msg.content() != null) {
      map.put("content", msg.content());
    }
    if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
      List<Map<String, Object>> tcs = new ArrayList<>();
      for (ToolCall tc : msg.toolCalls()) {
        tcs.add(toolCallToMap(tc));
      }
      map.put("tool_calls", tcs);
    }
    if (msg.toolCallId() != null) {
      map.put("tool_call_id", msg.toolCallId());
    }
    if (msg.name() != null) {
      map.put("name", msg.name());
    }
    return map;
  }

  private static Map<String, Object> toolCallToMap(ToolCall tc) {
    var map = new LinkedHashMap<String, Object>();
    map.put("id", tc.id());
    map.put("type", tc.type());
    var func = new LinkedHashMap<String, Object>();
    func.put("name", tc.function().name());
    func.put("arguments", tc.function().arguments());
    map.put("function", func);
    return map;
  }

  // ============================================================
  // Compact Backtracking
  // ============================================================

  /**
   * Resolves the traceId that triggered a compact record by finding a matching trace whose root
   * spanId equals the compact's parentSpanId.
   */
  private static String resolveTraceIdByParentSpan(
      List<RawMessage> allMessages, RawMessage compactMsg) {

    String parentSpanId = compactMsg.parentSpanId();
    if (parentSpanId == null) return null;

    // Look for a trace whose root span matches this parentSpanId
    for (RawMessage msg : allMessages) {
      if (msg.spanId() != null && msg.spanId().equals(parentSpanId)) {
        return msg.traceId();
      }
    }
    return null;
  }
}
