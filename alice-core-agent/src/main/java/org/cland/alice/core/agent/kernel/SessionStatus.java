package org.cland.alice.core.agent.kernel;

/**
 * 会话终态的语义化枚举（内核词汇）。
 *
 * <p>与厂商原始终止词（finish_reason 等）无关：原始词由 pipeline 在 Inferencer 内翻译， 不进内核。
 */
public enum SessionStatus {
  /** 会话正常闭环（全部目标通过 / 模型判据提交后经会话仲裁点放行）。 */
  FINISHED,

  /** 会话被取消（安全点取消语义生效）。 */
  CANCELLED,

  /** 会话因致命错误终止（error / 熔断兜底）。 */
  FAILED
}
