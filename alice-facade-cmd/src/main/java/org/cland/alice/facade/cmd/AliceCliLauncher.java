package org.cland.alice.facade.cmd;

import java.util.Map;
import java.util.UUID;
import org.cland.alice.agent.command.AgentCommand;
import org.cland.alice.agent.command.CapabilityCmd;
import org.cland.alice.agent.command.ControlCmd;
import org.cland.alice.agent.subagent.SubAgentManager;
import org.cland.alice.agent.subagent.SubAgentRecord;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.facade.cmd.chat.JLineChatSession;
import org.cland.alice.facade.cmd.config.AliceConfigStore;
import org.cland.alice.facade.cmd.config.CommandParser;
import org.cland.alice.facade.cmd.config.CommandParser.ParseException;
import org.cland.alice.facade.cmd.config.RunConfig;
import org.cland.alice.facade.cmd.render.OutputRenderer;
import org.cland.alice.model.ModelConfigLoader;
import org.cland.alice.model.ModelProvider;
import org.cland.alice.model.supplier.ClaudeSupplier;
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
        // parse 返回 null 表示帮助/版本信息已打印 → 成功退出
        return EXIT_SUCCESS;
      }

      logger.info("RunConfig: {}", config);

      // 2. 工具列表命令 — 不走 Agent 执行
      if (config.listTools()) {
        return handleListTools(config);
      }

      // 3. 配置管理命令 — 不走 Agent 执行
      if (config.configAction() != null) {
        return handleConfig(config);
      }

      // 4. 初始化 ModelProvider
      initializeModelProvider();

      // 4. 创建渲染器
      OutputRenderer renderer = OutputRenderer.create(config);

      // 5. 执行任务
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

    return dispatchCommand(cmd);
  }

  /**
   * 分发已解析的 {@link AgentCommand}。
   *
   * <p>由 {@link JLineChatSession} 等已预先解析的调用方使用，避免重复解析。
   *
   * @param cmd 已解析的 AgentCommand
   * @return 退出码
   */
  public static int dispatchCommand(AgentCommand cmd) {
    if (cmd == null) {
      System.err.println("Cannot dispatch null command");
      return EXIT_PARAM_ERROR;
    }

    String sessionId =
        cmd.sessionId() != null ? cmd.sessionId() : UUID.randomUUID().toString().substring(0, 8);

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
      case org.cland.alice.agent.command.ControlCmd.ClearContextCmd clear -> {
        System.out.println("Clearing context (session=" + clear.sessionId() + ")");
        System.out.println("上下文已清除");
        yield EXIT_SUCCESS;
      }
      case org.cland.alice.agent.command.ControlCmd.ViewContextCmd view -> {
        System.out.println("Viewing context (session=" + view.sessionId() + ")");
        System.out.println("── 上下文状态 ──");
        System.out.println("会话 ID: " + view.sessionId());
        System.out.println("Token 占用: N/A (Memory 模块待集成)");
        System.out.println("消息滑动窗口: N/A");
        yield EXIT_SUCCESS;
      }
      case org.cland.alice.agent.command.ControlCmd.CompactContextCmd compact -> {
        System.out.println("Compacting context (session=" + compact.sessionId() + ")");
        System.out.println("上下文压缩请求已提交（待 Memory 模块提供总结接口）");
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
      case org.cland.alice.agent.command.RoutineTimeCmd.RegisterRoutineCmd routine -> {
        System.out.println("Registering routine: " + routine.cronExpression());
        yield EXIT_SUCCESS;
      }
      case org.cland.alice.agent.command.SpawnSubAgentCmd spawn -> {
        System.out.println("Spawning sub-agent: " + spawn.goal());
        // 创建 SubAgentManager 并生成子 Agent
        String subSessionId = UUID.randomUUID().toString().substring(0, 8);
        SubAgentManager mgr = new SubAgentManager(subSessionId);
        SubAgentRecord record = mgr.spawnSubAgent(spawn.goal(), spawn.model());
        System.out.println("Sub-agent " + record.id() + " spawned with goal: " + spawn.goal());
        yield EXIT_SUCCESS;
      }
      case org.cland.alice.agent.command.ConnectSubAgentCmd connect -> {
        System.out.println(
            "Connecting to ACP agent: " + connect.name() + " at " + connect.acpEndpoint());
        yield EXIT_SUCCESS;
      }
      case org.cland.alice.agent.command.ListSubAgentsCmd list -> {
        System.out.println("Listing sub-agents (not yet wired to a SubAgentManager)");
        yield EXIT_SUCCESS;
      }
      case org.cland.alice.agent.command.CancelSubAgentCmd cancel -> {
        System.out.println("Cancel sub-agent: " + cancel.subAgentId() + " (not yet wired)");
        yield EXIT_SUCCESS;
      }
      case org.cland.alice.agent.command.GetSubAgentResultsCmd results -> {
        System.out.println(
            "Get results for sub-agent: " + results.subAgentId() + " (not yet wired)");
        yield EXIT_SUCCESS;
      }
      case org.cland.alice.agent.command.SendToSubAgentCmd send -> {
        System.out.println(
            "Send message to sub-agent " + send.subAgentId() + ": " + send.message());
        yield EXIT_SUCCESS;
      }
      case org.cland.alice.agent.command.PromptSubAgentCmd prompt -> {
        System.out.println("Prompt sub-agent " + prompt.subAgentId() + ": " + prompt.prompt());
        yield EXIT_SUCCESS;
      }
      case null, default -> {
        System.err.println("Unknown command type");
        yield EXIT_PARAM_ERROR;
      }
    };
  }

  // ========================================================================
  // 工具列表
  // ========================================================================

  /**
   * 处理 {@code alice tools} 子命令，列出 ToolRegistry 中的已注册工具。
   *
   * @param config 运行配置
   * @return 退出码
   */
  private static int handleListTools(RunConfig config) {
    try {
      var registry = org.cland.alice.tool.gateway.ToolRegistryHolder.INSTANCE;
      var tools = registry.allTools();

      if (tools.isEmpty()) {
        System.out.println("No tools registered.");
        System.out.println("Use 'alice run --skill <toolset>' or configure MCP to load tools.");
        return EXIT_SUCCESS;
      }

      System.out.println("Registered tools (" + tools.size() + "):");
      System.out.println();

      if (config.toolDetail()) {
        for (var meta : tools) {
          System.out.println("  ── " + meta.name() + " ──");
          System.out.println("     Description: " + meta.description());
          if (meta.inputSchema() != null && !meta.inputSchema().isEmpty()) {
            System.out.println("     Parameters:  " + meta.inputSchema());
          }
          System.out.println();
        }
      } else {
        for (var meta : tools) {
          System.out.println("  ⚡ " + meta.name() + " — " + meta.description());
        }
        System.out.println();
        System.out.println("Use 'alice tools --detail' to see parameter schemas.");
      }

      return EXIT_SUCCESS;
    } catch (Exception e) {
      System.err.println("Error listing tools: " + e.getMessage());
      return EXIT_RUNTIME_ERROR;
    }
  }

  // ========================================================================
  // 配置管理
  // ========================================================================

  /**
   * 处理 {@code alice config} 子命令。
   *
   * <p>支持:
   *
   * <ul>
   *   <li>{@code alice config} — 显示当前配置概览
   *   <li>{@code alice config get &lt;key&gt;} — 获取单个配置项
   *   <li>{@code alice config set &lt;key&gt; &lt;value&gt;} — 设置配置项（当前仅打印模拟）
   * </ul>
   */
  /** 全局配置存储实例（延迟初始化） */
  private static volatile AliceConfigStore configStore;

  /** 获取或创建配置存储实例。 */
  private static AliceConfigStore configStore() {
    if (configStore == null) {
      synchronized (AliceCliLauncher.class) {
        if (configStore == null) {
          configStore = new AliceConfigStore();
        }
      }
    }
    return configStore;
  }

  private static int handleConfig(RunConfig config) {
    try {
      String action = config.configAction();
      String key = config.configKey();
      String value = config.configValue();
      AliceConfigStore store = configStore();

      if ("set".equals(action)) {
        if (key == null || value == null) {
          System.err.println("Usage: alice config set <key> <value>");
          return EXIT_PARAM_ERROR;
        }
        store.set(key, value);
        System.out.println(
            "Config '" + key + "' set to '" + value + "' (persisted to ~/.alice/config.json)");
        return EXIT_SUCCESS;
      }

      if ("get".equals(action)) {
        if (key == null) {
          System.err.println("Usage: alice config get <key>");
          return EXIT_PARAM_ERROR;
        }
        printConfigValue(key, store);
        return EXIT_SUCCESS;
      }

      // "show" — 显示全部配置
      printAllConfig(store);
      return EXIT_SUCCESS;

    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      return EXIT_RUNTIME_ERROR;
    }
  }

  /**
   * 获取配置值的实际值（优先级：环境变量 > 配置文件 > 内建默认值）。
   *
   * @param key 点分隔的键名
   * @param store 配置存储
   * @return 值，或 {@code null} 表示未设置
   */
  private static String resolveConfigValue(String key, AliceConfigStore store) {
    // 1. 环境变量（最高优先级）
    String envName = configKeyToEnv(key);
    if (envName != null) {
      String envVal = System.getenv(envName);
      if (envVal != null && !envVal.isEmpty()) {
        return envVal;
      }
    }
    // 2. 配置文件
    String stored = store.get(key);
    if (stored != null) {
      return stored;
    }
    // 3. 内建默认值
    return switch (key) {
      case "default.model" -> AgentConfig.DEFAULT_MODEL;
      case "agent.max_iterations" -> String.valueOf(AgentConfig.DEFAULT_MAX_ITERATIONS);
      default -> null;
    };
  }

  /** 将配置键名映射为环境变量名。 */
  private static String configKeyToEnv(String key) {
    return switch (key) {
      case "openai.api_key", "providers.openai.api_key" -> "OPENAI_API_KEY";
      case "anthropic.api_key", "providers.anthropic.api_key" -> "ANTHROPIC_API_KEY";
      default -> null;
    };
  }

  /** 获取并打印单个配置项的值及其来源。 */
  private static void printConfigValue(String key, AliceConfigStore store) {
    String envName = configKeyToEnv(key);

    // 检查环境变量
    if (envName != null) {
      String envVal = System.getenv(envName);
      if (envVal != null && !envVal.isEmpty()) {
        System.out.println(
            key
                + " = "
                + envVal.substring(0, Math.min(8, envVal.length()))
                + "... (from env "
                + envName
                + ")");
        return;
      }
    }

    // 检查配置文件
    String stored = store.get(key);
    if (stored != null) {
      String display =
          key.contains("api_key")
              ? stored.substring(0, Math.min(8, stored.length())) + "..."
              : stored;
      System.out.println(key + " = " + display + " (from ~/.alice/config.json)");
      return;
    }

    // 检查内建默认值
    switch (key) {
      case "default.model" ->
          System.out.println(
              "default.model = " + AgentConfig.DEFAULT_MODEL + " (built-in default)");
      case "agent.max_iterations" ->
          System.out.println(
              "agent.max_iterations = "
                  + AgentConfig.DEFAULT_MAX_ITERATIONS
                  + " (built-in default)");
      default -> System.out.println(key + " = (not set)");
    }
  }

  /** 打印所有配置项。 */
  private static void printAllConfig(AliceConfigStore store) {
    System.out.println("=== Alice Agent Configuration ===");
    System.out.println();

    // 读取 providers 嵌套结构
    var all = store.getAll();
    System.out.println("-- Model Providers --");
    @SuppressWarnings("unchecked")
    var providers = (Map<String, Object>) all.get("providers");
    if (providers != null && !providers.isEmpty()) {
      for (var entry : providers.entrySet()) {
        String providerName = entry.getKey();
        System.out.println("  [" + providerName + "]");
        @SuppressWarnings("unchecked")
        var providerConfig = (Map<String, Object>) entry.getValue();
        if (providerConfig != null) {
          for (var pEntry : providerConfig.entrySet()) {
            String display =
                pEntry.getKey().contains("api_key")
                    ? pEntry
                            .getValue()
                            .toString()
                            .substring(0, Math.min(8, pEntry.getValue().toString().length()))
                        + "..."
                    : pEntry.getValue().toString();
            System.out.println("    " + pEntry.getKey() + " = " + display);
          }
        }
      }
    } else {
      // 回退到扁平键
      printConfigValue("openai.api_key", store);
      printConfigValue("anthropic.api_key", store);
    }

    System.out.println();
    System.out.println("-- Agent Defaults --");
    // 从嵌套或扁平读取
    String model = store.get("default.model");
    if (model == null) model = store.get("default_model");
    if (model != null) {
      System.out.println("default.model = " + model + " (from ~/.alice/config.json)");
    } else {
      printConfigValue("default.model", store);
    }

    String iterStr = store.get("agent.max_iterations");
    if (iterStr == null) iterStr = store.get("max_iterations");
    if (iterStr != null) {
      System.out.println("agent.max_iterations = " + iterStr + " (from ~/.alice/config.json)");
    } else {
      printConfigValue("agent.max_iterations", store);
    }

    System.out.println();
    System.out.println("Config file: ~/.alice/config.json");
    System.out.println("Use 'alice config get <key>' or 'alice config set <key> <value>'");
    System.out.println(
        "Known keys: openai.api_key, anthropic.api_key, default.model, agent.max_iterations");
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

      // 1. 加载 ~/.alice/model.json 配置文件
      try {
        ModelConfigLoader configLoader = new ModelConfigLoader();
        configLoader.load();
        configLoader.registerTo(provider);
        logger.info(
            "Loaded {} model provider(s) from ~/.alice/model.json",
            configLoader.getProviders().size());
      } catch (Exception e) {
        logger.debug("No model config found, using env vars: {}", e.getMessage());
      }

      // 2. 注册内置模型枚举
      provider.registerBuiltinModels();

      // 3. 从环境变量注册供应商（优先级低于配置文件）
      String openAiKey = System.getenv("OPENAI_API_KEY");
      if (openAiKey != null && !openAiKey.isEmpty()) {
        if (provider.getSupplier("gpt-4o-mini") == null) {
          provider.registerSupplier(new OpenAiSupplier(openAiKey));
          logger.info("OpenAI supplier registered from env var");
        }
      } else {
        logger.warn("OPENAI_API_KEY not set. Set it via environment variable to enable LLM calls.");
      }

      String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
      if (anthropicKey != null && !anthropicKey.isEmpty()) {
        if (provider.getSupplier("claude-3-5-sonnet-latest") == null) {
          provider.registerSupplier(new ClaudeSupplier(anthropicKey));
          logger.info("Anthropic Claude supplier registered from env var");
        }
      } else {
        logger.warn("ANTHROPIC_API_KEY not set. LLM calls via Anthropic will be unavailable.");
      }

      // 4. DeepSeek (OpenAI-compatible) 从环境变量注册
      String deepseekKey = System.getenv("DEEPSEEK_API_KEY");
      if (deepseekKey != null && !deepseekKey.isEmpty()) {
        if (provider.getSupplier("deepseek-v4-flash") == null) {
          provider.registerSupplier(
              new OpenAiSupplier(
                  "deepseek", deepseekKey, "https://api.deepseek.com/v1/chat/completions"));
          logger.info("DeepSeek supplier registered from env var (OpenAI-compatible)");
        }
      }
    } catch (Exception e) {
      logger.warn("ModelProvider initialization failed (some features may be unavailable)", e);
    }
  }
}
