package org.cland.alice.facade.cmd.config;

import java.util.UUID;
import java.util.concurrent.Callable;
import org.cland.alice.agent.command.AgentCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CLI 命令参数解析器。
 *
 * <p>基于 picocli 实现，解析命令行参数并转换为 {@link RunConfig} / {@link AgentCommand}。 对应设计文档中 {@code
 * CommandParser} 组件的职责。
 *
 * <p>子命令设计（picocli 多级命令）：
 *
 * <ul>
 *   <li>{@code alice run} — 执行单次任务
 *   <li>{@code alice chat} — 交互式对话（预留）
 *   <li>{@code alice tools} — 列出工具（预留）
 *   <li>{@code alice config} — 配置管理（预留）
 * </ul>
 *
 * <p>除 {@code run} 外，其他子命令通过 {@link AgentCommand} 抽象指令层与 Agent 核心交互。 解析失败时不会调用 {@code
 * System.exit()}，而是抛出 {@link ParseException}， 由调用方（如 {@code AliceCliLauncher}）处理退出码映射。
 */
public class CommandParser {

  private static final Logger logger = LoggerFactory.getLogger(CommandParser.class);

  /** 当前会话 ID（CLI 单次执行自动生成） */
  private String sessionId;

  /** 解析失败时抛出的异常，携带合适的退出码。 */
  public static final class ParseException extends RuntimeException {
    private final int exitCode;

    public ParseException(int exitCode, String message) {
      super(message);
      this.exitCode = exitCode;
    }

    public int exitCode() {
      return exitCode;
    }
  }

  public CommandParser() {
    this.sessionId = UUID.randomUUID().toString().substring(0, 8);
  }

  /** 设置会话 ID（用于 AgentCommand 追踪）。 */
  public CommandParser sessionId(String sessionId) {
    this.sessionId = sessionId;
    return this;
  }

  /**
   * 解析命令行参数，返回 {@link RunConfig}。
   *
   * <p>如果解析失败（参数错误或帮助信息），返回 {@code null}。 对于致命错误（未知子命令、参数错误），抛出 {@link ParseException}。
   *
   * @param args 原始命令行参数数组
   * @return 解析成功的 RunConfig，失败或帮助时返回 null
   * @throws ParseException 如果参数错误导致无法继续
   */
  public RunConfig parse(String[] args) {
    CliRoot root = new CliRoot();
    CommandLine cmdLine = new CommandLine(root);

    // 注册子命令
    cmdLine.addSubcommand("run", new RunCommand());
    cmdLine.addSubcommand("chat", new ChatCommand());
    cmdLine.addSubcommand("tools", new ToolsCommand());
    cmdLine.addSubcommand("config", new ConfigCommand());
    cmdLine.addSubcommand("routine", new RoutineCommand());

    try {
      CommandLine.ParseResult parseResult = cmdLine.parseArgs(args);

      // 处理顶层帮助
      if (parseResult.isUsageHelpRequested()) {
        cmdLine.usage(System.out);
        return null;
      }
      if (parseResult.isVersionHelpRequested()) {
        cmdLine.printVersionHelp(System.out);
        return null;
      }

      // 查找子命令
      CommandLine.ParseResult subResult = parseResult.subcommand();
      if (subResult == null) {
        cmdLine.usage(System.err);
        throw new ParseException(2, "No subcommand given. Use 'alice run --task \"...\"'");
      }

      // 检查子命令内的帮助/版本请求
      if (subResult.isUsageHelpRequested() || subResult.isVersionHelpRequested()) {
        subResult.commandSpec().commandLine().usage(System.out);
        return null;
      }

      Object sub = subResult.commandSpec().userObject();
      if (sub instanceof RunCommand run) {
        return run.toRunConfig();
      }

      if (sub instanceof ChatCommand chat) {
        return RunConfig.builder().task("chat").chat(true).build();
      }

      if (sub instanceof ToolsCommand) {
        throw new ParseException(1, "'tools' subcommand is not yet implemented.");
      }

      if (sub instanceof ConfigCommand) {
        throw new ParseException(1, "'config' subcommand is not yet implemented.");
      }

      if (sub instanceof RoutineCommand routine) {
        return routine.toRunConfig();
      }

      cmdLine.usage(System.err);
      throw new ParseException(2, "Unknown subcommand");

    } catch (CommandLine.ParameterException e) {
      System.err.println("Error: " + e.getMessage());
      System.err.println();
      cmdLine.usage(System.err);
      throw new ParseException(2, e.getMessage());
    }
  }

  /**
   * 将用户输入解析为 {@link AgentCommand} 抽象指令。
   *
   * <p>自然语言直接转为 AcquireGoalCmd；斜杠命令转为对应指令类型。 用于交互式会话场景（chat 模式）。
   *
   * @param input 用户原始输入
   * @return 解析后的 AgentCommand，无法识别时返回 null
   */
  public AgentCommand parseToAgentCommand(String input) {
    return AgentCommand.parse(input, sessionId, traceId());
  }

