package org.cland.alice.core.agent.kernel;

import io.vertx.core.Future;

/**
 * 文本 LLM pipeline 的执行契约（策略钩子 ②，§4/§6）。
 *
 * <p>内核决策循环只依赖本契约与 {@link ModelObservation}/{@link InferRequest} 语义类型： "何时问、问完的语义结果如何推进状态"归内核（§3.1
 * 决策循环语义）； "怎么问模型、怎么解析回答"（六段 pipeline：
 * Resolve/Assemble/Serialize/Transport/Decode/Observe→Record，§6.2）在此接口的实现内部完成。
 *
 * <p>pipeline 不是内核，也不是"效果源"：它是内核决策循环中 LLM 触点的一段边界实现。 kind（actor/classification/
 * reasoning/summarize…）是同一骨架的不同装配；当前 actor kind 由 {@code TextLlmPipeline} 提供（内部实现先行，D5）， 后续可按 kind
 * spec 声明装配。
 *
 * <p>实现方要求：返回的 Future 必须在调用线程可安全等待；当前实现同步完成（阻塞传输）， 流式/重试等异步形态以同契约演进。
 */
public interface Inferencer {

  /**
   * 执行一次 Infer（决策请求）往返。
   *
   * @param request 决策请求（含模型/角色文本/参数/工具素材）
   * @return 语义化观测结果（协议词已在 pipeline 内翻译）
   */
  Future<ModelObservation> infer(InferRequest request);
}
