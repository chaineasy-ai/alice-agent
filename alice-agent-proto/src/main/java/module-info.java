/**
 * alice-agent-proto — 协议契约层（对内/对外同一份契约）
 *
 * <p>组成：
 *
 * <ul>
 *   <li><b>入方向</b>：{@link org.cland.alice.agent.proto.AgentCommand} 密封指令体系
 *   <li><b>出方向</b>：{@link org.cland.alice.agent.proto.event.StepEvent} 事件帧（v1）
 *   <li><b>端口</b>：{@link org.cland.alice.agent.proto.port.AgentCommandDispatcher}（实现归 runtime）
 *   <li><b>codec</b>：{@link org.cland.alice.agent.proto.codec.CommandCodec} / {@link
 *       org.cland.alice.agent.proto.codec.EventCodec}（JSON v1）
 * </ul>
 *
 * <p>帧规范与兼容规则：docs/alice-agent-proto/PROTOCOL.md。 传输（InProcess/stdio/HTTP+SSE/ACP）与门面只依赖本模块契约，不依赖
 * core 实现。
 */
module alice.agent.proto.main {
  exports org.cland.alice.agent.proto;
  exports org.cland.alice.agent.proto.event;
  exports org.cland.alice.agent.proto.port;
  exports org.cland.alice.agent.proto.codec;

  requires org.slf4j;

  // codec（仅内部使用；Jackson 类型不进入公开 API ✓）
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.datatype.jsr310;
}
