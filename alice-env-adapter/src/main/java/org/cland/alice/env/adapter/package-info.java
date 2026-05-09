/**
 * alice-env-adapter — 环境适配器模块。
 *
 * <p>核心在于将"外部世界"抽象为一个可观察、可操作且可回滚的<strong>状态机</strong>。 通过原生支持 MCP 2.0，让 Agent 具备工业级的连接能力。
 *
 * <p>
 *
 * <h2>核心组件</h2>
 *
 * <ul>
 *   <li>{@link org.cland.alice.env.adapter.EnvManager} — 环境管理器，协调 MCP 客户端和快照生命周期
 *   <li>{@link org.cland.alice.env.adapter.McpClient} — MCP 2.0 协议客户端，封装工具调用和资源读取
 *   <li>{@link org.cland.alice.env.adapter.snapshot.SnapshotManager} — 快照管理器，支持保存/回滚/审计
 *   <li>{@link org.cland.alice.env.adapter.snapshot.EnvSnapshot} — 不可变环境状态快照
 *   <li>{@link org.cland.alice.env.adapter.transport.McpTransport} — 传输层抽象（Stdio/SSE）
 *   <li>{@link org.cland.alice.env.adapter.state.EnvState} — 环境状态机
 * </ul>
 *
 * <p>
 *
 * <h2>环境状态机流转</h2>
 *
 * <pre>
 *        [ DISCONNECTED ]
 *               |
 *               v (Connect / Handshake)
 *        [ INITIALIZING ]
 *               |
 *               v (Capability Discovery)
 *    +----[ READY / IDLE ] &lt;----------------+
 *    |          |                           |
 *    |          v (Action Triggered)        |
 *    |   [ CAPTURING SNAPSHOT ]             |
 *    |          |                           |
 *    |          v                           |
 *    |   [ EXECUTING (MCP) ]                |
 *    |          |                           |
 *    |          +---- (Success) ----&gt; [ AUDITING ]
 *    |          |                        |
 *    |          +---- (Error/Abort) -+   | (Pass)
 *    |                               |   v
 *    +---- [ ROLLING BACK ] &lt;--- (Fail) -+---- [ COMMITTED ]
 * </pre>
 */
package org.cland.alice.env.adapter;
