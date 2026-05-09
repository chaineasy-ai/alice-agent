package org.cland.alice.core.planner.model;

/**
 * 模型供应者抽象，对应设计文档中的 {@code ModelSupplier}。
 *
 * <p>Planner 内部不直接持有 OpenAI / Ollama 客户端， 而是通过此接口获取 ModelSession，实现 LLM Agnostic。
 */
public interface ModelSupplier {

  /** 获取用于复杂推理的模型会话（System 2 / Slow Path）。 通常对应高性能模型如 GPT-4o、DeepSeek-V3。 */
  ModelSession getReasoningModel();

  /** 获取用于快速分类或简单指令的模型会话（System 1 / Fast Path）。 通常对应轻量模型如 Qwen-1.8B、GPT-4o-mini。 */
  ModelSession getInstructionModel();

  /** 供应商名称。 */
  default String name() {
    return getClass().getSimpleName();
  }
}
