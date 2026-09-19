/**
 * alice-facade-rpc — HTTP RPC 2.0（协议适配层）
 *
 * <p>路由（v1，见 docs/alice-facade-rpc/DESIGN.md）：
 *
 * <ul>
 *   <li>{@code POST /api/v1/session} — 新建/续接（type=new|resume）
 *   <li>{@code POST /api/v1/chat/stream} — 一轮任务（type=prompt，SSE）
 *   <li>{@code POST /api/v1/chat/steer} — 人工插话（type=steer）
 *   <li>{@code POST /api/v1/chat/interrupt} — 中止当前轮（type=abort）
 *   <li>{@code POST /api/v1/command} — 通用：任意命令信封
 *   <li>{@code GET /api/v1/health} — 宿主健康快照
 * </ul>
 *
 * <p>请求体 = 协议 v1 命令信封（与 stdio 同构）；响应帧 = StepEvent v1（SSE {@code data:}）。 只依赖 proto + runtime（+
 * bootstrap SPI）；**不依赖 core**（组合根在 runtime）。
 *
 * <p>SPI：{@code --facade rpc}（HTTP，默认 8080）或 {@code --facade rpc --mode rpc}（stdio JSONL）。
 */
module alice.agent.facade.rpc.main {
  exports org.cland.alice.facade.rpc;

  provides org.cland.alice.agent.spi.AliceFacade with
      org.cland.alice.facade.rpc.AliceRpcFacade;

  requires alice.agent.proto.main;
  requires alice.agent.runtime.main;
  requires alice.agent.app.main;
  requires jdk.httpserver;
}
