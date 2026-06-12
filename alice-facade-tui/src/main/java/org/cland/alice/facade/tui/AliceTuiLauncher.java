package org.cland.alice.facade.tui;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.cland.alice.agent.command.AgentCommand;
import org.cland.alice.agent.command.AlignmentCmd;
import org.cland.alice.agent.command.CapabilityCmd;
import org.cland.alice.agent.command.ControlCmd;
import org.cland.alice.agent.command.ExecutionCmd;
import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.facade.tui.bridge.EventBridge;
import org.cland.alice.facade.tui.state.TuiState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AliceTuiLauncher：基于 JLine 3 的 TUI 外观模块入口启动器。
 *
 * <p>对应设计文档 §2 中的 AliceTuiLauncher 及 Layout.md 三层单线分割布局， 负责：
 *
 * <ul>
 *   <li>初始化所有子模块（Agent, EventBridge, ScreenManager）
 *   <li>建立事件监听链路
 *   <li>进入主输入循环
 * </ul>
 *
 * <p>基于 {@link AgentCommand} 抽象指令层：用户输入统一解析为 AgentCommand， 由 dispatchAgentCommand() 路由到 Agent
 * 核心或本地处理。
 */
public class AliceTuiLauncher implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(AliceTuiLauncher.class);

  private final Agent agent;
  private final EventBridge eventBridge;
  private final ScreenManager screenManager;

  private volatile boolean running;
  private volatile boolean shutdown;

  /** 当前会话 ID */
  private final String sessionId;

  // ========== 构造 ==========

  public AliceTuiLauncher() throws IOException {
    this(AgentConfig.defaults());
  }

  public AliceTuiLauncher(AgentConfig config) throws IOException {
    this.sessionId = UUID.randomUUID().toString().substring(0, 8);

    // 1. 创建 Agent
    this.agent = new Agent(config);

    // 2. 创建 EventBridge
    this.eventBridge = new EventBridge();

    // 3. 创建 ScreenManager（基于 JLine 3）
    this.screenManager = new ScreenManager(eventBridge);
    this.running = true;

    // 4. 设置回调
    setupCallbacks();

    // 5. 设置初始模型到状态栏（header 已精简为仅显示名称+版本）
    this.screenManager.layout().footer().setModel(config.defaultModelId());
  }

  /** 使用外部 Agent 实例构造 */
  public AliceTuiLauncher(Agent agent) throws IOException {
    this.sessionId = UUID.randomUUID().toString().substring(0, 8);
    this.agent = agent;
    this.eventBridge = new EventBridge();
    this.screenManager = new ScreenManager(eventBridge);
    this.running = true;
    setupCallbacks();
    this.screenManager.layout().footer().setModel(agent.config().defaultModelId());
  }

  // ========== 设置回调 ==========

  private void setupCallbacks() {
    screenManager
        .onTaskSubmit(this::submitAgentCommand)
        .onExit(() -> this.running = false)
        .onModelSwitch(
            modelId -> {
              logger.info("Model switch requested: {}", modelId);
            });

    // 连接 Agent 事件到 EventBridge
    hookAgentEvents();
  }

  /**
   * 钩子：将 Agent 核心事件连接到 EventBridge。
   *
   * <p>此处通过拦截 AgentExecutor 产生的 StepResult 来生成 TUI 事件。 更完整的实现应使用 Agent 内部的监听器模式。
   */
  private void hookAgentEvents() {
    // 待 AgentCore 发布完整事件后再完善
  }

  // ========== 启动 ==========

  /** 启动 TUI。 */
  public void start() throws IOException {
    logger.info("Starting Alice Agent TUI (JLine 3, three-panel layout)...");
    screenManager.start();
    eventBridge.onChatMessage("System", "欢迎使用 Alice Agent TUI！");
    eventBridge.onChatMessage("System", "输入 /help 查看可用命令。");
  }

  // ========== 主循环 ==========

  /**
   * 运行主输入循环。
   *
   * <p>基于 JLine 3 LineReader，原生支持 AUTO_MENU 向上补全弹窗。
   */
  public void run() {
    logger.info("Alice Agent TUI entering main input loop.");

    try {
      screenManager.runInputLoop();
    } catch (Exception e) {
      logger.error("Error in main input loop", e);
    } finally {
      shutdown();
    }
  }

  // ========== AgentCommand 分发 ==========

  /** 统一的 AgentCommand 分发入口。 */
  void dispatchAgentCommand(AgentCommand cmd) {
    if (cmd == null) return;

    logger.debug(
        "Dispatching AgentCommand: {} (session={})",
        cmd.getClass().getSimpleName(),
        cmd.sessionId());

    switch (cmd) {
      case ExecutionCmd.AcquireGoalCmd run -> submitTaskToAgent(run.task());
      case ExecutionCmd.ExecuteRawCmd exec -> submitTaskToAgent(exec.task());
      case CapabilityCmd.ReloadKernelCmd reload -> handleReload(reload);
      case CapabilityCmd cmd2 -> handleCapability(cmd2);
      case ControlCmd.ResetSessionCmd reset -> handleReset(reset);
      case ControlCmd.ClearContextCmd clear -> handleClearContext(clear);
      case ControlCmd.ViewContextCmd view -> handleViewContext(view);
      case ControlCmd.CompactContextCmd compact -> handleCompactContext(compact);
      case ControlCmd.FeedbackCmd feedback -> handleFeedback(feedback);
      case ControlCmd.InterruptCmd exit -> handleInterrupt(exit);
      case AlignmentCmd.SwitchModelCmd model -> handleModelSwitch(model);
      case null, default -> logger.warn("Unknown AgentCommand type: {}", cmd);
    }
  }

  /** 提交任务给 Agent 核心执行 */
  private void submitTaskToAgent(String task) {
    logger.info("Submitting task: {}", task);

    CompletableFuture.runAsync(
        () -> {
          try {
            String result = agent.ask(task);
            eventBridge.onTaskComplete(result, "Agent 执行完成");
            screenManager.state().transitionTo(TuiState.State.IDLE);
            screenManager.markContentDirty();
          } catch (Exception e) {
            logger.error("Task execution failed", e);
            eventBridge.onTaskError(e.getMessage());
            screenManager.state().transitionTo(TuiState.State.ERROR);
            screenManager.markContentDirty();
          }
        });
  }

  private void handleCapability(CapabilityCmd cmd) {
    logger.info(
        "Handling capability: {} resource={}", cmd.getClass().getSimpleName(), cmd.resource());
    eventBridge.onChatMessage("System", "能力装载: " + cmd.resource() + " (待实现完整 ResourceLoader)");
  }

  private void handleReload(CapabilityCmd.ReloadKernelCmd reload) {
    logger.info("Hot reload requested");
    eventBridge.onChatMessage("System", "热重载触发中... (待实现完整 ReloadKernel)");
  }

  private void handleReset(ControlCmd.ResetSessionCmd reset) {
    logger.info("Session reset requested: {}", reset.sessionId());
    eventBridge.onChatMessage("System", "会话已重置，上下文已清空");
  }

  private void handleInterrupt(ControlCmd.InterruptCmd interrupt) {
    logger.info("Interrupt requested: {}", interrupt.cause());
    if ("user-exit".equals(interrupt.cause()) || interrupt.cause().contains("exit")) {
      this.running = false;
    }
  }

  private void handleClearContext(ControlCmd.ClearContextCmd clear) {
    logger.info("Clear context requested: session={}", clear.sessionId());
    try {
      agent.clearMemory();
    } catch (Exception e) {
      logger.warn("Agent clearMemory not fully implemented, clearing UI only", e);
    }
    eventBridge.onChatMessage("System", "上下文已清除");
    screenManager.layout().thought().clear();
    screenManager.markContentDirty();
    if (screenManager.state().isRunning()) {
      screenManager.state().transitionTo(TuiState.State.IDLE);
    }
  }

  private void handleViewContext(ControlCmd.ViewContextCmd view) {
    logger.info("View context requested: session={}", view.sessionId());
    String contextInfo;
    try {
      contextInfo = agent.getActiveContext();
    } catch (Exception e) {
      logger.warn("Agent getActiveContext not fully implemented, using fallback", e);
      contextInfo = null;
    }

    if (contextInfo != null) {
      eventBridge.onChatMessage("System", contextInfo);
    } else {
      String sessionInfo = "会话 ID: " + view.sessionId();
      String modelInfo = "当前模型: " + screenManager.layout().footer().modelInfo();
      String statusInfo = "状态: " + screenManager.state().current().name();
      eventBridge.onChatMessage(
          "System",
          "── 上下文状态 ──\n"
              + sessionInfo
              + "\n"
              + modelInfo
              + "\n"
              + statusInfo
              + "\n"
              + "Token 占用: N/A (Memory 模块待集成)\n"
              + "消息滑动窗口: N/A\n"
              + "变量快照: N/A");
    }
  }

  private void handleCompactContext(ControlCmd.CompactContextCmd compact) {
    logger.info("Compact context requested: session={}", compact.sessionId());
    try {
      String result = agent.compactContext();
      eventBridge.onChatMessage(
          "System", result != null ? result : "上下文压缩完成（释放 Token: N/A，待 Memory 模块提供总结接口）");
    } catch (Exception e) {
      logger.warn("Agent compactContext not fully implemented", e);
      eventBridge.onChatMessage("System", "上下文压缩请求已提交（待 Memory 模块提供总结接口）");
    }
  }

  private void handleFeedback(ControlCmd.FeedbackCmd feedback) {
    logger.info("Feedback received: message={}", feedback.message());
    try {
      agent.injectFeedback(feedback.message());
      eventBridge.onChatMessage("System", "反馈已注入: " + feedback.message());
    } catch (Exception e) {
      logger.warn("Agent injectFeedback not fully implemented", e);
      eventBridge.onChatMessage(
          "System", "反馈已记录: " + feedback.message() + "（待 Agent 暴露 HumanInTheLoop 接口）");
    }
  }

  private void handleModelSwitch(AlignmentCmd.SwitchModelCmd model) {
    logger.info("Model switch requested: {}", model.modelId());
    try {
      agent.switchModel(model.modelId());
    } catch (Exception e) {
      logger.warn("Agent switchModel not fully implemented", e);
    }
    screenManager.layout().footer().setModel(model.modelId());
    screenManager.markContentDirty();
    eventBridge.onChatMessage("System", "模型切换至: " + model.modelId());
  }

  private void submitAgentCommand(String input) {
    AgentCommand cmd = AgentCommand.parse(input, sessionId, traceId());
    if (cmd != null) {
      dispatchAgentCommand(cmd);
    }
  }

  // ========== 关闭 ==========

  private void shutdown() {
    if (shutdown) return;
    shutdown = true;
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

  private String traceId() {
    return UUID.randomUUID().toString().substring(0, 12);
  }

  // ========== Main 入口 ==========

  public static void main(String[] args) {
    try {
      String apiKey = System.getenv("OPENAI_API_KEY");
      if (apiKey == null || apiKey.isEmpty()) {
        System.err.println("警告: 未设置 OPENAI_API_KEY，LLM 功能将不可用。");
      }

      AgentConfig config =
          AgentConfig.builder().defaultModelId("gpt-4o-mini").maxIterations(10).build();

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
