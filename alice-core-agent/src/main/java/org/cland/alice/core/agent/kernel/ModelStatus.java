package org.cland.alice.core.agent.kernel;

/**
 * 一次 Infer 的语义化终止状态（内核词汇，§6.1）。
 *
 * <p>厂商原始词（finish_reason 等）在 pipeline ⑥ Observe→Record 段内翻译，不进内核。
 */
public enum ModelStatus {
  /** 有内容 — 模型正常完成（语义上可作 goal 级判据提交的素材）。 */
  CONTENT,

  /** 要工具 — 模型请求执行工具（tool_calls）。 */
  TOOL_CALLS,

  /** 失败 — 传输/执行层失败（错误细节见 {@link ModelObservation#detail()}）。 */
  FAILED,

  /** 截断 — 输出被截断/过滤（如 length/content_filter，细节见 detail）。 */
  TRUNCATED
}
