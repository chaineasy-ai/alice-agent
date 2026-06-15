package org.cland.alice.facade.tui.command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.cland.alice.agent.command.AgentCommand;
import org.cland.alice.facade.tui.bridge.EventBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 斜杠命令执行处理器。
 *
 * <p>根据命令类型执行相应操作。现在基于 {@link AgentCommand} 抽象指令层：
 *
 * <ul>
 *   <li>UI 内部命令（/clear, /help）仍直接处理
 *   <li>IO 命令（/prompt, /history）读取完成后转换为 AgentCommand
 *   <li>系统命令（/exec）、配置命令（/model）转换为 AgentCommand 后通过回调派发给 Agent
 * </ul>
 */
public class CommandHandler {

  private static final Logger logger = LoggerFactory.getLogger(CommandHandler.class);

  private final EventBridge eventBridge;

  /** AgentCommand 分发回调（将指令派发给 Agent 核心执行） */
  private Consumer<AgentCommand> onAgentCommand;

  /** 会话重置回调 */
  private Consumer<String> onReset;

  /** 清屏回调 */
  private Runnable onClear;

  /** 退出回调 */
  private Runnable onExit;

  /** 命令输出回调（命令执行结果） */
  private Consumer<String> onCommandOutput;

  /** 模型切换回调 */
  private Consumer<String> onModelSwitch;

  /** 当前会话 ID（由 ScreenManager 注入） */
  private String sessionId;

  public CommandHandler(EventBridge eventBridge) {
    this.eventBridge = eventBridge;
    this.sessionId = UUID.randomUUID().toString().substring(0, 8);
  }

  /** 设置当前会话 ID */
  public CommandHandler sessionId(String sessionId) {
    this.sessionId = sessionId;
    return this;
  }

  // ========== 回调注册 ==========

  public CommandHandler onAgentCommand(Consumer<AgentCommand> onAgentCommand) {
    this.onAgentCommand = onAgentCommand;
    return this;
  }

  public CommandHandler onReset(Consumer<String> onReset) {
    this.onReset = onReset;
    return this;
  }

  public CommandHandler onClear(Runnable onClear) {
    this.onClear = onClear;
    return this;
  }

  public CommandHandler onExit(Runnable onExit) {
    this.onExit = onExit;
    return this;
  }

  public CommandHandler onCommandOutput(Consumer<String> onCommandOutput) {
    this.onCommandOutput = onCommandOutput;
    return this;
  }

  public CommandHandler onModelSwitch(Consumer<String> onModelSwitch) {
    this.onModelSwitch = onModelSwitch;
    return this;
  }

  // ========== 命令执行 ==========

  /**
   * 执行解析出的斜杠命令。
   *
   * @param cmd 已解析的 SlashCommand
   * @return true 表示命令已被处理（不应再作为 Agent 输入提交）
   */
  public boolean execute(SlashCommand cmd) {
    if (cmd == null) {
      return false;
    }

    logger.debug("Executing slash command: {}", cmd);

    return switch (cmd.type()) {
      case INTERNAL -> handleInternal(cmd);
      case IO -> handleIo(cmd);
      case SYSTEM -> handleSystem(cmd);
      case CONFIG -> handleConfig(cmd);
    };
  }

  /** 处理内部命令 */
  private boolean handleInternal(SlashCommand cmd) {
    if (cmd.is("/new")) {
      // 转化为 AgentCommand 并派发
      AgentCommand ac = cmd.toAgentCommand(sessionId(), traceId());
      dispatchToAgent(ac);
      eventBridge.onChatMessage("System", "会话已重置");
      if (onReset != null) {
        onReset.accept(cmd.args());
      }
      return true;
    }

    if (cmd.is("/clear")) {
      if (onClear != null) {
        onClear.run();
      }
      eventBridge.onChatMessage("System", "屏幕已清空");
      return true;
    }

    if (cmd.is("/context")) {
      AgentCommand ac = cmd.toAgentCommand(sessionId(), traceId());
      dispatchToAgent(ac);
      return true;
    }

    if (cmd.is("/compact")) {
      AgentCommand ac = cmd.toAgentCommand(sessionId(), traceId());
      dispatchToAgent(ac);
      eventBridge.onChatMessage("System", "上下文压缩请求已提交...");
      return true;
    }

    if (cmd.is("/feedback")) {
      if (!cmd.hasArgs()) {
        eventBridge.onChatMessage("System", "用法: /feedback <反馈内容>");
        return true;
      }
      AgentCommand ac = cmd.toAgentCommand(sessionId(), traceId());
      dispatchToAgent(ac);
      eventBridge.onChatMessage("System", "反馈已提交: " + cmd.args());
      return true;
    }

    if (cmd.is("/exit")) {
      // 转化为 InterruptCmd 并派发
      AgentCommand ac = cmd.toAgentCommand(sessionId(), traceId());
      dispatchToAgent(ac);
      eventBridge.onChatMessage("System", "正在安全退出...");
      if (onExit != null) {
        onExit.run();
      }
      return true;
    }

    if (cmd.is("/help")) {
      eventBridge.onChatMessage("System", SlashCommand.helpText());
      return true;
    }

    return false;
  }

