package org.cland.alice.facade.cmd;

import org.cland.alice.facade.cmd.config.CommandParser;
import org.cland.alice.facade.cmd.config.CommandParser.ParseException;
import org.cland.alice.facade.cmd.config.RunConfig;
import org.cland.alice.facade.cmd.render.OutputRenderer;
import org.cland.alice.model.ModelProvider;
import org.cland.alice.model.supplier.OpenAiSupplier;

/**
 * Alice CLI 模块的入口点。
 * <p>
 * 对应设计文档中 {@code AliceCliLauncher} 的角色：
 * <ul>
 *   <li>解析 CLI 命令参数（委托给 {@link CommandParser}）</li>
 *   <li>初始化核心环境（ModelProvider 等）</li>
 *   <li>驱动 {@link ExecutionCoordinator} 执行任务</li>
 *   <li>映射退出码并退出 JVM</li>
 * </ul>
 *
 * <p>
 * 退出码映射（符合设计文档）：
 * <ul>
 *   <li>{@code 0} — 任务执行成功</li>
 *   <li>{@code 1} — 运行时错误（Agent 无法完成目标）</li>
 *   <li>{@code 2} — 命令参数错误</li>
 *   <li>{@code 130} — 用户手动中断（Ctrl+C）</li>
 * </ul>
 */
public final class AliceCliLauncher {

    private static final System.Logger logger = System.getLogger(AliceCliLauncher.class.getName());

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
        // 注册 JVM 关闭钩子处理 Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
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

            logger.log(System.Logger.Level.INFO, "RunConfig: {0}", config);

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

    // ========================================================================
    // 初始化
    // ========================================================================

    /**
     * 初始化模型提供器。
     * <p>
     * 注册内置模型，并从环境变量中读取 API Key 注册外部供应商。
     */
    private static void initializeModelProvider() {
        try {
            ModelProvider provider = ModelProvider.getInstance();
            provider.registerBuiltinModels();

            String apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey != null && !apiKey.isEmpty()) {
                provider.registerSupplier(new OpenAiSupplier(apiKey));
                logger.log(System.Logger.Level.INFO, "OpenAI supplier registered");
            } else {
                logger.log(System.Logger.Level.WARNING,
                    "OPENAI_API_KEY not set. Set it via environment variable to enable LLM calls.");
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING,
                "ModelProvider initialization failed (some features may be unavailable)", e);
        }
    }
}