  private String traceId() {
    return UUID.randomUUID().toString().substring(0, 12);
  }

  // ========================================================================
  // 顶层 CLI 根命令
  // ========================================================================

  @Command(
      name = "alice",
      version = "Alice Agent CLI 0.1.0",
      description = "Alice Agent — AI-powered autonomous agent",
      subcommandsRepeatable = true,
      mixinStandardHelpOptions = true,
      usageHelpAutoWidth = true)
  private static class CliRoot implements Callable<Integer> {

    @Override
    public Integer call() {
      new CommandLine(this).usage(System.err);
      return 2;
    }
  }

  // ========================================================================
  // "run" 子命令
  // ========================================================================

  @Command(
      name = "run",
      description = "Execute a single task and exit",
      mixinStandardHelpOptions = true)
  private static class RunCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Task description for the agent")
    private String task;

    @Option(
        names = {"-m", "--model"},
        description = "Override default model (e.g. gpt-4o, claude-3.5-sonnet)")
    private String model;

    @Option(
        names = {"-v", "--verbose"},
        description = "Print detailed thought/execution process")
    private boolean verbose;

    @Option(names = "--json", description = "Output results in JSON format")
    private boolean jsonOutput;

    @Option(names = "--timeout", description = "Task timeout in seconds (default: 180)")
    private long timeoutSeconds;

    @Override
    public Integer call() {
      return 0;
    }

    /** 将 CLI 参数转换为 RunConfig */
    RunConfig toRunConfig() {
      RunConfig.Builder builder =
          RunConfig.builder().task(task).verbose(verbose).jsonOutput(jsonOutput);

      if (model != null && !model.isBlank()) {
        builder.model(model);
      }
      if (timeoutSeconds > 0) {
        builder.timeoutSeconds(timeoutSeconds);
      }

      return builder.build();
    }

    /** 将 CLI 参数转换为 AgentCommand（AcquireGoalCmd） */
    AgentCommand toAgentCommand(String sessionId) {
      return new org.cland.alice.agent.command.ExecutionCmd.AcquireGoalCmd(
          task, sessionId, UUID.randomUUID().toString().substring(0, 12));
    }
  }

  // ========================================================================
  // 预留子命令占位（可通过 AgentCommand 扩展）
  // ========================================================================

  @Command(
      name = "chat",
      description = "Start an interactive conversation (session support)",
      mixinStandardHelpOptions = true)
  private static class ChatCommand implements Callable<Integer> {
    @Override
    public Integer call() {
      try {
        org.cland.alice.facade.cmd.chat.JLineChatSession chatSession =
            new org.cland.alice.facade.cmd.chat.JLineChatSession();
        chatSession.run();
        return 0;
      } catch (Exception e) {
        System.err.println("Failed to start chat session: " + e.getMessage());
        return 1;
      }
    }
  }

  @Command(
      name = "tools",
      description = "List all loaded tools and their descriptions",
      mixinStandardHelpOptions = true)
  private static class ToolsCommand implements Callable<Integer> {
    @Option(
        names = {"-d", "--detail"},
        description = "Show detailed tool information")
    private boolean detail;

    @Override
    public Integer call() {
      System.err.println("Tools listing not yet implemented");
      return 1;
    }
  }

  @Command(
      name = "config",
      description = "Manage model keys / global configuration",
      mixinStandardHelpOptions = true)
  private static class ConfigCommand implements Callable<Integer> {
    @Parameters(description = "Config action (get/set)")
    private String action;

    @Override
    public Integer call() {
      System.err.println("Config management not yet implemented");
      return 1;
    }
  }

  // ========================================================================
  // "routine" 子命令
  // ========================================================================

  @Command(
      name = "routine",
      description = "Register or manage scheduled routine tasks",
      mixinStandardHelpOptions = true)
  private static class RoutineCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Cron expression or routine definition")
    private String cronExpression;

    @Option(
        names = {"--list", "-l"},
        description = "List registered routines")
    private boolean listRoutines;

    @Option(
        names = {"--remove", "-r"},
        description = "Remove a routine by ID")
    private String removeRoutineId;

    @Override
    public Integer call() {
      return 0;
    }

    /** 将 CLI 参数转换为 RunConfig */
    RunConfig toRunConfig() {
      RunConfig.Builder builder = RunConfig.builder().task("routine");
      if (cronExpression != null && !cronExpression.isBlank()) {
        builder.routineCron(cronExpression);
      }
      if (listRoutines) {
        builder.listRoutines(true);
      }
      return builder.build();
    }

    /** 将 CLI 参数转换为 AgentCommand（RegisterRoutineCmd） */
    AgentCommand toAgentCommand(String sessionId) {
      String expr = (cronExpression != null) ? cronExpression : "";
      String traceId = UUID.randomUUID().toString().substring(0, 12);
      return AgentCommand.parse("/routine " + expr, sessionId, traceId);
    }
  }
}
