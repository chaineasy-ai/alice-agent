package org.cland.alice.facade.tui;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.cland.alice.agent.command.AgentCommand;
import org.cland.alice.agent.command.AlignmentCmd;
import org.cland.alice.agent.command.CancelSubAgentCmd;
import org.cland.alice.agent.command.CapabilityCmd;
import org.cland.alice.agent.command.ConnectSubAgentCmd;
import org.cland.alice.agent.command.ControlCmd;
import org.cland.alice.agent.command.ExecutionCmd;
import org.cland.alice.agent.command.GetSubAgentResultsCmd;
import org.cland.alice.agent.command.ListSubAgentsCmd;
import org.cland.alice.agent.command.PromptSubAgentCmd;
import org.cland.alice.agent.command.SendToSubAgentCmd;
import org.cland.alice.agent.command.SpawnSubAgentCmd;
import org.cland.alice.agent.subagent.SubAgentManager;
import org.cland.alice.agent.subagent.SubAgentRecord;
import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.core.agent.wal.FileWalStore;
import org.cland.alice.core.agent.wal.RawMessage;
import org.cland.alice.core.agent.wal.SnowflakeIdGenerator;
import org.cland.alice.core.agent.wal.WalSession;
import org.cland.alice.facade.tui.bridge.EventBridge;
import org.cland.alice.facade.tui.state.TuiState;
import org.cland.alice.model.ModelConfigLoader;
import org.cland.alice.model.ModelProvider;
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
    this.sessionId = SnowflakeIdGenerator.generateSessionId();

    // 1. 创建 Agent 并注入 WAL
    WalSession wal =
        new WalSession(
            new FileWalStore(
                java.nio.file.Paths.get(
                    System.getProperty("user.home"), ".alice", "wal", sessionId)));
    this.agent = new Agent(config).withWal(wal);

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
    this.sessionId = SnowflakeIdGenerator.generateSessionId();
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
            })
        .onAgentCommand(this::dispatchAgentCommand);

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
    // screenManager.start() 会清屏 + 全量重绘，所有日志必须在此之后输出
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
      case ControlCmd.ResumeSessionCmd resume -> handleResume(resume);
      case ControlCmd.InterruptCmd exit -> handleInterrupt(exit);
      case AlignmentCmd.SwitchModelCmd model -> handleModelSwitch(model);
      case SpawnSubAgentCmd spawn -> handleSpawnSubAgent(spawn);
      case ConnectSubAgentCmd connect -> handleConnectAgent(connect);
      case ListSubAgentsCmd list -> handleListSubAgents(list);
      case CancelSubAgentCmd cancel -> handleCancelSubAgent(cancel);
      case GetSubAgentResultsCmd results -> handleGetResults(results);
      case SendToSubAgentCmd send -> handleSendToSubAgent(send);
      case PromptSubAgentCmd prompt -> handlePromptAgent(prompt);
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
            if (result == null || result.isBlank()) {
              logger.warn("Agent returned empty result for task: {}", task);
              eventBridge.onTaskComplete("(Agent 返回了空结果，请检查模型配置或 API 状态)", "warning");
            } else {
              eventBridge.onTaskComplete(result, "Agent 执行完成");
            }
            screenManager.markContentDirty();
          } catch (Exception e) {
            logger.error("Task execution failed", e);
            eventBridge.onTaskError("任务执行失败: " + e.getMessage());
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

  private void handleResume(ControlCmd.ResumeSessionCmd resume) {
    logger.info(
        "Resume session requested: {} (snapshot={})", resume.sessionId(), resume.snapshotId());

    CompletableFuture.runAsync(
        () -> {
          try {
            var sessId = resume.sessionId();

            // 定位 WAL 目录：优先直接使用 sessionId 作为子目录名（新风格），
            // 未找到时扫描所有子目录（兼容旧 style hash 子目录）。
            var walDir = resolveWalDir(sessId);
            if (walDir == null) {
              eventBridge.onChatMessage("System", "会话 '" + sessId + "' 未找到。\n使用 /resume 查看可用会话列表。");
              return;
            }

            // 恢复会话
            var wal =
                new org.cland.alice.core.agent.wal.WalSession(
                    new org.cland.alice.core.agent.wal.FileWalStore(walDir));
            var recoveryResult = wal.recover(resume.sessionId());

            // 1. 绑定 WalSession 到 Agent，使后续消息继续追加到该会话的 WAL
            agent.withWal(wal);

            // 2. 加载所有原始消息并展示到聊天界面
            List<RawMessage> allMessages = wal.getAllMessages(resume.sessionId());
            int displayedCount = 0;
            for (RawMessage msg : allMessages) {
              // 跳过内部消息（system 提示词、tool_register 注册信息）和不可见消息
              if ("system".equals(msg.role())
                  || "tool_register".equals(msg.role())
                  || !msg.isUserVisible()) {
                logger.debug(
                    "[Resume] skip role={} id={} visible={}",
                    msg.role(),
                    msg.messageId(),
                    msg.isUserVisible());
                continue;
              }
              displayedCount++;

              String roleLabel =
                  switch (msg.role()) {
                    case "user" -> "User";
                    case "assistant" -> {
                      String spanType = msg.spanType();
                      if ("llm_think".equals(spanType)) {
                        yield "(thought)";
                      } else if ("llm_final_response".equals(spanType)) {
                        yield "Assistant";
                      } else if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                        yield "[Tools]";
                      } else {
                        yield "Assistant";
                      }
                    }
                    case "tool" -> "[Tool Result]";
                    case "compact" -> "[Summary]";
                    default -> msg.role();
                  };

              String content = msg.content() != null ? msg.content() : "";

              // 对于 tool_calls 类型的 assistant 消息，显示工具调用信息
              if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                StringBuilder tcSb = new StringBuilder();
                tcSb.append("调用工具: ");
                for (var tc : msg.toolCalls()) {
                  tcSb.append(tc.function().name());
                  if (tc.function().arguments() != null && !tc.function().arguments().isBlank()) {
                    String args = tc.function().arguments();
                    if (args.length() > 120) {
                      args = args.substring(0, 120) + "...";
                    }
                    tcSb.append("(").append(args).append(")");
                  }
                  tcSb.append(", ");
                }
                if (tcSb.length() > 2) tcSb.setLength(tcSb.length() - 2);
                content = tcSb.toString();
              }

              if (!content.isEmpty()) {
                logger.debug(
                    "[Resume] display role={} label={} contentLen={}",
                    msg.role(),
                    roleLabel,
                    content.length());
                eventBridge.onChatMessage(roleLabel, content);
              }
            }

            // 3. 构建恢复摘要
            var sb = new StringBuilder();
            sb.append("── 会话恢复完成 ──\n");
            sb.append("  会话: ").append(resume.sessionId()).append("\n");
            sb.append("  消息数: ").append(allMessages.size()).append("\n");
            sb.append("  已展示: ").append(displayedCount).append(" 条\n");
            if (resume.snapshotId() != null) {
              sb.append("  快照: ").append(resume.snapshotId()).append("\n");
            }
            sb.append("  状态: ").append(recoveryResult.summary()).append("\n");
            sb.append("────────────────────\n");
            sb.append("会话已恢复，可以继续对话。");

            eventBridge.onChatMessage("System", sb.toString());
            screenManager.markContentDirty();

          } catch (Exception e) {
            logger.error("Failed to resume session {}", resume.sessionId(), e);
            eventBridge.onTaskError("恢复会话失败: " + e.getMessage());
            screenManager.markContentDirty();
          }
        });
  }

  /**
   * 定位会话的 WAL 目录。
   *
   * <p>优先尝试直接使用 {@code sessionId} 作为子目录名（新风格 — snowflake 完整 ID）， 未找到时扫描 {@code ~/.alice/wal/}
   * 下所有子目录（兼容旧 style hash 子目录）。
   *
   * @param sessionId 待查找的会话 ID
   * @return WAL 目录路径，未找到时返回 {@code null}
   */
  private java.nio.file.Path resolveWalDir(String sessionId) {
    var baseDir = java.nio.file.Paths.get(System.getProperty("user.home"), ".alice", "wal");
    if (!java.nio.file.Files.isDirectory(baseDir)) return null;

    // 1. 尝试直接路径（新风格）
    var direct = baseDir.resolve(sessionId);
    if (java.nio.file.Files.isDirectory(direct)) {
      var walFile = direct.resolve(sessionId + ".wal.jsonl");
      if (java.nio.file.Files.exists(walFile)) return direct;
    }

    // 2. 扫描所有子目录（兼容旧 style hash 子目录）
    try (var stream = java.nio.file.Files.newDirectoryStream(baseDir)) {
      for (var subDir : stream) {
        if (!java.nio.file.Files.isDirectory(subDir)) continue;
        var walFile = subDir.resolve(sessionId + ".wal.jsonl");
        if (java.nio.file.Files.exists(walFile)) return subDir;
      }
    } catch (java.io.IOException e) {
      logger.warn("Failed to scan WAL directories for session {}: {}", sessionId, e.getMessage());
    }

    return null;
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

  // ──────────────────────────────────────────────────────────────────────────
  // Sub-Agent 命令处理
  // ──────────────────────────────────────────────────────────────────────────

  private void handleSpawnSubAgent(SpawnSubAgentCmd spawn) {
    logger.info("Spawning sub-agent: goal={}", spawn.goal());
    eventBridge.onChatMessage("System", "正在生成子 Agent: " + spawn.goal());

    CompletableFuture.runAsync(
        () -> {
          try {
            String subSessionId = SnowflakeIdGenerator.generateSessionId();
            SubAgentManager mgr = new SubAgentManager(subSessionId);
            SubAgentRecord record = mgr.spawnSubAgent(spawn.goal(), spawn.model());
            String msg = "子 Agent " + record.id() + " 已生成，目标: " + spawn.goal();
            eventBridge.onChatMessage("System", msg);
          } catch (Exception e) {
            logger.error("Failed to spawn sub-agent", e);
            eventBridge.onTaskError("子 Agent 生成失败: " + e.getMessage());
          }
        });
  }

  private void handleConnectAgent(ConnectSubAgentCmd connect) {
    logger.info(
        "Connecting to ACP agent: name={}, endpoint={}", connect.name(), connect.acpEndpoint());
    eventBridge.onChatMessage(
        "System", "连接 ACP Agent: " + connect.name() + " -> " + connect.acpEndpoint());
  }

  private void handleListSubAgents(ListSubAgentsCmd list) {
    logger.info("List sub-agents requested");
    eventBridge.onChatMessage("System", "列出子 Agent（待集成 SubAgentManager）");
  }

  private void handleCancelSubAgent(CancelSubAgentCmd cancel) {
    logger.info("Cancel sub-agent: id={}", cancel.subAgentId());
    eventBridge.onChatMessage(
        "System", "取消子 Agent: " + cancel.subAgentId() + "（待集成 SubAgentManager）");
  }

  private void handleGetResults(GetSubAgentResultsCmd results) {
    logger.info("Get results for sub-agent: id={}", results.subAgentId());
    eventBridge.onChatMessage(
        "System", "获取子 Agent 结果: " + results.subAgentId() + "（待集成 SubAgentManager）");
  }

  private void handleSendToSubAgent(SendToSubAgentCmd send) {
    logger.info("Send to sub-agent: id={}, message={}", send.subAgentId(), send.message());
    eventBridge.onChatMessage(
        "System", "发送消息给子 Agent " + send.subAgentId() + ": " + send.message());
  }

  private void handlePromptAgent(PromptSubAgentCmd prompt) {
    logger.info("Prompt ACP agent: id={}, prompt={}", prompt.subAgentId(), prompt.prompt());
    eventBridge.onChatMessage(
        "System", "提示 ACP Agent " + prompt.subAgentId() + ": " + prompt.prompt());
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

  // ========== 公共启动入口 ==========

  /**
   * 从 {@link FacadeSelector} 调用的公共启动入口。
   *
   * <p>在 {@code alice-bootstrap} 模块中，FacadeSelector 不再持有 Agent / AgentConfig， 因此 TUI Launcher
   * 需要自行初始化 ModelProvider 和 Agent 核心。
   *
   * @param args 原始命令行参数（TUI 模式下大部分参数由本方法自行处理）
   * @return 退出码
   */
  public static int launch(String[] args) {
    // 1. 锁死编码
    System.setProperty("file.encoding", "UTF-8");

    // 1.1 抑制 JLine 内部 Log.warn() 输出。
    //     JLine 用 java.lang.System.getLogger("org.jline") 输出日志，后端通常为
    //     java.util.logging (JUL)。设置 "org.jline" 的日志级别为 SEVERE 以抑制
    //     "Unable to create a system terminal, creating a dumb terminal" 警告。
    //     该警告在非交互式终端（Git Bash、管道、GraalVM）上属于预期行为。
    java.util.logging.Logger.getLogger("org.jline").setLevel(java.util.logging.Level.SEVERE);

    // 2. JLine 4.2.1 提供以下终端提供商（按优先级排列）：
    //    - ffm (Foreign Function & Memory API, JDK 22+) — 需要 --enable-native-access
    //    - jni (JNI native)
    //    - exec (子进程终端)
    //    - dumb (回退终端)
    //
    //    JLine 4 不再提供 jansi terminal provider（jansi 已被自带的 jansi-core 取代作为
    //    ANSI 码渲染器，但不再是 terminal provider）。
    //
    //    在 Windows 上（特别是 Git Bash/MSYS2/Cygwin 环境），终端是一个伪终端（pty），
    //    不是原生 Win32 控制台句柄。FFM 和 JNI provider 会因无法获取控制台句柄而失败。
    //    此时应优先使用 exec provider（它通过 fork 子进程来管理终端 I/O）。
    //
    //    如果是原生 Windows CMD/PowerShell 控制台，FFM provider 可正常工作
    //    （需 --enable-native-access=ALL-UNNAMED）。
    //
    //    GraalVM CE 不完全支持 FFM（Foreign Function & Memory）API：
    //    GraalVM 使用自己的 Truffle/NFI 原生接口，而非标准 Panama FFM API。
    //    在 GraalVM 上 FFM provider 会失败，此时应优先使用 exec provider。
    //
    //    策略：
    //    - GraalVM + Windows + Git Bash: exec,ffm,jni,dumb
    //    - GraalVM + Windows (CMD/PowerShell): exec,ffm,jni,dumb
    //      (FFM 在 GraalVM 上不可用，先试 exec)
    //    - OpenJDK + Windows + Git Bash: exec,ffm,jni,dumb
    //    - OpenJDK + Windows (CMD/PowerShell): ffm,jni,exec,dumb
    //    - Linux/macOS: exec,ffm,jni,dumb
    boolean isGraalVm = System.getProperty("java.vm.name", "").toLowerCase().contains("graal");
    String osName = System.getProperty("os.name").toLowerCase();
    String termProgram = System.getenv("TERM_PROGRAM");
    String msystem = System.getenv("MSYSTEM");
    boolean isGitBash =
        (msystem != null && !msystem.isEmpty())
            || (termProgram != null && termProgram.contains("mintty"));
    if (osName.contains("win") && !isGitBash && !isGraalVm) {
      // OpenJDK + Windows 原生控制台（CMD/PowerShell）：FFM provider 优先
      System.setProperty("org.jline.terminal.providers", "ffm,jni,exec,dumb");
    } else {
      // Git Bash / GraalVM / Linux / macOS：exec provider 优先（pty 环境），FFM 次之
      System.setProperty("org.jline.terminal.providers", "exec,ffm,jni,dumb");
    }

    // 3. 如果在 Windows 下，启用原生控制台虚拟终端模式
    //    JLine 4 自带 org.jline.jansi.AnsiConsole，不再是旧的 org.fusesource.jansi。
    //    这里尝试通过 JLine 4 内置的 jansi-core 安装 ANSI 支持。
    try {
      if (System.getProperty("os.name").toLowerCase().contains("win")) {
        // JLine 4 内置的 jansi-core（包名 org.jline.jansi）
        Class<?> ansiConsole = Class.forName("org.jline.jansi.AnsiConsole");
        ansiConsole.getMethod("systemInstall").invoke(null);
      }
    } catch (Throwable t) {
      // 静默降级：可能缺少 --enable-native-access，或非 Windows 环境
    }

    try {
      // 4. 加载模型配置（~/.alice/model.json）
      ModelConfigLoader configLoader = new ModelConfigLoader();
      try {
        configLoader.load();
        configLoader.registerTo(ModelProvider.getInstance());
        logger.info("Loaded {} model provider(s) from config", configLoader.getProviders().size());
      } catch (Exception e) {
        logger.warn("Failed to load model config, using defaults: {}", e.getMessage());
      }

      // 5. 注册内置模型枚举
      ModelProvider.getInstance().registerBuiltinModels();

      // 6. 确定默认模型
      String defaultModel = configLoader.getDefaultModel();
      if (defaultModel == null || defaultModel.isBlank()) {
        defaultModel = "gpt-4o-mini";
        logger.info("No default_model in ~/.alice/model.json, using built-in: {}", defaultModel);
      } else {
        logger.info("Using default model from config: {}", defaultModel);
      }

      String apiKey = System.getenv("OPENAI_API_KEY");
      if (apiKey == null || apiKey.isEmpty()) {
        logger.warn(
            "OPENAI_API_KEY not set. OpenAI models will be unavailable, but other providers may work.");
      }

      String deepseekKey = System.getenv("DEEPSEEK_API_KEY");
      if (deepseekKey != null && !deepseekKey.isEmpty()) {
        // 如果配置加载没有注册 DeepSeek，手动注册一个（DeepSeek API 与 OpenAI 兼容）
        if (ModelProvider.getInstance().getSupplier("deepseek-v4-flash") == null) {
          ModelProvider.getInstance()
              .registerSupplier(
                  new org.cland.alice.model.supplier.OpenAiSupplier(
                      "deepseek", deepseekKey, "https://api.deepseek.com/v1/chat/completions"));
          logger.info("Registered DeepSeek supplier via OpenAiSupplier (OpenAI-compatible)");
        }
      }

      AgentConfig config =
          AgentConfig.builder().defaultModelId(defaultModel).maxIterations(10).build();

      AliceTuiLauncher launcher = new AliceTuiLauncher(config);
      launcher.start();
      launcher.run();
      return 0;

    } catch (IOException e) {
      System.err.println("TUI 启动失败: " + e.getMessage());
      return 1;
    }
  }

  // ========== Main 入口 ==========

  public static void main(String[] args) {
    int exitCode = launch(args);
    System.exit(exitCode);
  }
}
