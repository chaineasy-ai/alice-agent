/*
 * Alice Agent — App 模块入口点
 *
 * 对应设计文档 §2.2 中 AliceApp 实体：
 *   负责 JVM 级别的初始化，如日志加载、环境变量检查、
 *   命令行参数解析以及向 AliceAgent 的转交控制。
 */
package org.cland.alice.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * AliceAgent 系统的 JVM 入口点。
 * <p>
 * 职责（对应设计文档 §3 业务流程图）：
 * <ol>
 *   <li>初始化 JVM 级基础设施（日志、ShutdownHook）</li>
 *   <li>调用 {@link AliceAgent} 启动完整的应用生命周期</li>
 *   <li>将退出码传播给 JVM</li>
 * </ol>
 *
 * <p>
 * 退出码约定：
 * <ul>
 *   <li>{@code 0} — 正常退出</li>
 *   <li>{@code 1} — 运行时错误</li>
 *   <li>{@code 2} — 参数/配置错误</li>
 *   <li>{@code 130} — 用户手动中断（Ctrl+C）</li>
 * </ul>
 */
public final class AliceApp {

    private static final Logger logger = LoggerFactory.getLogger(AliceApp.class);

    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_RUNTIME_ERROR = 1;
    public static final int EXIT_PARAM_ERROR = 2;
    public static final int EXIT_INTERRUPTED = 130;

    private AliceApp() {
        // 工具类，不可实例化
    }

    /**
     * 系统主入口。
     *
     * @param args 命令行参数
     */
    static void main(String[] args) {
        // 1. 注册 JVM 关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
            logger.info("JVM shutting down...")
        ));

        // 2. 执行初始化检查
        try {
            initializeEnvironment();
        } catch (Exception e) {
            System.err.println("[FATAL] Environment initialization failed: " + e.getMessage());
            System.exit(EXIT_RUNTIME_ERROR);
            return;
        }

        // 3. 委托给应用引导程序
        int exitCode = AliceAgent.bootstrap(args);

        // 4. 退出
        logger.info("Alice Agent exiting with code {}", exitCode);
        System.exit(exitCode);
    }

    // ========================================================================
    // 环境初始化
    // ========================================================================

    /**
     * JVM 级别的基础设施初始化。
     * <p>
     * 对应设计文档 §3 流程图中的 "加载 application.yaml" 和前置检查步骤。
     * 当前实现为轻量版本，后续可扩展为加载 YAML / 环境变量合并配置。
     */
    private static void initializeEnvironment() {
        logger.info("Initializing Alice Agent environment...");

        // 检查 Java 版本
        String javaVersion = System.getProperty("java.version");
                logger.debug("Java version: {}", javaVersion);

        // 检查关键环境变量（非阻塞，仅警告）
        checkEnvVar("OPENAI_API_KEY", "LLM calls will be unavailable");
        checkEnvVar("ANTHROPIC_API_KEY", "Anthropic models will be unavailable");

        logger.info("Environment initialized successfully");
    }

    /**
     * 检查环境变量是否设置，若未设置则记录警告。
     */
    private static void checkEnvVar(String name, String hint) {
        if (System.getenv(name) == null || System.getenv(name).isEmpty()) {
        logger.warn("Environment variable {} is not set. {}.", name, hint);
        }
    }
}
