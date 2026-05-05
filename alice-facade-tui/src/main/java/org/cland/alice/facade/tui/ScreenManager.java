package org.cland.alice.facade.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.TerminalResizeListener;

import org.cland.alice.facade.tui.bridge.EventBridge;
import org.cland.alice.facade.tui.bridge.TuiEvent;
import org.cland.alice.facade.tui.command.CommandHandler;
import org.cland.alice.facade.tui.command.SlashCommand;
import org.cland.alice.facade.tui.component.*;
import org.cland.alice.facade.tui.layout.TuiLayout;
import org.cland.alice.facade.tui.state.TuiState;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * ScreenManager：TUI 核心管理器。
 * <p>
 * 对应设计文档 §2 中的 ScreenManager，负责：
 * <ul>
 *   <li>初始化 Lanterna 终端屏幕</li>
 *   <li>管理 UI 组件生命周期</li>
 *   <li>处理键盘输入</li>
 *   <li>驱动渲染循环</li>
 *   <li>处理终端 resize 事件</li>
 * </ul>
 */
public class ScreenManager implements AutoCloseable {

    private static final System.Logger logger = System.getLogger(ScreenManager.class.getName());

    /** 渲染帧间隔（毫秒） */
    private static final long FRAME_INTERVAL_MS = 50;

    private final Terminal terminal;
    private final Screen screen;
    private final TextGraphics graphics;

    private final TuiLayout layout;
    private final TuiState state;
    private final EventBridge eventBridge;
    private final CommandHandler commandHandler;

    private final AtomicBoolean running;
    private final Thread renderThread;

    /** 输入历史 */
    private final Deque<String> inputHistory;
    private int historyIndex;

    /** 当前聚焦组件 */
    private FocusTarget focusTarget;

    /** 聚焦目标枚举 */
    public enum FocusTarget {
        INPUT,
        CHAT,
        THOUGHT
    }

    /** 任务提交回调 */
    private Consumer<String> onTaskSubmit;

    /** 清除回调 */
    private Runnable onClear;

    /** 退出回调 */
    private Runnable onExit;

    /** 模型切换回调 */
    private Consumer<String> onModelSwitch;

    // ========== 构造 ==========

    public ScreenManager(EventBridge eventBridge) throws IOException {
        this.eventBridge = eventBridge;

        DefaultTerminalFactory factory = new DefaultTerminalFactory();
        factory.setForceTextTerminal(true);  // 确保在 IDE 中也使用文本终端
        this.terminal = factory.createTerminal();
        this.screen = new TerminalScreen(terminal);
        this.graphics = screen.newTextGraphics();

        // 初始化组件
        HeaderComponent header = new HeaderComponent();
        ChatComponent chat = new ChatComponent();
        ThoughtComponent thought = new ThoughtComponent();
        StatusComponent status = new StatusComponent();
        InputComponent input = new InputComponent();
        FooterComponent footer = new FooterComponent();

        this.layout = new TuiLayout(header, chat, thought, status, input, footer);
        this.state = new TuiState();
        this.commandHandler = new CommandHandler(eventBridge);
        this.running = new AtomicBoolean(true);
        this.renderThread = new Thread(this::renderLoop, "alice-tui-render");
        this.renderThread.setDaemon(true);

        this.inputHistory = new ArrayDeque<>();
        this.historyIndex = 0;
        this.focusTarget = FocusTarget.INPUT;

        // 设置输入焦点
        input.setFocused(true);

        // 注册事件监听
        setupEventListeners();

        // 注册命令回调
        setupCommandCallbacks();

        // 终端 resize 监听
        terminal.addResizeListener((TerminalResizeListener) (terminal, newSize) -> {
            handleResize(newSize);
        });
    }

    // ========== 初始化 ==========

    /** 启动 TUI */
    public void start() throws IOException {
        screen.startScreen();
        screen.setCursorPosition(null); // 隐藏默认光标
        layout.recalculate(terminal.getTerminalSize().getColumns(),
            terminal.getTerminalSize().getRows());
        renderThread.start();

        // 欢迎消息
        layout.chat().addMessage("System",
            "Alice Agent v1.0 TUI 已启动。输入 /help 查看可用命令。");
    }

    // ========== 事件监听设置 ==========

