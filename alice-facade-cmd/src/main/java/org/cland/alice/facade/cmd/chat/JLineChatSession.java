package org.cland.alice.facade.cmd.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.cland.alice.agent.command.AgentCommand;
import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.core.agent.wal.FileWalStore;
import org.cland.alice.core.agent.wal.WalSession;
import org.cland.alice.facade.cmd.AliceCliLauncher;
import org.jline.builtins.Completers;
import org.jline.reader.Completer;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.DefaultHighlighter;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JLineChatSession：基于 JLine 3 的交互式 CLI 聊天会话引擎。
 *
 * <p>提供行编辑、命令历史持久化、Tab 补全、多行输入、信号处理等能力。 用户输入统一通过 {@link AgentCommand#parse(String, String, String)}
 * 解析， 然后由 {@link AliceCliLauncher#dispatchCommand(String)} 分发执行。
 */
public class JLineChatSession implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(JLineChatSession.class);

  /** 默认历史文件路径 */
  private static final String HISTORY_FILE = "~/.alice/chat_history";

  /** 最大历史行数 */
  private static final int MAX_HISTORY_SIZE = 1000;

  private final Terminal terminal;
  private final LineReader reader;
  private final Agent agent;

  private volatile boolean running;

  public JLineChatSession() throws IOException {
    this(AgentConfig.defaults());
  }

  public JLineChatSession(AgentConfig config) throws IOException {
    // 1. 初始化 Terminal
    this.terminal = TerminalBuilder.builder().system(true).build();

    // 2. 创建 WAL 并初始化 Agent
    WalSession wal =
        new WalSession(
            new FileWalStore(
                java.nio.file.Paths.get(
                    System.getProperty("user.home"),
                    ".alice",
                    "wal",
                    UUID.randomUUID().toString().substring(0, 8))));
    this.agent = new Agent(config).withWal(wal);

    // 3. 构建 LineReader
    this.reader = buildLineReader();

    // 4. 确保历史目录存在
    ensureHistoryDir();

    this.running = false;
  }

  // ========== LineReader 构建 ==========

  private LineReader buildLineReader() {
    // Tab 补全器
    Completer completer = buildCompleter();

    // 高亮器（斜杠命令着色）
    Highlighter highlighter = new CommandHighlighter();

    return LineReaderBuilder.builder()
        .terminal(terminal)
        .completer(completer)
        .highlighter(highlighter)
        .variable(LineReader.HISTORY_FILE, resolveHistoryFile().toString())
        .variable(LineReader.HISTORY_SIZE, MAX_HISTORY_SIZE)
        .variable(LineReader.BELL_STYLE, "none")
        .variable(LineReader.DISABLE_HISTORY, false)
        .option(LineReader.Option.HISTORY_TIMESTAMPED, false)
        .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
        .option(LineReader.Option.HISTORY_REDUCE_BLANKS, true)
        .option(LineReader.Option.CASE_INSENSITIVE, false)
        .build();
  }

  /** 构建 Tab 补全器 */
  private Completer buildCompleter() {
    // 斜杠命令补全
    StringsCompleter slashCommands =
        new StringsCompleter(
            "/run",
            "/exec",
            "/skill",
            "/rules",
            "/reload",
            "/model",
            "/new",
            "/feedback",
            "/exit",
            "/clear",
            "/context",
            "/compact",
            "/help",
            "/prompt",
            "/history",
            "/tools");

    // /model 后补全模型 ID
    StringsCompleter modelIds =
        new StringsCompleter("gpt-4o", "gpt-4o-mini", "claude-3.5-sonnet", "gemma4", "o3-mini");

    ArgumentCompleter modelCompleter =
        new ArgumentCompleter(new StringsCompleter("/model"), modelIds);

    // /prompt 后补全文件路径
    ArgumentCompleter promptCompleter =
        new ArgumentCompleter(new StringsCompleter("/prompt"), new Completers.FileNameCompleter());

    // 模型 ID 补全（也可用于 /model <TAB>）
    StringsCompleter modelArgCompleter =
        new StringsCompleter("gpt-4o", "gpt-4o-mini", "claude-3.5-sonnet");

    return new AggregateCompleter(
        slashCommands,
        modelCompleter,
        promptCompleter,
        // 兜底：如果第一个词是 /model，第二个词补全模型 ID
        new ArgumentCompleter(
            new StringsCompleter("/model"), modelArgCompleter, NullCompleter.INSTANCE));
  }

  // ========== 主循环 ==========

  /**
   * 运行交互式聊天会话。
   *
   * <p>主循环流程：
   *
   * <ol>
   *   <li>打印欢迎信息
   *   <li>循环读取用户输入
   *   <li>解析并分发 AgentCommand
   *   <li>退出时清理资源
   * </ol>
   */
  public void run() {
    this.running = true;

    // 欢迎信息
    printWelcome();

    // 设置 Ctrl+C 处理
    terminal.handle(
        Terminal.Signal.INT,
        signal -> {
          System.out.println("\n收到中断信号 (Ctrl+C)，输入 /exit 退出或继续输入。");
        });

    String prompt = "alice> ";

    try {
      while (running) {
        String input;
        try {
          input = reader.readLine(prompt);
        } catch (org.jline.reader.EndOfFileException e) {
          // Ctrl+D → 退出
          System.out.println();
          break;
        } catch (org.jline.reader.UserInterruptException e) {
          // Ctrl+C → 取消当前输入，继续循环
          continue;
        }

        if (input == null || input.isBlank()) {
          continue;
        }

        // 检查多行输入（检测未闭合的引号/花括号）
        input = handleMultilineInput(input);

        // 处理退出命令
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("/exit") || trimmed.equalsIgnoreCase("exit")) {
          System.out.println("正在退出 Alice Agent...");
          break;
        }

        // 解析
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        AgentCommand cmd = AgentCommand.parse(input, sessionId, traceId());

        if (cmd != null) {
          dispatchAndRender(cmd);
        }
      }
    } catch (Exception e) {
      logger.error("Chat session error", e);
      System.err.println("\n会话发生错误: " + e.getMessage());
    } finally {
      close();
    }
  }

  /** 处理多行输入：检测未闭合的引号或花括号，自动进入多行模式。 */
  private String handleMultilineInput(String input) {
    String result = input;
    while (hasUnclosedBrackets(result) || hasUnclosedQuotes(result)) {
      String continuationPrompt = "→ ";
      String nextLine;
      try {
        nextLine = reader.readLine(continuationPrompt);
      } catch (Exception e) {
        break;
      }
      if (nextLine == null) {
        break;
      }
      result = result + "\n" + nextLine;
    }
    return result;
  }

  /** 检查是否有未闭合的引号 */
  private boolean hasUnclosedQuotes(String input) {
    int singleQuotes = 0;
    int doubleQuotes = 0;
    boolean escaped = false;
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
        continue;
      }
      if (c == '\'' && doubleQuotes % 2 == 0) {
        singleQuotes++;
      } else if (c == '"' && singleQuotes % 2 == 0) {
        doubleQuotes++;
      }
    }
    return singleQuotes % 2 != 0 || doubleQuotes % 2 != 0;
  }

  /** 检查是否有未闭合的花括号 */
  private boolean hasUnclosedBrackets(String input) {
    int braces = 0;
    int parens = 0;
    boolean inSingleQuote = false;
    boolean inDoubleQuote = false;

    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (c == '\'' && !inDoubleQuote) {
        inSingleQuote = !inSingleQuote;
      } else if (c == '"' && !inSingleQuote) {
        inDoubleQuote = !inDoubleQuote;
      }
      if (!inSingleQuote && !inDoubleQuote) {
        switch (c) {
          case '{' -> braces++;
          case '}' -> braces--;
          case '(' -> parens++;
          case ')' -> parens--;
        }
      }
    }
    return braces != 0 || parens != 0;
  }

  /** 分发 AgentCommand 并渲染输出 */
  private void dispatchAndRender(AgentCommand cmd) {
    // 对于 ExecutionCmd，输出思考提示
    if (cmd instanceof org.cland.alice.agent.command.ExecutionCmd) {
      String taskDesc =
          switch (cmd) {
            case org.cland.alice.agent.command.ExecutionCmd.AcquireGoalCmd g -> g.goal();
            case org.cland.alice.agent.command.ExecutionCmd.ExecuteRawCmd e -> e.command();
            default -> cmd.toString();
          };
      System.out.println("🤔 Agent 思考中... (" + taskDesc + ")");
    }

    // 通过 AliceCliLauncher.dispatchCommand 分发
    int exitCode = AliceCliLauncher.dispatchCommand(cmd);

    if (exitCode != AliceCliLauncher.EXIT_SUCCESS) {
      System.err.println("命令执行返回非零退出码: " + exitCode);
    }
  }

  // ========== 辅助 ==========

  private void printWelcome() {
    System.out.println();
    System.out.println("╔══════════════════════════════════════════╗");
    System.out.println("║      Alice Agent v1.0 — Interactive CLI ║");
    System.out.println("╠══════════════════════════════════════════╣");
    System.out.println("║  /help       显示帮助信息                 ║");
    System.out.println("║  /exit 或 Ctrl+D  退出会话               ║");
    System.out.println("║  Ctrl+C      取消当前输入                ║");
    System.out.println("║  Tab         命令/参数补全               ║");
    System.out.println("║  Ctrl+R      搜索历史命令                 ║");
    System.out.println("╚══════════════════════════════════════════╝");
    System.out.println();
  }

  private void ensureHistoryDir() {
    try {
      Path historyPath = resolveHistoryFile();
      Path dir = historyPath.getParent();
      if (dir != null && !Files.exists(dir)) {
        Files.createDirectories(dir);
      }
    } catch (IOException e) {
      logger.warn("Could not create history directory: {}", e.getMessage());
    }
  }

  private static Path resolveHistoryFile() {
    return Paths.get(HISTORY_FILE).toAbsolutePath().normalize();
  }

  private String traceId() {
    return UUID.randomUUID().toString().substring(0, 12);
  }

  // ========== 关闭 ==========

  @Override
  public void close() {
    this.running = false;
    try {
      terminal.close();
    } catch (IOException e) {
      logger.warn("Error closing terminal", e);
    }
    try {
      agent.close();
    } catch (Exception e) {
      logger.warn("Error closing agent", e);
    }
  }

  // ========================================================================
  // 高亮器：斜杠命令着色
  // ========================================================================

  /** 命令高亮器，为斜杠命令提供视觉区分。 */
  static class CommandHighlighter extends DefaultHighlighter {

    @Override
    public org.jline.utils.AttributedString highlight(LineReader reader, String buffer) {
      if (buffer == null || buffer.isBlank()) {
        return super.highlight(reader, buffer);
      }

      // 对于斜杠命令，可以返回自定义着色
      // 目前委托给 DefaultHighlighter 处理（错误模式高亮等）
      return super.highlight(reader, buffer);
    }
  }
}
