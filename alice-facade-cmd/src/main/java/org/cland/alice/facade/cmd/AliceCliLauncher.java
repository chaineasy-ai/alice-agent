package org.cland.alice.facade.cmd;

import java.util.UUID;
import org.cland.alice.agent.command.AgentCommand;
import org.cland.alice.agent.command.CapabilityCmd;
import org.cland.alice.agent.command.ControlCmd;
import org.cland.alice.facade.cmd.config.CommandParser;
import org.cland.alice.facade.cmd.config.CommandParser.ParseException;
import org.cland.alice.facade.cmd.config.RunConfig;
import org.cland.alice.facade.cmd.render.OutputRenderer;
import org.cland.alice.model.ModelProvider;
import org.cland.alice.model.supplier.OpenAiSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Alice CLI 模块的入口点。
 *
 * <p>对应设计文档中 {@code AliceCliLauncher} 的角色：
 *
 * <ul>
 *   <li>解析 CLI 命令参数（委托给 {@link CommandParser}）
 *   <li>初始化核心环境（ModelProvider 等）
 *   <li>对于 {@code run} 子命令，驱动 {@link ExecutionCoordinator} 执行任务
 *   <li>对于其他子命令（预留），通过 {@link AgentCommand} 抽象层路由
 *   <li>映射退出码并退出 JVM
 * </ul>
 *
 * <p>退出码映射（符合设计文档）：
 *
 * <ul>
 *   <li>{@code 0} — 任务执行成功
 *   <li>{@code 1} — 运行时错误（Agent 无法完成目标）
 *   <li>{@code 2} — 命令参数错误
 *   <li>{@code 130} — 用户手动中断（Ctrl+C）
 * </ul>
 */
public final class AliceCliLauncher {

  private static final Logger logger = LoggerFactory.getLogger(AliceCliLauncher.class);

  // 退出码常量
  public static final int EXIT_SUCCESS = 0;
  public static final int EXIT_RUNTIME_ERROR = 1;
  public static final int EXIT_PARAM_ERROR = 2;
  public static final int EXIT_INTERRUPTED = 130;

  private AliceCliLauncher() {
    // 工具类，不可实例化
  }

  /**
   * CLI 主入口点。
   *
   * @param args 命令行参数
   */
  public static void main(String[] args) {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.err.println("\nReceived shutdown signal. Exiting...");
                }));

    int exitCode = run(args);
    System.exit(exitCode);
  }

  /**
   * CLI 运行逻辑（可测试的主逻辑）。
   *
   * @param args 命令行参数
   * @return 退出码
   */
  public static int run(String[] args) {
    try {
      // 1. 解析参数
      CommandParser parser = new CommandParser();
      RunConfig config = parser.parse(args);

      if (config == null) {
        // parse 返回 null 表示帮助/版本信息已打印
        return EXIT_PARAM_ERROR;
      }

      logger.info("RunConfig: {}", config);

      // 2. 初始化 ModelProvider
      initializeModelProvider();

      // 3. 创建渲染器
      OutputRenderer renderer = OutputRenderer.create(config);

      // 4. 执行任务
      ExecutionCoordinator coordinator = new ExecutionCoordinator(config, renderer);
      return coordinator.execute();

    } catch (ParseException e) {
      System.err.println("Error: " + e.getMessage());
      return e.exitCode();
    }
  }

  /**
   * 将用户输入解析为 {@link AgentCommand} 并分发。
   *
   * <p>用于交互式 CLI 会话（chat 模式），根据 AgentCommand 类型路由：
   *
   * <ul>
   *   <li>{@code AcquireGoalCmd} / {@code ExecuteRawCmd} → Agent 核心执行
   *   <li>{@code SwitchModelCmd} → 模型切换
   *   <li>{@code RegisterSkillCmd} / {@code UpdateRulesCmd} / {@code ReloadKernelCmd} → 能力装载
   *   <li>{@code ResetSessionCmd} / {@code InterruptCmd} → 生命周期控制
   * </ul>
   *
   * @param input 用户原始输入
   * @return 退出码
   */
  public static int dispatchCommand(String input) {
    String sessionId = UUID.randomUUID().toString().substring(0, 8);
    String traceId = UUID.randomUUID().toString().substring(0, 12);

    AgentCommand cmd = AgentCommand.parse(input, sessionId, traceId);
    if (cmd == null) {
      System.err.println("Unrecognized command: " + input);
      return EXIT_PARAM_ERROR;
    }

    logger.info(
        "Dispatching AgentCommand: {} (session={})", cmd.getClass().getSimpleName(), sessionId);

    return switch (cmd) {
      case org.cland.alice.agent.command.ExecutionCmd.AcquireGoalCmd run -> {
        System.out.println("Executing goal: " + run.goal());
        yield EXIT_SUCCESS;
      }
      case org.cland.alice.agent.command.ExecutionCmd.ExecuteRawCmd exec -> {
        System.out.println("Executing raw: " + exec.command());
        yield EXIT_SUCCESS;
      }
      case CapabilityCmd.RegisterSkillCmd skill -> {
        System.out.println("Registering skill: " + skill.skillRef());
        yield EXIT_SUCCESS;
      }
      case CapabilityCmd.UpdateRulesCmd rules -> {
        System.out.println("Updating rules: " + rules.rulesRef());
        yield EXIT_SUCCESS;
      }
      case CapabilityCmd.ReloadKernelCmd reload -> {
        System.out.println("Reloading kernel...");
        yield EXIT_SUCCESS;
      }
      case org.cland.alice.agent.command.AlignmentCmd.SwitchModelCmd model -> {
        System.out.println("Switching model to: " + model.modelId());
        yield EXIT_SUCCESS;
      }
      case ControlCmd.ResetSessionCmd reset -> {
        System.out.println("Resetting session: " + reset.sessionId());
        yield EXIT_SUCCESS;
      }
      case ControlCmd.FeedbackCmd fb -> {
        System.out.println("Feedback received: " + fb.message());
        yield EXIT_SUCCESS;
      }
      case ControlCmd.InterruptCmd interrupt -> {
        System.out.println("Interrupted: " + interrupt.cause());
        yield EXIT_SUCCESS;
      }
      case null, default -> {
        System.err.println("Unknown command type");
        yield EXIT_PARAM_ERROR;
      }
    };
  }

  // ========================================================================
  // 初始化
  // ========================================================================

  /**
   * 初始化模型提供器。
   *
   * <p>注册内置模型，并从环境变量中读取 API Key 注册外部供应商。
   */
  private static void initializeModelProvider() {
    try {
      ModelProvider provider = ModelProvider.getInstance();
      provider.registerBuiltinModels();

      String apiKey = System.getenv("OPENAI_API_KEY");
      if (apiKey != null && !apiKey.isEmpty()) {
        provider.registerSupplier(new OpenAiSupplier(apiKey));
        logger.info("OpenAI supplier registered");
      } else {
        logger.warn("OPENAI_API_KEY not set. Set it via environment variable to enable LLM calls.");
      }
    } catch (Exception e) {
      logger.warn("ModelProvider initialization failed (some features may be unavailable)", e);
    }
  }
}
