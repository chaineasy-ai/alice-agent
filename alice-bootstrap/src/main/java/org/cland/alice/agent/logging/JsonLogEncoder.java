/*
 * Alice Agent — JSON Lines 日志编码器（#173 M3-2 可观测）
 *
 * 每行一个合法 JSON 对象（严格单行），字段对齐 `spec` 日志规范：
 *   timestamp(ISO8601) / level / app / trace_id / thread / logger / message [/ exception]
 * 供 `info.log` / `error.log` 使用，便于 SRE 采集器直接透传解析。
 */
package org.cland.alice.agent.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Logback 编码器：把 {@link ILoggingEvent} 编码为单行 JSON（JSON Lines）。
 *
 * <p>输出样例：
 *
 * <pre>
 * {"timestamp":"2026-10-16T07:30:00.123+08:00","level":"INFO","app":"alice-agent","trace_id":"-","thread":"main","logger":"org.cland.alice.agent.AliceApp","message":"Facade selected: cli"}
 * </pre>
 */
public class JsonLogEncoder extends EncoderBase<ILoggingEvent> {

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault());

  private static final String APP = "alice-agent";

  @Override
  public byte[] encode(ILoggingEvent event) {
    StringBuilder sb = new StringBuilder(256);
    sb.append('{');
    field(sb, "timestamp", TIMESTAMP.format(Instant.ofEpochMilli(event.getTimeStamp())));
    sb.append(',');
    field(sb, "level", String.valueOf(event.getLevel()));
    sb.append(',');
    field(sb, "app", APP);
    sb.append(',');
    Map<String, String> mdc = null;
    try {
      mdc = event.getMDCPropertyMap();
    } catch (RuntimeException ignored) {
      // MDC 未初始化（如早期/裸 LoggerContext）——日志编码不得因观测字段失败
    }
    String traceId = mdc == null ? "-" : mdc.getOrDefault("trace_id", "-");
    field(sb, "trace_id", traceId);
    sb.append(',');
    field(sb, "thread", event.getThreadName());
    sb.append(',');
    field(sb, "logger", event.getLoggerName());
    sb.append(',');
    field(sb, "message", event.getFormattedMessage());
    IThrowableProxy throwable = event.getThrowableProxy();
    if (throwable != null) {
      sb.append(',');
      field(sb, "exception", ThrowableProxyUtil.asString(throwable));
    }
    sb.append('}').append('\n');
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public byte[] headerBytes() {
    return null;
  }

  @Override
  public byte[] footerBytes() {
    return null;
  }

  private static void field(StringBuilder sb, String key, String value) {
    sb.append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"');
  }

  /** JSON 字符串转义（RFC 8259）：引号/反斜杠/控制字符。 */
  static String escape(String input) {
    if (input == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(input.length() + 16);
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }
}
