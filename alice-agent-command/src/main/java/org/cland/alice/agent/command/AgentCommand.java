/*
 * Alice Agent — Abstract Command Layer
 *
 * 对应 docs/app/AgentCommand.md §1 类图中的顶层接口。
 * 所有面向 Agent 的指令均由此密封体系派生。
 */
package org.cland.alice.agent.command;

import java.time.Instant;

/**
 * AgentCommand — 所有 Agent 指令的顶层密封接口。
 *
 * <p>按驱动性质分为四大类：
 *
 * <ol>
 *   <li><b>ExecutionCmd</b> — 任务驱动，消耗 Token 的实际工作（/run, /exec）
 *   <li><b>CapabilityCmd</b> — 能力装载，需 Reload 的静态/动态资源（/skill, /rules, /reload）
 *   <li><b>AlignmentCmd</b> — 运行配置，调整内核参数（/model）
 *   <li><b>ControlCmd</b> — 控制与反馈，生命周期与 HITL（/new, /feedback, /exit 等）
 * </ol>
 *
 * <p>每条指令都携带 {@code sessionId} 与 {@code traceId}，便于链路追踪。
 */
public sealed interface AgentCommand permits ExecutionCmd, CapabilityCmd, AlignmentCmd, ControlCmd {

  /** 会话标识 */
  String sessionId();

  /** 链路追踪标识 */
  String traceId();

  /** 指令发起时间戳 */
  Instant timestamp();

  // ========================================================================
  // 工厂方法 —— 从原始输入构造 AgentCommand
  // ========================================================================

  /**
   * 从用户输入中解析出一条 AgentCommand。
   *
   * @param input 用户原始输入（可能以 "/" 开头）
   * @param sessionId 当前会话 ID
   * @param traceId 当前链路 ID
   * @return 解析后的 AgentCommand，如果无法识别则返回 {@code null}
   */
  static AgentCommand parse(String input, String sessionId, String traceId) {
    if (input == null || input.isBlank()) return null;
    String trimmed = input.trim();

    // 非斜杠命令统一视为自然语言任务 → AcquireGoalCmd
    if (!trimmed.startsWith("/")) {
      return new ExecutionCmd.AcquireGoalCmd(trimmed, sessionId, traceId);
    }

    // 分离命令名与参数
    int spaceIdx = trimmed.indexOf(' ');
    String cmd;
    String args;
    if (spaceIdx < 0) {
      cmd = trimmed.toLowerCase();
      args = "";
    } else {
      cmd = trimmed.substring(0, spaceIdx).toLowerCase();
      args = trimmed.substring(spaceIdx + 1).trim();
    }

    return switch (cmd) {
      // ── Execution ──────────────────────────────────────────────
      case "/run" ->
          new ExecutionCmd.AcquireGoalCmd(
              args.isBlank() ? "(empty /run)" : args, sessionId, traceId);
      case "/exec" ->
          new ExecutionCmd.ExecuteRawCmd(
              args.isBlank() ? "echo 'no command given'" : args, sessionId, traceId);

      // ── Capability ─────────────────────────────────────────────
      case "/skill" -> new CapabilityCmd.RegisterSkillCmd(args, sessionId, traceId);
      case "/rules" -> new CapabilityCmd.UpdateRulesCmd(args, sessionId, traceId);
      case "/reload" -> new CapabilityCmd.ReloadKernelCmd(sessionId, traceId);

      // ── Alignment ──────────────────────────────────────────────
      case "/model" ->
          new AlignmentCmd.SwitchModelCmd(args.isBlank() ? "gpt-4o" : args, sessionId, traceId);

      // ── Control ────────────────────────────────────────────────
      case "/new" -> new ControlCmd.ResetSessionCmd(sessionId, traceId);
      case "/feedback" -> new ControlCmd.FeedbackCmd(args, sessionId, traceId);
      case "/exit" -> new ControlCmd.InterruptCmd("user-exit", sessionId, traceId);

      // 非斜杠命令（上文已处理），或未知斜杠命令
      default -> null;
    };
  }
}
