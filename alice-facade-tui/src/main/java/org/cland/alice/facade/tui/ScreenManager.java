package org.cland.alice.facade.tui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.cland.alice.facade.tui.bridge.EventBridge;
import org.cland.alice.facade.tui.bridge.TuiEvent;
import org.cland.alice.facade.tui.command.CommandHandler;
import org.cland.alice.facade.tui.command.SlashCommand;
import org.cland.alice.facade.tui.component.*;
import org.cland.alice.facade.tui.layout.TuiLayout;
import org.cland.alice.facade.tui.state.TuiState;
import org.jline.reader.*;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ScreenManager：基于 JLine 3 的 TUI 核心管理器。
 *
 * <p>对应 Layout.md 三层单线分割布局 v2.0。负责：
 *
 * <ul>
 *   <li>初始化 JLine 3 Terminal + LineReader
 *   <li>维护三层固定布局（Layout.md §7.1 沉浸式三看板）
 *   <li>强制输入框补全菜单最大行数限制（LIST_MAX=3），杜绝界面整体上移溢出
 *   <li>处理键盘输入、驱动渲染更新、处理终端 resize
 * </ul>
 *
 * <p>所有光标移动和清屏操作使用原始 ANSI 转义码直接写入 terminal.writer()， 避免 JLine InfoCmp.Capability 在
 * Windows/dumb-terminal 下不可用的问题。
 *
 * <p>参考 docs/alice-facade-tui/Layout.md
 */
