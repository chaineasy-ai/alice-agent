/*
 * Alice Agent — JSON 输出渲染器（--json）
 *
 * 口径变更（2026-09-19）：不再自定义 JSON 结构，改为输出**协议 v1 StepEvent 帧（JSONL）**，
 * 与 stdio/HTTP 传输共用同一套 codec/schema（契约唯一真源 ✓）。
 */
package org.cland.alice.facade.cli.render;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.cland.alice.agent.proto.codec.EventCodec;
import org.cland.alice.agent.proto.event.StepEvent;
import org.cland.alice.agent.proto.event.StepEventType;
import org.cland.alice.core.agent.lifecycle.Observation;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.facade.cli.config.RunConfig;
import org.cland.alice.runtime.compose.Ids;

/**
 * JSON 结构输出渲染器（{@code --json} 模式）—— 输出协议 v1 {@link StepEvent} 帧（每行一个 JSON）。
 *
 * <p>映射：Continue(action) → {@code TOOL_CALL}；Continue(observation) → {@code TOOL_RESULT}； Finish →
 * {@code SUMMARY}；Failure → {@code ERROR}；renderFinal/renderError 末尾补 {@code DONE}。
 */
public final class JsonOutputRenderer implements OutputRenderer {

  private final String sessionId;
  private final String traceId;
  private final AtomicLong seq = new AtomicLong();

  /**
   * @param sessionId 会话 ID（协议帧必填）
   * @param traceId 链路 ID（协议帧必填）
   */
  public JsonOutputRenderer(String sessionId, String traceId) {
    this.sessionId = sessionId == null || sessionId.isBlank() ? "-" : sessionId;
    this.traceId = traceId == null || traceId.isBlank() ? Ids.newTraceId() : traceId;
  }

  /** 缺省构造（sessionId 记 {@code -}；测试/嵌入用）。 */
  public JsonOutputRenderer() {
    this("-", Ids.newTraceId());
  }

  @Override
  public void render(StepResult stepResult, RunConfig config) {
    if (stepResult == null) {
      return;
    }
    switch (stepResult) {
      case StepResult.Continue cont -> {
        var action = cont.nextAction();
        if (action != null) {
          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("type", action.type().name());
          payload.put("tool", action.target());
          payload.put("actionId", action.actionId());
          if (action.parameters() != null && !action.parameters().isEmpty()) {
            payload.put("args", action.parameters()); // 协议 TOOL_CALL 口径：args 必带 ✓
          }
          if (config != null && config.verbose() && action.thought() != null) {
            payload.put("thought", action.thought());
          }
          emit(StepEventType.TOOL_CALL, payload);
        }
        Observation observation = cont.observation();
        if (observation != null) {
          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("status", observation.status().name());
          payload.put("summary", observation.summary());
          emit(StepEventType.TOOL_RESULT, payload);
        }
      }
      case StepResult.Finish fin -> {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", fin.answer());
        if (fin.summary() != null) {
          payload.put("summary", fin.summary());
        }
        emit(StepEventType.SUMMARY, payload);
      }
      case StepResult.Failure fail -> {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", fail.errorMessage());
        if (fail.cause() != null) {
          payload.put("cause", fail.cause().getMessage());
        }
        emit(StepEventType.ERROR, payload);
      }
    }
  }

  @Override
  public void renderFinal(String summary, RunConfig config) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("text", summary == null ? "" : summary);
    emit(StepEventType.SUMMARY, payload);
    emit(StepEventType.DONE, Map.of());
  }

  @Override
  public void renderError(String errorMessage, RunConfig config) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("message", errorMessage == null ? "" : errorMessage);
    emit(StepEventType.ERROR, payload);
    emit(StepEventType.DONE, Map.of());
  }

  // ========================================================================
  // 辅助
  // ========================================================================

  private void emit(StepEventType type, Map<String, Object> payload) {
    StepEvent event =
        new StepEvent(
            StepEvent.V1,
            type,
            sessionId,
            traceId,
            seq.getAndIncrement(),
            Instant.now(),
            payload,
            StepEvent.Usage.zero());
    try {
      System.out.println(EventCodec.encode(event));
    } catch (RuntimeException e) {
      System.err.println("JSON frame encode error: " + e.getMessage());
    }
  }
}
