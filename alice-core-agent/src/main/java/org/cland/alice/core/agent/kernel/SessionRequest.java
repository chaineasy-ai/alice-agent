package org.cland.alice.core.agent.kernel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次会话执行的请求契约（内核数据）。
 *
 * <p>承载进入会话闭环的最小信息：会话标识、用户输入、模型选择与扩展选项。 请求不携带任何执行策略 —— 策略由 L2 Agent 在装配期注入内核。
 *
 * <p>扩展选项（options）约定：{@code maxIterations} 为内核可识别的预算键（数值）， 其余键逐项落入会话上下文的初始属性 （legacy
 * 过渡期语义；目标态下预算/判据均为图注解，不再走自由键）。
 *
 * @param sessionId 会话 ID；为 {@code null} 或空白时由内核实现生成
 * @param input 用户输入 / 环境信号
 * @param modelId 模型 ID；为 {@code null} 时使用实现默认模型
 * @param options 扩展选项（不可为 null；规范化后不可变）
 */
public record SessionRequest(
    String sessionId, String input, String modelId, Map<String, Object> options) {

  /** 规范化：options 为空或不可变副本，避免外部修改影响执行。 */
  public SessionRequest {
    options = normalize(options);
  }

  /** 便捷工厂：仅输入，会话与模型交由内核决定。 */
  public static SessionRequest of(String input) {
    return new SessionRequest(null, input, null, Map.of());
  }

  /** 便捷工厂：指定会话与输入。 */
  public static SessionRequest of(String sessionId, String input) {
    return new SessionRequest(sessionId, input, null, Map.of());
  }

  /** 便捷工厂：全量指定。 */
  public static SessionRequest of(
      String sessionId, String input, String modelId, Map<String, Object> options) {
    return new SessionRequest(sessionId, input, modelId, options);
  }

  private static Map<String, Object> normalize(Map<String, Object> options) {
    if (options == null || options.isEmpty()) {
      return Map.of();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(options));
  }
}
