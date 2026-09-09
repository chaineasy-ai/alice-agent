package org.cland.alice.core.agent.kernel;

import io.vertx.core.Future;

/**
 * 内核执行契约（主接口，D7 定名）。
 *
 * <p>内核 = 承载 LLM-Agent 决策循环的执行基底：循环骨架与语义（决策→效果→观察→再决策，何时问、结果算什么、如何推进/回退/仲裁）、
 * 嵌套图结构语义（战术子图展开、复合节点、exit port）与账本状态语义由内核负责；提示词、协议编解码、模型供应商、工具实现在外。
 *
 * <p>目标形态下本接口只是图元模型（decision/effect/gate/observe/terminal/composite）的 <b>解释器</b>： 执行逻辑由图设计决定（R0
 * 唯一遍历器），策略内容由 L2 Agent 按能力声明装配注入。
 *
 * <p><b>当前状态（D3 手术式抽取的第一步）</b>：{@code AgentExecutor} 是首个实现（legacy 先行实现）—— PPAO 编排、WAL
 * 记录点、事件分发保持原样，仅把对外的执行面收敛为本契约； 后续按 §5.2 R0–R4 将"语义决定"（何时终止/回退/仲裁）逐步收进解释器。
 *
 * <p>数据流类型（{@link SessionRequest}/{@link SessionResult}）为内核词汇，不引用任何策略类型； L2 Agent 负责把业务请求翻译成 {@link
 * SessionRequest}，并把 {@link SessionResult} 翻译回业务结果。
 */
public interface Loop {

  /**
   * 进入一次会话闭环。
   *
   * <p>契约上"每会话一次"（D2）：goal 图游标与战术子图展开都是内核执行语义，子 agent 会话以同契约递归表达。
   *
   * @param request 会话请求（由 L2 组装）
   * @return 会话结果（状态/答案/迭代/元数据快照），异步完成
   */
  Future<SessionResult> execute(SessionRequest request);

  /** 取消当前执行（安全点取消语义）。可在任意线程安全调用。 */
  void cancel();

  /**
   * 当前内核状态只读快照（阶段/迭代/会话）。
   *
   * @return 快照；尚未执行过任何会话时返回空闲态
   */
  KernelState state();

  /** 内核执行事件流订阅入口（thought → action → observe）。 */
  EventStream events();
}