public class ScreenManager implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(ScreenManager.class);

  /** 渲染帧间隔（毫秒） */
  private static final long FRAME_INTERVAL_MS = 100;

  /** 补全菜单最大展示行数 —— 锁定渲染边界，杜绝菜单展开时整体界面位移 */
  private static final int COMPLETION_LIST_MAX = 3;

  /** ANSI 转义序列（所有光标定位走原生写入，不走 terminal.puts） */
  private static final String ANSI_CLEAR_SCREEN = "\033[2J\033[H";

  private static final String ANSI_CLEAR_LINE = "\033[K";

  private final Terminal terminal;
  private final LineReader reader;

  private final TuiLayout layout;
  private final TuiState state;
  private final EventBridge eventBridge;
  private final CommandHandler commandHandler;

  private final AtomicBoolean running;
  private final Thread renderThread;

  /** 输入历史 */
  private final Deque<String> inputHistory;

  private int historyIndex;

  /** 任务提交回调 */
  private Consumer<String> onTaskSubmit;

  /** 清屏回调 */
  private Runnable onClear;

  /** 退出回调 */
  private Runnable onExit;

  /** 模型切换回调 */
  private Consumer<String> onModelSwitch;

  /** 内容变更标记——渲染线程检测到此标记后重绘上方滚动区 */
  private final AtomicBoolean contentDirty;

  /** 模型补全列表 */
  private static final List<String> MODEL_CANDIDATES =
      List.of(
          "deepseek-v4-flash \u2022 medium",
          "deepseek-v4-reasoning \u2022 deep",
          "gpt-4o-mini",
          "claude-3.5-sonnet");

  // ========== 构造 ==========

  public ScreenManager(EventBridge eventBridge) throws IOException {
    this.eventBridge = eventBridge;

    // 强制 UTF-8 输出编码（解决 Windows GBK 终端中文乱码）
    System.setProperty("file.encoding", "UTF-8");
    System.setProperty("sun.stdout.encoding", "UTF-8");
    System.setProperty("sun.stderr.encoding", "UTF-8");

    // 1. 创建 JLine 3 Terminal
    this.terminal = TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).build();

    // 2. 创建模型补全器（供 /model 命令使用）
    StringsCompleter modelCompleter = new StringsCompleter(MODEL_CANDIDATES);

    // 3. 创建 LineReader（支持 AUTO_MENU 向上弹窗）
    //    强制 LIST_MAX=3：补全菜单最多渲染 3 行，超出滚动——从根源锁死渲染边界
    //    对应 Layout.md §1 视口与边界数学防御策略
    this.reader =
        LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(modelCompleter)
            .option(LineReader.Option.AUTO_MENU, true)
            .variable(LineReader.LIST_MAX, COMPLETION_LIST_MAX)
            .build();

    // 4. 初始化组件
    HeaderComponent header = new HeaderComponent();
    ThoughtComponent thought = new ThoughtComponent();
    InputComponent input = new InputComponent();
    FooterComponent footer = new FooterComponent();

    this.layout = new TuiLayout(header, thought, input, footer);
    this.state = new TuiState();
    this.commandHandler = new CommandHandler(eventBridge);
    this.running = new AtomicBoolean(true);
    this.renderThread = new Thread(this::renderLoop, "alice-tui-render");
    this.renderThread.setDaemon(true);

    this.inputHistory = new ArrayDeque<>();
    this.historyIndex = 0;
    this.contentDirty = new AtomicBoolean(true);

    // 5. 注册事件监听
    setupEventListeners();

    // 6. 注册命令回调
    setupCommandCallbacks();

    // 7. 终端 resize 监听（JLine 3 方式：Signal.WINCH）
    terminal.handle(
        Terminal.Signal.WINCH,
        signal -> {
          layout.recalculate(terminal.getWidth(), terminal.getHeight());
          contentDirty.set(true);
        });

    // 初始布局计算
    layout.recalculate(terminal.getWidth(), terminal.getHeight());

    // 禁用终端回显 —— 由 LineReader 管理
    terminal.echo(false);
  }

  // ========== 初始化 ==========

  /** 启动 TUI */
  public void start() throws IOException {
    java.io.Writer writer = terminal.writer();

    // 清屏（ANSI 原生序列，兼容 dumb terminal）
    writer.write(ANSI_CLEAR_SCREEN);
    writer.flush();

    // 首次完整绘制
    fullRedraw();

    // 渲染循环在首次绘制后启动，确保初始状态可见
    renderThread.start();

    // 欢迎消息
    layout
        .thought()
        .addSystemMessage(
            "Alice Agent v0.1.0 TUI \u5DF2\u542F\u52A8\u3002\u8F93\u5165 /help \u67E5\u770B\u53EF\u7528\u547D\u4EE4\u3002");
    contentDirty.set(true);
  }

  // ========== 事件监听设置 ==========

  private void setupEventListeners() {
    eventBridge.addListener(
        event -> {
          switch (event) {
            case TuiEvent.StartThinking e -> {
              layout.thought().addAgentMessage("\u601D\u8003\u4E2D: " + e.prompt());
              contentDirty.set(true);
            }
            case TuiEvent.NewThought e -> {
              layout.thought().addThought(e.thought(), e.step());
              contentDirty.set(true);
            }
            case TuiEvent.ActionExecuting e -> {
              String desc =
                  e.action().type().name()
                      + (e.action().target() != null ? " (" + e.action().target() + ")" : "");
              layout.thought().addAction(desc);
              contentDirty.set(true);
            }
            case TuiEvent.ObservationResult e -> {
              layout.thought().addObservation(e.summary());
              contentDirty.set(true);
            }
            case TuiEvent.ChatMessage e -> {
              if ("User".equalsIgnoreCase(e.sender())) {
                layout.thought().addUserMessage(e.content());
              } else if ("System".equalsIgnoreCase(e.sender())) {
                layout.thought().addSystemMessage(e.content());
              } else {
                layout.thought().addAgentMessage(e.content());
              }
              contentDirty.set(true);
            }
            case TuiEvent.TaskComplete e -> {
              layout.thought().addAgentMessage(e.result());
              state.transitionTo(TuiState.State.IDLE);
              contentDirty.set(true);
            }
            case TuiEvent.TaskError e -> {
              layout.thought().addSystemMessage("\u9519\u8BEF: " + e.errorMessage());
              state.transitionTo(TuiState.State.ERROR);
              contentDirty.set(true);
            }
            case TuiEvent.TokenUpdate e -> {
              contentDirty.set(true);
            }
            default -> {}
          }
        });
  }

  private void setupCommandCallbacks() {
    commandHandler
        .onReset(
            args -> {
              layout.thought().clear();
              state.transitionTo(TuiState.State.IDLE);
              contentDirty.set(true);
            })
        .onClear(
            () -> {
              layout.thought().clear();
              contentDirty.set(true);
            })
        .onExit(
            () -> {
              running.set(false);
              if (onExit != null) onExit.run();
            })
        .onCommandOutput(
            output -> {
              if (onTaskSubmit != null) {
                onTaskSubmit.accept(output);
              }
            })
        .onModelSwitch(
            modelId -> {
              layout.footer().setModel(modelId);
              if (onModelSwitch != null) {
                onModelSwitch.accept(modelId);
              }
              contentDirty.set(true);
            });
  }

  // ========== 渲染 ==========

  /** 将光标移动到终端的指定行（0-indexed）。 */
  private void cursorLine(int row) {
    java.io.Writer writer = terminal.writer();
    try {
      writer.write("\033[" + (row + 1) + ";1H");
    } catch (IOException e) {
      logger.warn("cursorLine failed: row={}", row, e);
    }
  }

  /**
   * 全屏重绘（v2.0 布局）。
   *
   * <p>绘制顺序：Header(含暗色延伸线) → 滚动区内容 → 上分割线 → 输入区 → 下分割线 → 状态栏。
   *
   * <p>所有光标定位使用原始 ANSI \033[row;colH 序列，避免 InfoCmp.Capability 在 Windows 下失效。
   */
  private void fullRedraw() {
    java.io.Writer writer = terminal.writer();

    try {
      // 1. Header（自带暗色分隔线延伸到 [Session: xxx]）
      List<String> headerLines = layout.header().render();
      for (int i = 0; i < headerLines.size(); i++) {
        cursorLine(layout.header().row() + i);
        writer.write(headerLines.get(i));
        writer.write(ANSI_CLEAR_LINE);
      }

      // 2. 滚动区内容
      List<String> thoughtLines = layout.thought().render();
      for (int i = 0; i < thoughtLines.size(); i++) {
        cursorLine(layout.contentStartRow() + i);
        if (i < thoughtLines.size()) {
          writer.write(thoughtLines.get(i));
        }
        writer.write(ANSI_CLEAR_LINE);
      }

      // 3. 上分割线 (content 下方)
      cursorLine(layout.separator1Row());
      writer.write(layout.separatorLine());
      writer.write(ANSI_CLEAR_LINE);

      // 4. 输入区行
      List<String> inputLines = layout.input().render();
      for (int i = 0; i < inputLines.size(); i++) {
        cursorLine(layout.inputRow() + i);
        writer.write(inputLines.get(i));
        writer.write(ANSI_CLEAR_LINE);
      }

      // 5. 下分割线 (input 下方)
      cursorLine(layout.separator2Row());
      writer.write(layout.separatorLine());
      writer.write(ANSI_CLEAR_LINE);

      // 6. 底部状态栏（ANSI 256 色）
      List<String> footerLines = layout.footer().render();
      for (int i = 0; i < footerLines.size(); i++) {
        cursorLine(layout.footerRow() + i);
        writer.write(footerLines.get(i));
        writer.write(ANSI_CLEAR_LINE);
      }

      writer.flush();

    } catch (IOException e) {
      logger.error("Full redraw failed", e);
    }
  }

  /**
   * 仅重绘上方滚动区（Header + 滚动内容 + 上分割线）。
   *
   * <p>不触碰输入行和状态栏，避免闪烁。分割线使用 ANSI 暗色渲染。
   */
  private void redrawScrollArea() {
    java.io.Writer writer = terminal.writer();

    try {
      // 1. Header（自带暗色延伸分隔线）
      List<String> headerLines = layout.header().render();
      for (int i = 0; i < headerLines.size(); i++) {
        cursorLine(layout.header().row() + i);
        writer.write(headerLines.get(i));
        writer.write(ANSI_CLEAR_LINE);
      }

      // 2. 滚动区内容（逐行重绘）
      List<String> thoughtLines = layout.thought().render();
      int contentRows = layout.contentHeight();
      for (int i = 0; i < contentRows; i++) {
        cursorLine(layout.contentStartRow() + i);
        if (i < thoughtLines.size()) {
          writer.write(thoughtLines.get(i));
        }
        writer.write(ANSI_CLEAR_LINE);
      }

      // 3. 上分割线 (content 下方)
      cursorLine(layout.separator1Row());
      writer.write(layout.separatorLine());
      writer.write(ANSI_CLEAR_LINE);

      writer.flush();
    } catch (IOException e) {
      logger.error("Scroll area redraw failed", e);
    }
  }

  /** 渲染循环：定期检查 contentDirty，增量重绘上方滚动区 */
  private void renderLoop() {
    while (running.get()) {
      try {
        if (contentDirty.compareAndSet(true, false)) {
          redrawScrollArea();
        }
        Thread.sleep(FRAME_INTERVAL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  // ========== 主输入循环 ==========

  /**
   * 运行主输入循环。
   *
   * <p>使用 JLine 3 LineReader 的 readLine() 方法处理用户输入。 LineReader 原生支持 AUTO_MENU（自动完成弹窗）， LIST_MAX=3
   * 强制限制补全菜单最大行数，从根源锁死渲染边界。
   *
   * <p>对应 Layout.md §1 视口与边界数学防御策略 + §7.2 Inline Completion Mode。
   */
  public void runInputLoop() {
    while (running.get()) {
      // 确保上方滚动区是最新状态
      if (contentDirty.get()) {
        redrawScrollArea();
        contentDirty.set(false);
      }

      // 定位光标到输入区（原始 ANSI 序列），并清除下方残影
      cursorLine(layout.inputRow());
      terminal.writer().write("\033[J"); // 清除光标到屏幕底端
      terminal.writer().flush();

      // 同步 JLine LineReader 内部光标位置：设置 LINE_OFFSET 为输入起始行
      // LineReader 默认光标从当前终端位置开始，但内部可能缓存了上一次的偏移。
      // 强制设置 LINE_OFFSET 告知 reader 输入起始行号（0-indexed）。
      reader.setVariable(LineReader.LINE_OFFSET, layout.inputRow());

      // 使用 JLine 3 LineReader 读取输入（支持 AUTO_MENU 补全弹窗）
      // 补全菜单在输入行上方自然展开，最多 3 行（LIST_MAX=3），不干扰下方分割线和状态栏
      String line;
      try {
        line = reader.readLine(layout.input().prompt());
      } catch (EndOfFileException e) {
        // Ctrl+D
        break;
      } catch (UserInterruptException e) {
        // Ctrl+C → exit
        running.set(false);
        if (onExit != null) onExit.run();
        break;
      }

      if (line == null) break;

      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;

      // 添加到历史
      inputHistory.addLast(trimmed);
      historyIndex = inputHistory.size();

      // 同步 InputComponent 状态
      layout.input().setText(trimmed);

      // 检查斜杠命令
      SlashCommand slashCmd = SlashCommand.parse(trimmed);
      if (slashCmd != null) {
        boolean handled = commandHandler.execute(slashCmd);
        if (handled) {
          contentDirty.set(true);
          continue;
        }
      }

      // 检查是否允许提交任务
      if (state.isRunning()) {
        layout
            .thought()
            .addSystemMessage(
                "Agent \u6B63\u5728\u6267\u884C\u4E2D\uFF0C\u8BF7\u7B49\u5F85\u5B8C\u6210\u6216\u6309 F5 \u505C\u6B62\u3002");
        contentDirty.set(true);
        continue;
      }

      // 提交 Agent 任务
      layout.thought().addUserMessage(trimmed);
      state.transitionTo(TuiState.State.RUNNING);
      contentDirty.set(true);

      if (onTaskSubmit != null) {
        onTaskSubmit.accept(trimmed);
      }

      eventBridge.onStartThinking(trimmed);
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

  public TuiLayout layout() {
    return layout;
  }

  public TuiState state() {
    return state;
  }

  public EventBridge eventBridge() {
    return eventBridge;
  }

  public Terminal terminal() {
    return terminal;
  }

  public LineReader reader() {
    return reader;
  }

  // ========== 外部触发更新 ==========

  /** 标记内容为脏，触发渲染线程重绘。外部（如 Agent 线程）可调用此方法。 */
  public void markContentDirty() {
    this.contentDirty.set(true);
  }

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
      terminal.writer().write(ANSI_CLEAR_SCREEN);
      terminal.writer().flush();
    } catch (Exception e) {
      logger.warn("Error clearing screen on close", e);
    }
    try {
      terminal.close();
    } catch (Exception e) {
      logger.warn("Error closing terminal", e);
    }
  }
}