  /** 处理 IO 命令（文件读取） */
  private boolean handleIo(SlashCommand cmd) {
    if (cmd.is("/prompt")) {
      if (!cmd.hasArgs()) {
        eventBridge.onChatMessage("System", "用法: /prompt <文件路径>");
        return true;
      }
      try {
        Path path = Paths.get(cmd.args());
        String content = Files.readString(path);
        eventBridge.onChatMessage("System", "已加载提示词文件: " + path.toAbsolutePath());
        eventBridge.onChatMessage("System", "── 系统提示词 ──\n" + content);
        if (onCommandOutput != null) {
          onCommandOutput.accept(content);
        }
        // 转化为 UpdateRulesCmd 并派发给 Agent
        AgentCommand ac =
            new org.cland.alice.agent.command.CapabilityCmd.UpdateRulesCmd(
                path.toAbsolutePath().toString(), sessionId(), traceId());
        dispatchToAgent(ac);
      } catch (IOException e) {
        eventBridge.onTaskError("读取文件失败: " + e.getMessage());
      }
      return true;
    }

    if (cmd.is("/history")) {
      eventBridge.onChatMessage("System", "暂无可用的历史记录");
      return true;
    }

    return false;
  }

  /** 处理系统命令（shell 执行） */
  private boolean handleSystem(SlashCommand cmd) {
    if (cmd.is("/exec")) {
      if (!cmd.hasArgs()) {
        eventBridge.onChatMessage("System", "用法: /exec <shell 命令>");
        return true;
      }

      String commandLine = cmd.args();
      eventBridge.onChatMessage("System", "执行命令: $ " + commandLine);

      // 转化为 ExecuteRawCmd 并派发
      AgentCommand ac = cmd.toAgentCommand(sessionId(), traceId());
      dispatchToAgent(ac);

      // 异步执行 shell 并输出结果
      CompletableFuture.runAsync(
          () -> {
            try {
              String osName = System.getProperty("os.name").toLowerCase();
              ProcessBuilder pb;
              if (osName.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", commandLine);
              } else {
                pb = new ProcessBuilder("sh", "-c", commandLine);
              }

              pb.redirectErrorStream(true);
              Process process = pb.start();

              StringBuilder output = new StringBuilder();
              try (BufferedReader reader =
                  new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  output.append(line).append("\n");
                }
              }

              int exitCode = process.waitFor();
              String result = "── exit code: " + exitCode + " ──\n" + output.toString();

              eventBridge.onChatMessage("System", result);
              if (onCommandOutput != null) {
                onCommandOutput.accept(result);
              }

            } catch (IOException | InterruptedException e) {
              Thread.currentThread().interrupt();
              eventBridge.onTaskError("命令执行失败: " + e.getMessage());
            }
          });

      return true;
    }

    return false;
  }

  /** 处理配置命令 */
  private boolean handleConfig(SlashCommand cmd) {
    if (cmd.is("/model")) {
      if (!cmd.hasArgs()) {
        eventBridge.onChatMessage("System", "用法: /model <模型ID> (例如: gpt-4o, claude-3.5)");
        return true;
      }

      String modelId = cmd.args();
      eventBridge.onChatMessage("System", "切换模型至: " + modelId);

      // 转化为 SwitchModelCmd 并派发
      AgentCommand ac = cmd.toAgentCommand(sessionId(), traceId());
      dispatchToAgent(ac);

      if (onModelSwitch != null) {
        onModelSwitch.accept(modelId);
      }
      return true;
    }

    if (cmd.is("/tools")) {
      eventBridge.onChatMessage("System", "可用工具列表待查询 ToolRegistry...");
      return true;
    }

    if (cmd.is("/routine")) {
      if (!cmd.hasArgs()) {
        eventBridge.onChatMessage("System", "用法: /routine <cron表达式>");
        return true;
      }

      String cronExpr = cmd.args();
      eventBridge.onChatMessage("System", "注册定时任务: " + cronExpr);

      // 转化为 RegisterRoutineCmd 并派发
      AgentCommand ac = cmd.toAgentCommand(sessionId(), traceId());
      dispatchToAgent(ac);

      return true;
    }

    if (cmd.is("/sub-agent")) {
      if (!cmd.hasArgs()) {
        eventBridge.onChatMessage(
            "System", "用法: /sub-agent spawn|connect|list|cancel|results|send|prompt [args...]");
        return true;
      }

      String subCmdLine = cmd.args();
      eventBridge.onChatMessage("System", "子 Agent 指令: /sub-agent " + subCmdLine);

      // 转化为对应的 SubAgentCmd 并派发
      AgentCommand ac = cmd.toAgentCommand(sessionId(), traceId());
      if (ac != null) {
        dispatchToAgent(ac);
      } else {
        eventBridge.onChatMessage("System", "无法识别的子 Agent 指令: " + subCmdLine);
      }

      return true;
    }

    return false;
  }

  /**
   * 将 AgentCommand 派发给 Agent 核心。
   *
   * <p>通过回调将抽象指令传递给上层（AliceTuiLauncher / ScreenManager）， 最终由 Agent 核心执行或路由到相应处理器。
   */
  private void dispatchToAgent(AgentCommand cmd) {
    if (onAgentCommand != null) {
      onAgentCommand.accept(cmd);
    } else {
      logger.warn("No onAgentCommand callback registered. Command dropped: {}", cmd);
    }
  }

  // ========== 辅助 ==========

  private String sessionId() {
    return sessionId;
  }

  private String traceId() {
    return UUID.randomUUID().toString().substring(0, 12);
  }
}
