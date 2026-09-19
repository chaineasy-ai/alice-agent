/*
 * Alice Agent — 事件帧编解码（协议 v1）
 *
 * 与 CommandCodec 同源（同 v1 纪律、同 Jackson 配置）：字段只增不改不删、未知字段忽略、
 * 帧必须带 v。详见 docs/alice-agent-proto/PROTOCOL.md。
 */
package org.cland.alice.agent.proto.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Objects;
import org.cland.alice.agent.proto.event.StepEvent;
import org.cland.alice.agent.proto.port.CommandValidationException;

/**
 * 事件编解码 — StepEvent ↔ 协议 v1 线格式（JSON）。
 *
 * <p>传输面（stdio JSONL / HTTP+SSE）统一调本类；JSONL 分帧（只按 {@code \n} 切）由传输层负责。
 */
public final class EventCodec {

  private EventCodec() {}

  /**
   * 事件 → JSON 帧（单行）。
   *
   * @param event 事件（不得为 null）
   * @return 单行 JSON
   * @throws CommandValidationException 序列化失败
   */
  public static String encode(StepEvent event) {
    Objects.requireNonNull(event, "event must not be null");
    try {
      return Json.MAPPER.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      throw new CommandValidationException("事件序列化失败：" + e.getOriginalMessage(), e);
    }
  }

  /**
   * JSON 帧 → 事件。
   *
   * @param json 单行 JSON（未知字段忽略）
   * @return 事件
   * @throws CommandValidationException 非法 JSON / 版本不支持 / 缺必填字段
   */
  public static StepEvent decode(String json) {
    StepEvent event;
    try {
      event = Json.MAPPER.readValue(json == null ? "" : json, StepEvent.class);
    } catch (Exception e) {
      throw new CommandValidationException("事件帧不是合法 JSON：" + e.getMessage(), e);
    }
    if (event.v() != StepEvent.V1) {
      throw new CommandValidationException("不支持的事件版本 v=" + event.v() + "（当前支持 v=1）");
    }
    return event;
  }
}
