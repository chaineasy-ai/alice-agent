/**
 * alice-agent-runtime — 会话宿主（会话语义收口）
 *
 * <p>职责：
 *
 * <ul>
 *   <li>{@link org.cland.alice.runtime.AgentHost}：实现 {@code AgentCommandDispatcher}—— 一轮一锁 / 单写者 /
 *       超时中止 / new-resume 策略 / 内核事件 → StepEvent 映射 / 健康快照
 *   <li>{@link org.cland.alice.runtime.engine.AgentEngine}：引擎端口（{@code CoreAgentEngine} 适配
 *       core.Agent）
 *   <li>{@link org.cland.alice.runtime.transport.InProcessTransport}：进程内直调传输
 * </ul>
 *
 * <p>依赖纪律：只依赖协议契约（{@code alice.agent.proto.main}）与 core 引擎； 传输/门面依赖本模块，不直接依赖 core。
 */
module alice.agent.runtime.main {
  exports org.cland.alice.runtime;
  exports org.cland.alice.runtime.compose;
  exports org.cland.alice.runtime.engine;
  exports org.cland.alice.runtime.transport;

  requires alice.agent.proto.main;
  requires alice.agent.alice.core.agent.main;
  // 组合根挂 Guardrail 桥（GuardrailVerificatorAdapter implements Verificator）
  requires alice.agent.alice.guardrail.main;
  // 启动横幅枚举工具（ToolRegistry）
  requires alice.agent.alice.tool.gateway.main;
}