    private void setupEventListeners() {
        eventBridge.addListener(event -> {
            switch (event) {
                case TuiEvent.StartThinking e -> {
                    layout.header().setStatus("Thinking...");
                    layout.status().setStatus("Thinking...");
                    layout.chat().addMessage("Agent", "思考中: " + e.prompt());
                }
                case TuiEvent.NewThought e -> {
                    layout.thought().addThought(e.thought(), e.step());
                    layout.status().setIteration(e.step());
                }
                case TuiEvent.ActionExecuting e -> {
                    String desc = e.action().type().name()
                        + (e.action().target() != null ? " (" + e.action().target() + ")" : "");
                    layout.thought().addAction(desc);
                    layout.header().setStatus("Executing: " + desc);
                }
                case TuiEvent.ObservationResult e -> {
                    layout.thought().addObservation(e.summary());
                }
                case TuiEvent.ChatMessage e -> {
                    layout.chat().addMessage(e.sender(), e.content());
                }
                case TuiEvent.TaskComplete e -> {
                    layout.header().setStatus("Complete");
                    layout.status().setStatus("Complete");
                    layout.chat().addMessage("Agent", e.result());
                    state.transitionTo(TuiState.State.IDLE);
                }
                case TuiEvent.TaskError e -> {
                    layout.header().setStatus("Error");
                    layout.status().setStatus("Error");
                    layout.chat().addMessage("System", "错误: " + e.errorMessage());
                    state.transitionTo(TuiState.State.ERROR);
                }
                case TuiEvent.TokenUpdate e -> {
                    layout.status().updateStats(e.tokenCount(), e.status());
                }
                default -> {}
            }
        });
    }

    private void setupCommandCallbacks() {
        commandHandler
            .onReset(args -> {
                layout.chat().clearMessages();
                layout.thought().clear();
                state.transitionTo(TuiState.State.IDLE);
                layout.header().setStatus("Idle");
                layout.status().setStatus("Idle");
                layout.status().setIteration(0);
                layout.status().setTokenCount(0);
            })
            .onClear(() -> {
                layout.chat().clearMessages();
                layout.thought().clear();
            })
            .onExit(() -> {
                if (onExit != null) onExit.run();
            })
            .onCommandOutput(output -> {
                // 命令输出自动传给 Agent 作为上下文
                if (onTaskSubmit != null) {
                    onTaskSubmit.accept(output);
                }
            })
            .onModelSwitch(modelId -> {
                layout.header().setModel(modelId);
                if (onModelSwitch != null) {
                    onModelSwitch.accept(modelId);
                }
            });
    }

    // ========== 渲染循环 ==========

