package org.cland.alice.core.agent.wal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for reordering parallel asynchronous tool return records to match the upstream
 * assistant's toolCalls order.
 *
 * <p>Per the WAL specification (§3.4): when a single assistant record contains multiple parallel
 * ToolCall entries, persisted tool records must align in identical order. If asynchronous tool
 * return payloads arrive out-of-order (disordered timestamps due to network latency), this utility
 * re-sorts them.
 *
 * <p>Usage in ETL:
 *
 * <pre>{@code
 * List<RawMessage> reordered = ToolRecordReorderingUtil.reorderToolMessages(
 *     originalMessages, toolSourceAssistant.toolCalls());
 * }</pre>
 */
public final class ToolRecordReorderingUtil {

  private ToolRecordReorderingUtil() {}

  /**
   * Reorders tool records in the given list to match the upstream assistant's toolCalls order.
   * Non-tool records retain their original relative positions.
   *
   * <p>Algorithm:
   *
   * <ol>
   *   <li>Build an ordered map from toolCallId → position from the assistant's toolCalls list
   *   <li>Extract all tool records and reorder by position (toolCallId lookup)
   *   <li>Rebuild the list: non-tool records in place, tool records in correct order
   * </ol>
   *
   * @param messages the full message list for a trace (may contain tool records in arbitrary order)
   * @param assistantToolCalls the ordered toolCalls list from the upstream assistant message
   * @return a new list with tool records reordered to match the upstream sequence
   */
  public static List<RawMessage> reorderToolMessages(
      List<RawMessage> messages, List<ToolCall> assistantToolCalls) {

    if (messages == null || messages.isEmpty()) return List.of();
    if (assistantToolCalls == null || assistantToolCalls.isEmpty()) {
      return List.copyOf(messages);
    }

    // 1. Build toolCallId → position index
    Map<String, Integer> positionIndex = new LinkedHashMap<>();
    for (int i = 0; i < assistantToolCalls.size(); i++) {
      positionIndex.put(assistantToolCalls.get(i).id(), i);
    }

    // 2. Separate tool and non-tool records
    List<RawMessage> nonToolRecords = new ArrayList<>();
    List<RawMessage> toolRecords = new ArrayList<>();

    for (RawMessage msg : messages) {
      if ("tool".equals(msg.role())) {
        toolRecords.add(msg);
      } else {
        nonToolRecords.add(msg);
      }
    }

    // 3. Sort tool records by their position in the upstream toolCalls list
    toolRecords.sort(
        Comparator.comparingInt(
            msg -> positionIndex.getOrDefault(msg.toolCallId(), Integer.MAX_VALUE)));

    // 4. Interleave: maintain relative order of non-tool records, insert
    //    reordered tool records in positions where they originally were.
    //    To preserve the original structure, we rebuild by iterating through
    //    the original list and replacing tool records in sequence.
    List<RawMessage> result = new ArrayList<>(messages.size());
    int toolIndex = 0;

    for (RawMessage msg : messages) {
      if ("tool".equals(msg.role())) {
        // Insert the next reordered tool record
        if (toolIndex < toolRecords.size()) {
          result.add(toolRecords.get(toolIndex));
          toolIndex++;
        } else {
          // Fallback: keep original if we've exhausted sorted list
          result.add(msg);
        }
      } else {
        result.add(msg);
      }
    }

    return List.copyOf(result);
  }

  /**
   * Validates that tool records in the given list are in correct order matching the upstream
   * assistant's toolCalls list.
   *
   * @param messages the message list to validate
   * @param assistantToolCalls the ordered toolCalls list from the upstream assistant
   * @return true if all tool records are in the correct order
   */
  public static boolean isOrderCorrect(
      List<RawMessage> messages, List<ToolCall> assistantToolCalls) {

    if (assistantToolCalls == null || assistantToolCalls.isEmpty()) return true;

    Map<String, Integer> positionIndex = new LinkedHashMap<>();
    for (int i = 0; i < assistantToolCalls.size(); i++) {
      positionIndex.put(assistantToolCalls.get(i).id(), i);
    }

    int lastPosition = -1;
    for (RawMessage msg : messages) {
      if ("tool".equals(msg.role())) {
        Integer pos = positionIndex.get(msg.toolCallId());
        if (pos == null) continue; // unknown toolCallId, skip
        if (pos < lastPosition) return false;
        lastPosition = pos;
      }
    }
    return true;
  }
}
