package org.cland.alice.facade.tui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.env.adapter.EnvEvent;
import org.cland.alice.facade.tui.bridge.EventBridge;
import org.cland.alice.facade.tui.bridge.TuiEvent;
import org.cland.alice.facade.tui.state.TuiState;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * AliceTuiLauncher：TUI 外观模块的入口启动器。
 * <p>
 * 对应设计文档 §2 中的 AliceTuiLauncher，负责：
 * <ul>
 *   <li>初始化所有子模块（Agent, EventBridge, ScreenManager）</li>
 *   <li>建立事件监听链路</li>
 *   <li>进入主事件循环</li>
 * </ul>
 */
public class AliceTuiLauncher implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AliceTuiLauncher.class);

    private final Agent agent;
    private final EventBridge eventBridge;
    private final ScreenManager screenManager;

    private volatile boolean running;

    // ========== 构造 ==========

    public AliceTuiLauncher() throws IOException {
        this(AgentConfig.defaults());
    }

    public AliceTuiLauncher(AgentConfig config) throws IOException {
        // 1. 创建 Agent
        this.agent = new Agent(config);

        // 2. 创建 EventBridge
        this.eventBridge = new EventBridge();

        // 3. 创建 ScreenManager
        this.screenManager = new ScreenManager(eventBridge);
        this.running = true;

        // 4. 设置回调
        setupCallbacks();

        // 5. 设置初始状态
        this.screenManager.layout().header().setModel(config.defaultModelId());
    }

    /** 使用外部 Agent 实例构造 */
    public AliceTuiLauncher(Agent agent) throws IOException {
        this.agent = agent;
        this.eventBridge = new EventBridge();
        this.screenManager = new ScreenManager(eventBridge);
        this.running = true;
        setupCallbacks();
        this.screenManager.layout().header().setModel(agent.config().defaultModelId());
    }

    // ========== 设置回调 ==========

    private void setupCallbacks() {
        screenManager
            .onTaskSubmit(this::submitTask)
            .onExit(() -> this.running = false)
            .onModelSwitch(modelId -> {
                // 模型切换：仅更新显示，实际由 /model 命令处理
            logger.info("Model switch requested: {}", modelId);
            });

        // 连接 Agent 事件到 EventBridge
        hookAgentEvents();
    }

    /**
     * 钩子：将 Agent 核心事件连接到 EventBridge。
     * <p>
     * 此处通过拦截 AgentExecutor 产生的 StepResult 来生成 TUI 事件。
     * 更完整的实现应使用 Agent 内部的监听器模式（如 Vert.x event bus）。
     */
    private void hookAgentEvents() {
        // 注意：当前 Agent 使用同步/异步 API，尚未发布内部事件。
        // 以下代码演示了如何桥接。在实际应用中，可以扩展现有 API
        // 或监听 AgentExecutor 的 Future 回调。
        // 暂不实现，待 AgentCore 发布完整事件后再完善。
    }

    // ========== 启动 ==========

    /**
     * 启动 TUI。
     */
    public void start() throws IOException {
        logger.info("Starting Alice Agent TUI...");
        screenManager.start();
        eventBridge.onChatMessage("System", "欢迎使用 Alice Agent TUI！");
        eventBridge.onChatMessage("System", "输入 /help 查看可用命令。");
    }

    // ========== 主循环 ==========

    /**
     * 运行主事件循环。
     * <p>
     * 对应设计文档 §4 业务流程中的主事件循环。
     */
    public void run() {
        logger.info("Alice Agent TUI entering main loop.");

        try {
            while (running) {
                // 从键盘读取输入（阻塞式）
                KeyStroke keyStroke = screenManager.screen().readInput();

                if (keyStroke == null) {
                    Thread.sleep(50);
                    continue;
                }

                // 处理输入
                boolean shouldContinue = screenManager.handleInput(keyStroke);
                if (!shouldContinue) {
        logger.info("Exit requested via keyboard.");
                    break;
                }
            }
        } catch (IOException e) {
        logger.error("IO error in main loop", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.info("Main loop interrupted.");
        } finally {
            shutdown();
        }
    }

    // ========== 任务提交 ==========

    /**
     * 提交任务给 Agent 执行（异步）。
     *
     * @param input 用户输入
     */
    private void submitTask(String input) {
                logger.info("Submitting task: {}", input);

        CompletableFuture.runAsync(() -> {
            try {
                // 同步执行 Agent 任务
                String result = agent.ask(input);

                // 任务完成
                eventBridge.onTaskComplete(result, "Agent 执行完成");
                screenManager.state().transitionTo(TuiState.State.IDLE);

            } catch (Exception e) {
            logger.error("Task execution failed", e);
                eventBridge.onTaskError(e.getMessage());
                screenManager.state().transitionTo(TuiState.State.ERROR);
            }
        });
    }

    // ========== 关闭 ==========

    private void shutdown() {
        logger.info("Shutting down Alice Agent TUI...");
        try {
            screenManager.close();
        } catch (Exception e) {
        logger.warn("Error closing screen manager", e);
        }
        try {
            eventBridge.close();
        } catch (Exception e) {
        logger.warn("Error closing event bridge", e);
        }
        try {
            agent.close();
        } catch (Exception e) {
        logger.warn("Error closing agent", e);
        }
        logger.info("Alice Agent TUI shut down complete.");
    }

    @Override
    public void close() {
        running = false;
        shutdown();
    }

    // ========== Main 入口 ==========

    /**
     * alice-facade-tui 模块的主入口。
     * <p>
     * 启动 TUI 界面，连接 Agent 核心。
     */
    public static void main(String[] args) {
        try {
            // 检查环境变量
            String apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey == null || apiKey.isEmpty()) {
                System.err.println("警告: 未设置 OPENAI_API_KEY，LLM 功能将不可用。");
                System.err.println("请设置环境变量后再运行。");
                // 仍然可以启动 TUI，但 Agent 无法调用 LLM
            }

            // 创建配置
            AgentConfig config = AgentConfig.builder()
                .defaultModelId("gpt-4o-mini")
                .maxIterations(10)
                .build();

            // 启动 TUI
            AliceTuiLauncher launcher = new AliceTuiLauncher(config);
            launcher.start();
            launcher.run();

        } catch (IOException e) {
            System.err.println("TUI 启动失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
