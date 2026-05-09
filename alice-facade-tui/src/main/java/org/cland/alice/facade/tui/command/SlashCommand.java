package org.cland.alice.facade.tui.command;

/**
 * 斜杠命令定义，对应设计文档 §7.3 的斜杠命令表。
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
      case "/prompt" -> new SlashCommand(cmd, args, Type.IO, "加载提示词：读取外部文件作为系统提示");
      case "/history" -> new SlashCommand(cmd, args, Type.IO, "历史回溯：展示最近执行记录快照");
      case "/exec" -> new SlashCommand(cmd, args, Type.SYSTEM, "执行指令：运行 Shell 命令并将结果传给 Agent");
      case "/model" -> new SlashCommand(cmd, args, Type.CONFIG, "切换模型：动态修改当前使用 LLM");
      case "/tools" -> new SlashCommand(cmd, args, Type.CONFIG, "查看工具：列出 Agent 可用工具集");
      default -> null;
    };
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
