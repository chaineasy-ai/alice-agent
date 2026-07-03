package org.cland.alice.facade.tui.command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.cland.alice.agent.command.AgentCommand;
import org.cland.alice.agent.command.ControlCmd;
import org.cland.alice.core.agent.prompt.PromptManager;
import org.cland.alice.core.agent.wal.SnowflakeIdGenerator;
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
    this.sessionId = SnowflakeIdGenerator.generateSessionId();
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
        // 无参数时列出可用的 managed prompts
        eventBridge.onChatMessage("System", PromptHelper.listPrompts());
        return true;
      }
      try {
        String args = cmd.args();
        var result = PromptHelper.resolve(args);
        if (!result.found()) {
          eventBridge.onChatMessage(
              "System", result.message() + "\n使用 /prompt 查看可用 prompt 列表，或指定完整文件路径。");
          return true;
        }
        String content = PromptHelper.readContent(result.path());
        eventBridge.onChatMessage("System", "已加载提示词: " + result.path().toAbsolutePath());
        eventBridge.onChatMessage("System", "── 内容 ──\n" + content);
        if (onCommandOutput != null) {
          onCommandOutput.accept(content);
        }
        // 注册到 Agent 系统（拷贝 + 刷新 PromptManager 缓存）
        Path dest = PromptHelper.copyPromptFile(result.path(), result.managed());
        PromptManager.reloadFromDisk();
        // 通过 UpdateRulesCmd 派发给 Agent（已在本地完成拷贝 + reload）
        AgentCommand ac =
            new org.cland.alice.agent.command.CapabilityCmd.UpdateRulesCmd(
                dest.toAbsolutePath().toString(), sessionId(), traceId());
        if (ac != null) {
          dispatchToAgent(ac);
        }
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

    if (cmd.is("/resume")) {
      if (cmd.hasArgs()) {
        String sid = cmd.args().trim();
        // 检查是否以 --session-id= 或 -s= 开头
        if (sid.startsWith("--session-id=")) {
          sid = sid.substring("--session-id=".length()).trim();
        } else if (sid.startsWith("-s=")) {
          sid = sid.substring("-s=".length()).trim();
        }
        // 检查是否有 --snapshot 参数
        String snapshotId = null;
        int snapIdx = sid.indexOf("--snapshot");
        if (snapIdx >= 0) {
          String afterSnap = sid.substring(snapIdx + "--snapshot".length()).trim();
          sid = sid.substring(0, snapIdx).trim();
          if (afterSnap.startsWith("=")) {
            snapshotId = afterSnap.substring(1).trim();
          } else if (afterSnap.startsWith(" ")) {
            snapshotId = afterSnap.substring(1).trim();
          }
        }
        if (!sid.isEmpty()) {
          // 尝试解析为数字索引（用户可能输入了编号）
          try {
            int index = Integer.parseInt(sid);
            // 从 WAL 获取会话列表，通过索引查找
            var resolved = resolveSessionByIndex(index);
            if (resolved != null) {
              sid = resolved;
            } else {
              eventBridge.onChatMessage("System", "编号 " + index + " 超出范围。使用 /resume 查看可用会话列表。");
              return true;
            }
          } catch (NumberFormatException e) {
            // 不是数字，直接作为 sessionId 使用
          }
          eventBridge.onChatMessage("System", "恢复会话: " + sid);
          AgentCommand ac = new ControlCmd.ResumeSessionCmd(sid, traceId(), snapshotId);
          dispatchToAgent(ac);
        } else {
          eventBridge.onChatMessage("System", "用法: /resume <session-id> 或 /resume");
        }
      } else {
        // 无参数 → 扫描 WAL 会话列表，让用户选择
        handleResumeList();
      }
      return true;
    }

    return false;
  }

  /**
   * 扫描 ~/.alice/wal 目录，列出所有可恢复的会话供用户选择。
   *
   * <p>遍历各子目录下的 .wal.jsonl 文件，显示会话 ID 和 checkpoint 状态， 提示用户输入会话 ID 进行恢复。
   */
  private void handleResumeList() {
    try {
      var walDir = java.nio.file.Paths.get(System.getProperty("user.home"), ".alice", "wal");
      if (!java.nio.file.Files.isDirectory(walDir)) {
        eventBridge.onChatMessage("System", "未找到 WAL 存储目录: " + walDir);
        eventBridge.onChatMessage("System", "没有可恢复的会话。请先执行一些任务以创建会话。");
        return;
      }

      // 收集所有子目录（每个 sessionId 哈希值对应一个子目录）
      var sessions = new ArrayList<SessionInfo>();
      try (var stream = Files.newDirectoryStream(walDir)) {
        for (var subDir : stream) {
          if (!Files.isDirectory(subDir)) continue;
          // 在该子目录下查找 .wal.jsonl 文件
          try (var files = Files.newDirectoryStream(subDir, "*.wal.jsonl")) {
            for (var walFile : files) {
              String fileName = walFile.getFileName().toString();
              String sessionId = fileName.substring(0, fileName.length() - ".wal.jsonl".length());
              var cpFile = subDir.resolve(sessionId + ".checkpoint.json");
              boolean hasCheckpoint = Files.exists(cpFile);
              long fileSize = Files.size(walFile);
              sessions.add(new SessionInfo(sessionId, hasCheckpoint, fileSize));
            }
          }
        }
      }

      if (sessions.isEmpty()) {
        eventBridge.onChatMessage("System", "没有可恢复的会话。请先执行一些任务以创建会话。");
        return;
      }

      // 构建会话列表输出
      var sb = new StringBuilder();
      sb.append("── 可恢复的会话列表 ────────────────────────\n");
      for (int i = 0; i < sessions.size(); i++) {
        var s = sessions.get(i);
        String sizeStr = s.fileSize > 1024 ? (s.fileSize / 1024) + "KB" : s.fileSize + "B";
        sb.append(
            String.format(
                "  [%d] %s  %s  %s\n", i + 1, s.sessionId, s.hasCheckpoint ? "📌" : "  ", sizeStr));
      }
      sb.append("──────────────────────────────────────────────\n");
      sb.append("输入 /resume <会话ID> 或 /resume [编号] 恢复指定会话。");
      eventBridge.onChatMessage("System", sb.toString());

    } catch (Exception e) {
      logger.error("Failed to list sessions for resume", e);
      eventBridge.onTaskError("列出会话失败: " + e.getMessage());
    }
  }

  /** 会话信息记录 */
  private record SessionInfo(String sessionId, boolean hasCheckpoint, long fileSize) {}

  /**
   * 根据编号（1-based）从 WAL 查找对应的 sessionId。
   *
   * @param index 用户输入的编号（1-based）
   * @return 对应的 sessionId，未找到时返回 null
   */
  private String resolveSessionByIndex(int index) {
    try {
      var walDir = java.nio.file.Paths.get(System.getProperty("user.home"), ".alice", "wal");
      if (!Files.isDirectory(walDir)) return null;

      var sessions = new ArrayList<String>();
      try (var stream = Files.newDirectoryStream(walDir)) {
        for (var subDir : stream) {
          if (!Files.isDirectory(subDir)) continue;
          try (var files = Files.newDirectoryStream(subDir, "*.wal.jsonl")) {
            for (var walFile : files) {
              String fileName = walFile.getFileName().toString();
              String sessionId = fileName.substring(0, fileName.length() - ".wal.jsonl".length());
              sessions.add(sessionId);
            }
          }
        }
      }

      if (index < 1 || index > sessions.size()) return null;
      return sessions.get(index - 1);
    } catch (Exception e) {
      logger.error("Failed to resolve session by index", e);
      return null;
    }
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
