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
 * <p>按驱动性质分为五大类：
 *
 * <ol>
 *   <li><b>ExecutionCmd</b> — 任务驱动，消耗 Token 的实际工作（/run, /exec）
 *   <li><b>CapabilityCmd</b> — 能力装载，需 Reload 的静态/动态资源（/skill, /rules, /reload）
 *   <li><b>AlignmentCmd</b> — 运行配置，调整内核参数（/model）
 *   <li><b>ControlCmd</b> — 控制与反馈，生命周期与 HITL（/new, /feedback, /exit 等）
 *   <li><b>RoutineTimeCmd</b> — 定时调度，Cron 表达式驱动的自主任务（/routine）
 *   <li><b>SubAgentCmd</b> — 多 Agent 管理，子 Agent 创建与外部 ACP Agent 连接（/sub-agent）
 * </ol>
 *
 * <p>每条指令都携带 {@code sessionId} 与 {@code traceId}，便于链路追踪。
 */
public sealed interface AgentCommand
    permits ExecutionCmd, CapabilityCmd, AlignmentCmd, ControlCmd, RoutineTimeCmd, SubAgentCmd {

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
      case "/clear" -> new ControlCmd.ClearContextCmd(sessionId, traceId);
      case "/context" -> new ControlCmd.ViewContextCmd(sessionId, traceId);
      case "/compact" -> new ControlCmd.CompactContextCmd(sessionId, traceId);

      // ── Routine-Time ──────────────────────────────────────────
      case "/routine" -> new RoutineTimeCmd.RegisterRoutineCmd(args, sessionId, traceId);

      // ── Sub-Agent ─────────────────────────────────────────────
      case "/sub-agent" -> parseSubAgent(args, sessionId, traceId);

      // 非斜杠命令（上文已处理），或未知斜杠命令
      default -> null;
    };
  }

  /**
   * 解析 {@code /sub-agent} 子命令。
   *
   * <p>格式：{@code /sub-agent <subcommand> [args...]}
   *
   * <p>支持的子命令：
   *
   * <ul>
   *   <li>{@code spawn --goal "<goal>" [--model <model>]}
   *   <li>{@code connect --name <name> --acp-endpoint <url>}
   *   <li>{@code list}
   *   <li>{@code cancel <id>}
   *   <li>{@code results <id>}
   *   <li>{@code send <id> <message>}
   *   <li>{@code prompt <id> <prompt>}
   * </ul>
   */
  private static AgentCommand parseSubAgent(String args, String sessionId, String traceId) {
    if (args == null || args.isBlank()) return null;

    int spaceIdx = args.indexOf(' ');
    String subCmd =
        spaceIdx < 0 ? args.trim().toLowerCase() : args.substring(0, spaceIdx).toLowerCase();
    String subArgs = spaceIdx < 0 ? "" : args.substring(spaceIdx + 1).trim();

    return switch (subCmd) {
      case "spawn" -> {
        // Parse --goal "..." [--model "..."]
        String goal = extractNamedArg(subArgs, "--goal");
        if (goal == null || goal.isBlank()) yield null;
        String model = extractNamedArg(subArgs, "--model");
        yield new SpawnSubAgentCmd(goal, model, sessionId, traceId);
      }
      case "connect" -> {
        String name = extractNamedArg(subArgs, "--name");
        String ep = extractNamedArg(subArgs, "--acp-endpoint");
        if (name == null || name.isBlank() || ep == null || ep.isBlank()) yield null;
        try {
          java.net.URI uri = java.net.URI.create(ep);
          // Require a scheme (http/https) to reject relative URIs like "not-a-url"
          if (uri.getScheme() == null) yield null;
          yield new ConnectSubAgentCmd(name, uri, sessionId, traceId);
        } catch (IllegalArgumentException e) {
          yield null;
        }
      }
      case "list" -> new ListSubAgentsCmd(sessionId, traceId);
      case "cancel" -> {
        if (subArgs.isBlank()) yield null;
        yield new CancelSubAgentCmd(subArgs, sessionId, traceId);
      }
      case "results" -> {
        if (subArgs.isBlank()) yield null;
        yield new GetSubAgentResultsCmd(subArgs, sessionId, traceId);
      }
      case "send" -> {
        // Format: send <id> <message>
        int idEnd = subArgs.indexOf(' ');
        if (idEnd < 0) yield null;
        String id = subArgs.substring(0, idEnd).trim();
        String msg = subArgs.substring(idEnd + 1).trim();
        // Strip surrounding quotes if present
        if (msg.startsWith("\"") && msg.endsWith("\"")) {
          msg = msg.substring(1, msg.length() - 1);
        }
        if (id.isBlank() || msg.isBlank()) yield null;
        yield new SendToSubAgentCmd(id, msg, sessionId, traceId);
      }
      case "prompt" -> {
        int idEnd = subArgs.indexOf(' ');
        if (idEnd < 0) yield null;
        String id = subArgs.substring(0, idEnd).trim();
        String prompt = subArgs.substring(idEnd + 1).trim();
        // Strip surrounding quotes if present
        if (prompt.startsWith("\"") && prompt.endsWith("\"")) {
          prompt = prompt.substring(1, prompt.length() - 1);
        }
        if (id.isBlank() || prompt.isBlank()) yield null;
        yield new PromptSubAgentCmd(id, prompt, sessionId, traceId);
      }
      default -> null;
    };
  }

  /**
   * 从参数字符串中提取命名参数的值。
   *
   * <p>例如 {@code extractNamedArg("--goal \"hello\" --model gpt-4", "--goal")} 返回 {@code "hello"}。
   *
   * @param args 参数字符串
   * @param name 参数名（含 {@code --} 前缀）
   * @return 参数值，未找到时返回 {@code null}
   */
  private static String extractNamedArg(String args, String name) {
    int idx = args.indexOf(name);
    if (idx < 0) return null;
    String after = args.substring(idx + name.length()).trim();
    if (after.isEmpty()) return null;
    if (after.startsWith("\"")) {
      int end = after.indexOf('"', 1);
      return end < 0 ? after.substring(1) : after.substring(1, end);
    }
    int end = after.indexOf(' ');
    return end < 0 ? after : after.substring(0, end);
  }
}
