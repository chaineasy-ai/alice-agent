package org.cland.alice.agent.proto.codec;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * codec 共享的 Jackson 配置（**不对外暴露 Jackson 类型**，仅本包内使用）。
 *
 * <p>口径：时间走 ISO-8601（关时间戳序列化）；**容忍未知字段**（向前兼容：老消费者读新帧不崩）。
 */
final class Json {

  static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private Json() {}
}
