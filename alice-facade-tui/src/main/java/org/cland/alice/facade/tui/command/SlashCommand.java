package org.cland.alice.facade.tui.command;

import org.cland.alice.agent.command.AgentCommand;

/**
 * 斜杠命令定义，对应设计文档 §7.3 的斜杠命令表。
 *
 * <p>现在基于 {@link org.cland.alice.agent.command.AgentCommand} 抽象指令层实现。
 *
 * <p>命令分为三类：
 *
 * <ul>
 *   <li>Type A（内部）：仅操作 UI/会话状态 — /new, /clear, /exit, /help
 *   <li>Type B（IO 操作）：读取文件等 — /prompt, /history
 *   <li>Type C（系统）：执行 shell 命令 — /exec
 *   <li>Type D（模型/工具）：修改运行时行为 — /model, /tools
 * </ul>
 */
public record SlashCommand(String command, String args, Type type, String description) {

  public enum Type {
    /** 仅操作 UI/会话状态 */
    INTERNAL,
    /** IO 操作（文件读取等） */
    IO,
    /** 系统命令执行 */
    SYSTEM,
    /** 模型/工具配置 */
    CONFIG
  }

  /** 提取输入中的斜杠命令，如果存在则返回 SlashCommand，否则返回 null */
  public static SlashCommand parse(String input) {
    if (input == null || !input.startsWith("/")) {
      return null;
    }

    // 分离命令名和参数
    String trimmed = input.trim();
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
      case "/new" -> new SlashCommand(cmd, args, Type.INTERNAL, "重置会话：清空上下文，开启新对话");
      case "/clear" -> new SlashCommand(cmd, args, Type.INTERNAL, "清屏：仅清空 UI 显示内容");
      case "/exit" -> new SlashCommand(cmd, args, Type.INTERNAL, "安全退出：保存会话后关闭 TUI");
      case "/help" -> new SlashCommand(cmd, args, Type.INTERNAL, "命令帮助：列出所有斜杠命令");
      case "/context" -> new SlashCommand(cmd, args, Type.INTERNAL, "查看上下文：展示当前会话的 Token 占用与消息快照");
      case "/compact" ->
          new SlashCommand(cmd, args, Type.INTERNAL, "压缩上下文：提炼历史为摘要，释放 Context Window");
      case "/feedback" -> new SlashCommand(cmd, args, Type.INTERNAL, "反馈：向 Agent 注入人类反馈（HITL）");
      case "/prompt" -> new SlashCommand(cmd, args, Type.IO, "加载提示词：读取外部文件作为系统提示");
      case "/history" -> new SlashCommand(cmd, args, Type.IO, "历史回溯：展示最近执行记录快照");
      case "/exec" -> new SlashCommand(cmd, args, Type.SYSTEM, "执行指令：运行 Shell 命令并将结果传给 Agent");
      case "/model" -> new SlashCommand(cmd, args, Type.CONFIG, "切换模型：动态修改当前使用 LLM");
      case "/tools" -> new SlashCommand(cmd, args, Type.CONFIG, "查看工具：列出 Agent 可用工具集");
      default -> null;
    };
  }

  /**
   * 将当前 SlashCommand 转换为对应的 {@link AgentCommand}。
   *
   * <p>部分命令（如 /clear, /help）仅 UI 层面处理，无 AgentCommand 映射，返回 {@code null}。
   *
   * @param sessionId 当前会话 ID
   * @param traceId 当前链路 ID
   * @return 对应的 AgentCommand，或 {@code null}
   */
  public AgentCommand toAgentCommand(String sessionId, String traceId) {
    // 拼接原始格式作为 AgentCommand.parse 的输入
    String raw = command + (args.isEmpty() ? "" : " " + args);
    return AgentCommand.parse(raw, sessionId, traceId);
  }

  /** 是否匹配某个命令 */
  public boolean is(String commandName) {
    return command.equalsIgnoreCase(commandName);
  }

  /** 是否有参数 */
  public boolean hasArgs() {
    return args != null && !args.isEmpty();
  }

  /** 获取所有可用命令的帮助文本 */
  public static String helpText() {
    return """
            ── 斜杠命令 (Slash Commands) ──────────────────────────
            /new         重置会话：清空上下文，开启新对话
            /clear       清屏：仅清空 UI 显示内容
            /context     查看上下文：展示当前 Token 占用与消息快照
            /compact     压缩上下文：提炼历史摘要，释放 Context Window
            /feedback    反馈：向 Agent 注入人类反馈（HITL）
            /exit        安全退出：保存会话后关闭 TUI
            /help        命令帮助：列出所有斜杠命令
            /prompt <f>  加载提示词：读取外部文件作为系统提示
            /history     历史回溯：展示最近执行记录快照
            /exec <cmd>  执行指令：运行 Shell 命令并将结果传给 Agent
            /model <id>  切换模型：动态修改当前使用 LLM
            /tools       查看工具：列出 Agent 可用工具集
            ────────────────────────────────────────────────────────
            """;
  }
}
