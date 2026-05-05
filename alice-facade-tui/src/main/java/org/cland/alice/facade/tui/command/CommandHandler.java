package org.cland.alice.facade.tui.command;

import org.cland.alice.facade.tui.bridge.EventBridge;
import org.cland.alice.facade.tui.bridge.TuiEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 斜杠命令执行处理器。
 * <p>
 * 根据命令类型执行相应操作：
 * <ul>
 *   <li>Type A（INTERNAL）：直接处理 UI/会话操作</li>
 *   <li>Type B（IO）：读取文件并回调</li>
 *   <li>Type C（SYSTEM）：通过 ProcessBuilder 执行 shell 命令</li>
 *   <li>Type D（CONFIG）：更新模型/工具配置</li>
 * </ul>
 */
public class CommandHandler {

    private static final System.Logger logger = System.getLogger(CommandHandler.class.getName());

    private final EventBridge eventBridge;

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

    public CommandHandler(EventBridge eventBridge) {
        this.eventBridge = eventBridge;
    }

    // ========== 回调注册 ==========

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

        logger.log(System.Logger.Level.DEBUG, "Executing slash command: {0}", cmd);

        return switch (cmd.type()) {
            case INTERNAL -> handleInternal(cmd);
            case IO       -> handleIo(cmd);
            case SYSTEM   -> handleSystem(cmd);
            case CONFIG   -> handleConfig(cmd);
        };
    }

    /** 处理内部命令 */
    private boolean handleInternal(SlashCommand cmd) {
        if (cmd.is("/new")) {
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

        if (cmd.is("/exit")) {
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
                eventBridge.onChatMessage("System",
                    "用法: /prompt <文件路径>");
                return true;
            }
            try {
                Path path = Paths.get(cmd.args());
                String content = Files.readString(path);
                eventBridge.onChatMessage("System",
                    "已加载提示词文件: " + path.toAbsolutePath());
                // 将文件内容作为系统提示输出
                eventBridge.onChatMessage("System",
                    "── 系统提示词 ──\n" + content);
                if (onCommandOutput != null) {
                    onCommandOutput.accept(content);
                }
            } catch (IOException e) {
                eventBridge.onTaskError("读取文件失败: " + e.getMessage());
            }
            return true;
        }

        if (cmd.is("/history")) {
            // 历史记录由 ScreenManager 处理
            eventBridge.onChatMessage("System",
                "暂无可用的历史记录");
            return true;
        }

        return false;
    }

    /** 处理系统命令（shell 执行） */
    private boolean handleSystem(SlashCommand cmd) {
        if (cmd.is("/exec")) {
            if (!cmd.hasArgs()) {
                eventBridge.onChatMessage("System",
                    "用法: /exec <shell 命令>");
                return true;
            }

            String commandLine = cmd.args();
            eventBridge.onChatMessage("System",
                "执行命令: $ " + commandLine);

            // 异步执行
            CompletableFuture.runAsync(() -> {
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
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
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
                eventBridge.onChatMessage("System",
                    "用法: /model <模型ID> (例如: gpt-4o, claude-3.5)");
                return true;
            }

            String modelId = cmd.args();
            eventBridge.onChatMessage("System",
                "切换模型至: " + modelId);

            if (onModelSwitch != null) {
                onModelSwitch.accept(modelId);
            }
            return true;
        }

        if (cmd.is("/tools")) {
            eventBridge.onChatMessage("System",
                "可用工具列表待查询 ToolRegistry...");
            return true;
        }

        return false;
    }
}