    private void renderLoop() {
        while (running.get()) {
            try {
                // 绘制所有脏组件
                boolean anyDirty = false;
                for (Component comp : layout.getComponents()) {
                    if (comp.isVisible() && comp.isDirty()) {
                        comp.draw(graphics);
                        anyDirty = true;
                    }
                }

                if (anyDirty) {
                    screen.refresh();
                }

                Thread.sleep(FRAME_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                logger.log(System.Logger.Level.ERROR, "Render error", e);
            }
        }
    }

    // ========== 输入处理 ==========

    /**
     * 处理键盘输入（在主线程中同步调用）。
     * 返回 false 表示请求退出。
     */
    public boolean handleInput(com.googlecode.lanterna.input.KeyStroke keyStroke) {
        if (keyStroke == null) return true;

        switch (keyStroke.getKeyType()) {
            // ===== 退出 =====
            case EOF:
            case Escape:
                if (focusTarget == FocusTarget.INPUT && layout.input().getText().isEmpty()) {
                    return false; // 退出
                }
                layout.input().clear();
                return true;

            // ===== 功能键 =====
            case F1:
                layout.chat().addMessage("System", SlashCommand.helpText());
                return true;

            case F5:
                // 停止当前任务
                if (state.isRunning()) {
                    layout.chat().addMessage("System", "正在停止当前任务...");
                    state.transitionTo(TuiState.State.INTERVENE);
                }
                return true;

            case F10:
                return false; // 退出

            // ===== 焦点切换 =====
            case Tab:
                switchFocus();
                return true;

            // ===== 翻页 =====
            case PageUp:
                if (focusTarget == FocusTarget.CHAT) {
                    layout.chat().pageUp();
                } else if (focusTarget == FocusTarget.THOUGHT) {
                    layout.thought().pageUp();
                }
                return true;

            case PageDown:
                if (focusTarget == FocusTarget.CHAT) {
                    layout.chat().pageDown();
                } else if (focusTarget == FocusTarget.THOUGHT) {
                    layout.thought().pageDown();
                }
                return true;

            // ===== 箭头 =====
            case ArrowLeft:
                if (focusTarget == FocusTarget.INPUT) {
                    layout.input().cursorLeft();
                }
                return true;

            case ArrowRight:
                if (focusTarget == FocusTarget.INPUT) {
                    layout.input().cursorRight();
                }
                return true;

            case ArrowUp:
                if (focusTarget == FocusTarget.CHAT) {
                    layout.chat().scrollUp();
                } else if (focusTarget == FocusTarget.THOUGHT) {
                    layout.thought().scrollUp();
                } else if (focusTarget == FocusTarget.INPUT) {
                    navigateHistory(-1);
                }
                return true;

            case ArrowDown:
                if (focusTarget == FocusTarget.CHAT) {
                    layout.chat().scrollDown();
                } else if (focusTarget == FocusTarget.THOUGHT) {
                    layout.thought().scrollDown();
                } else if (focusTarget == FocusTarget.INPUT) {
                    navigateHistory(1);
                }
                return true;

            // ===== 回车 =====
            case Enter:
                handleEnter();
                return true;

            // ===== 退格 =====
            case Backspace:
                if (focusTarget == FocusTarget.INPUT) {
                    layout.input().deleteBeforeCursor();
                }
                return true;

            case Delete:
                if (focusTarget == FocusTarget.INPUT) {
                    layout.input().deleteAtCursor();
                }
                return true;

            case Home:
                if (focusTarget == FocusTarget.INPUT) {
                    layout.input().cursorHome();
                }
                return true;

            case End:
                if (focusTarget == FocusTarget.INPUT) {
                    layout.input().cursorEnd();
                }
                return true;

            // ===== 普通字符 =====
            case Character:
                char ch = keyStroke.getCharacter();
                if (ch == 0) return true;

                // Ctrl+Q -> 退出
                if (ch == 0x11) { // Ctrl+Q
                    return false;
                }

                if (focusTarget == FocusTarget.INPUT) {
                    layout.input().insertChar(ch);
                }
                return true;

            default:
                return true;
        }
    }

    // ========== 提交处理 ==========

    private void handleEnter() {
        if (focusTarget != FocusTarget.INPUT) {
            switchFocus();
            return;
        }

        String input = layout.input().commitInput();
        if (input.isEmpty()) return;

        // 添加到历史
        inputHistory.addLast(input);
        historyIndex = inputHistory.size();

        // 检查斜杠命令
        SlashCommand slashCmd = SlashCommand.parse(input);
        if (slashCmd != null) {
            boolean handled = commandHandler.execute(slashCmd);
            if (handled) return;
        }

        // 检查是否允许提交任务
        if (state.isRunning()) {
            layout.chat().addMessage("System",
                "Agent 正在执行中，请等待完成或按 F5 停止。");
            return;
        }

        // 提交 Agent 任务
        layout.chat().addMessage("User", input);
        state.transitionTo(TuiState.State.RUNNING);
        layout.header().setStatus("Running");
        layout.status().setStatus("Running");

        if (onTaskSubmit != null) {
            onTaskSubmit.accept(input);
        }

        eventBridge.onStartThinking(input);
    }

    // ========== 焦点管理 ==========

    private void switchFocus() {
        layout.input().setFocused(false);
        focusTarget = switch (focusTarget) {
            case INPUT  -> { layout.input().setFocused(false); yield FocusTarget.CHAT; }
            case CHAT   -> FocusTarget.THOUGHT;
            case THOUGHT -> { layout.input().setFocused(true); yield FocusTarget.INPUT; }
        };
        if (focusTarget == FocusTarget.INPUT) {
            layout.input().setFocused(true);
        }
    }

    // ========== 历史导航 ==========

    private void navigateHistory(int direction) {
        if (inputHistory.isEmpty()) return;

        historyIndex += direction;
        if (historyIndex < 0) {
            historyIndex = 0;
        } else if (historyIndex >= inputHistory.size()) {
            historyIndex = inputHistory.size();
            layout.input().setText("");
            return;
        }

        String[] historyArray = inputHistory.toArray(new String[0]);
        layout.input().setText(historyArray[historyIndex]);
    }

    // ========== Resize ==========

    private void handleResize(TerminalSize newSize) {
        try {
            screen.doResizeIfNecessary();
            layout.recalculate(newSize.getColumns(), newSize.getRows());
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Resize handling failed", e);
        }
    }

    // ========== 回调注册 ==========

    public ScreenManager onTaskSubmit(Consumer<String> callback) {
        this.onTaskSubmit = callback;
        return this;
    }

    public ScreenManager onClear(Runnable callback) {
        this.onClear = callback;
        return this;
    }

    public ScreenManager onExit(Runnable callback) {
        this.onExit = callback;
        return this;
    }

    public ScreenManager onModelSwitch(Consumer<String> callback) {
        this.onModelSwitch = callback;
        return this;
    }

    // ========== 属性访问 ==========

    public TuiLayout layout()              { return layout; }
    public TuiState state()                { return state; }
    public EventBridge eventBridge()       { return eventBridge; }
    public Screen screen()                 { return screen; }

    // ========== 关闭 ==========

    @Override
    public void close() {
        running.set(false);
        try {
            renderThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            screen.stopScreen();
        } catch (IOException e) {
            logger.log(System.Logger.Level.WARNING, "Error stopping screen", e);
        }
        try {
            terminal.close();
        } catch (IOException e) {
            logger.log(System.Logger.Level.WARNING, "Error closing terminal", e);
        }
    }
}
