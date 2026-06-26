package org.cland.alice.facade.tui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.cland.alice.agent.command.AgentCommand;
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

  /**
   * 终端尺寸轮询间隔（帧数）。
   *
   * <p>每 {@value #SIZE_POLL_INTERVAL_FRAMES} 个渲染帧（即 {@value #SIZE_POLL_INTERVAL_FRAMES} × {@value
   * #FRAME_INTERVAL_MS} ms） 轮询一次终端尺寸， 作为 WINCH 信号不可用或未抵达时的保底 resize 检测机制。
   */
  private static final int SIZE_POLL_INTERVAL_FRAMES = 5; // 500ms

  /** 补全菜单最大展示行数 —— v2.3 边界防御：锁定 3 行，溢出自动内部滚动，杜绝底部状态栏被顶出 */
  private static final int COMPLETION_LIST_MAX = 3;

  /** ANSI 转义序列（光标定位使用 terminal.puts，清屏操作使用原始 ANSI） */
  private static final String ANSI_CLEAR_SCREEN = "\033[2J\033[H";

  private static final String ANSI_CLEAR_LINE = "\033[K";

  /**
   * ANSI 光标定位模板：{@code \033[<row>;1H}，其中 {@code row} 为 1-indexed。
   *
   * <p>用于后台渲染线程，避免使用 {@code terminal.puts()} 干扰 JLine 的内部光标状态。
   */
  private static final String ANSI_CURSOR_LINE = "\033[%d;1H";

  private final Terminal terminal;
  private final LineReader reader;

  private final TuiLayout layout;
  private final TuiState state;
  private final EventBridge eventBridge;
  private final CommandHandler commandHandler;

  private final AtomicBoolean running;
  private final Thread renderThread;

  /**
   * 终端输出锁：用于同步主线程（输入循环）与后台渲染线程对 {@code terminal.writer()} 的写入。 防止 {@link #redrawScrollArea()} 的原始
   * ANSI 写入与 JLine 的 {@code readLine()} 内部光标定位产生竞态，导致输入提示符出现在错误行或底部状态栏被覆盖。
   */
  private final Object terminalLock = new Object();

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

  /** 输入活跃标记——主线程在 readLine 中时置为 true，阻止渲染线程竞争写入终端 */
  private final AtomicBoolean inputActive;

  /** 输入期间积压的重绘标记——inputActive 期间 contentDirty 被设置时记录，输入结束后处理 */
  private final AtomicBoolean pendingRedraw;

  /**
   * 终端尺寸变更标记。
   *
   * <p>当终端 WINCH 触发时置为 {@code true}，渲染线程在下次重绘前会先全屏清除旧内容， 再按新布局绘制所有区域，确保 footer 不会残留在旧位置。
   */
  private final AtomicBoolean needsFullClear;

  /** 上次记录的终端宽度，用于轮询检测 resize */
  private volatile int lastPollWidth;

  /** 上次记录的终端高度，用于轮询检测 resize */
  private volatile int lastPollHeight;

  /** 渲染帧计数器，用于定时轮询终端尺寸 */
  private int frameCount;

  /**
   * 恢复被 JLine 补全菜单覆盖的底部区域（下分割线 + 状态栏）。
   *
   * <p>JLine 的 AUTO_MENU 补全列表在输入行下方渲染，当用户键入 "/" 时， 补全项会覆盖 separator2 和 footer 行。readLine 返回后 JLine
   * 不会恢复这些内容， 因此每次读取后都需要显式重绘。
   */
  private void restoreLowerArea() {
    synchronized (terminalLock) {
      java.io.Writer writer = terminal.writer();
      try {
        // 恢复被完成菜单覆盖的分割线（补全菜单位于输入行上方，可能覆盖分割线）
        cursorLine(layout.separatorRow());
        writer.write(layout.separatorLine());
        writer.write(ANSI_CLEAR_LINE);

        writer.flush();
      } catch (IOException e) {
        logger.warn("Failed to restore lower area after JLine completion menu", e);
      }
    }
  }

  /** 模型补全列表 */
  private static final List<String> MODEL_CANDIDATES =
      List.of(
          "deepseek-v4-flash \u2022 medium",
          "deepseek-v4-reasoning \u2022 deep",
          "gpt-4o-mini",
          "claude-3.5-sonnet");

  /** 斜杠命令补全列表（由 {@link SlashCommand} 提取） */
  private static final List<String> SLASH_COMMAND_CANDIDATES =
      List.of(
          "/new",
          "/clear",
          "/context",
          "/compact",
          "/feedback",
          "/exit",
          "/help",
          "/prompt",
          "/history",
          "/exec",
          "/model",
          "/tools",
          "/routine",
          "/sub-agent",
          "/resume");

  // ========== 构造 ==========

  public ScreenManager(EventBridge eventBridge) throws IOException {
    this.eventBridge = eventBridge;

    // 强制 UTF-8 输出编码（解决 Windows GBK 终端中文乱码）
    System.setProperty("file.encoding", "UTF-8");
    System.setProperty("sun.stdout.encoding", "UTF-8");
    System.setProperty("sun.stderr.encoding", "UTF-8");

    // 1. 创建 JLine 3 Terminal
    //    在 Windows 上，JLine 3.27.1 默认的 FFM provider 需要 --enable-native-access，
    //    如果未设置该 JVM 标志，FFM/JNA/Jansi 会依次失败，最终回退到 dumb terminal。
    //    这里我们主动设置 provider=jansi 并通过系统属性优先确保 ANSI 支持。
    //    如果仍失败，会 fallback 到 dumb terminal（此时 output 会退化，但不会崩溃）。
    this.terminal = createTerminal();

    // 2. 创建命令补全器（斜杠命令 + 模型名，按优先级聚合）
    var allCandidates = new java.util.ArrayList<String>();
    allCandidates.addAll(SLASH_COMMAND_CANDIDATES);
    allCandidates.addAll(MODEL_CANDIDATES);
    StringsCompleter cmdCompleter = new StringsCompleter(allCandidates);

    // 3. 创建 LineReader（支持 AUTO_MENU 向上弹窗）
    //    LIST_MAX=5：斜杠命令较多，展示 5 行
    //    LINE_OFFSET 在每次 readLine 前动态设置为输入行位置，
    //    使 JLine 感知到下方空间不足（仅 2 行），从而自动将补全列表渲染到输入行上方。
    this.reader =
        LineReaderBuilder.builder()
            .terminal(this.terminal)
            .completer(cmdCompleter)
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
    this.inputActive = new AtomicBoolean(false);
    this.pendingRedraw = new AtomicBoolean(false);
    this.needsFullClear = new AtomicBoolean(false);
    this.lastPollWidth = this.terminal.getWidth();
    this.lastPollHeight = this.terminal.getHeight();
    this.frameCount = 0;

    // 5. 注册事件监听
    setupEventListeners();

    // 6. 注册命令回调
    setupCommandCallbacks();

    // 7. 终端 resize 监听（JLine Signal.WINCH → EventBridge 事件系统）
    this.terminal.handle(
        Terminal.Signal.WINCH,
        signal -> {
          int w = terminal.getWidth();
          int h = terminal.getHeight();
          logger.debug("WINCH signal received: {}x{}", w, h);
          // 通过 EventBridge 事件系统统一分发 resize 事件
          eventBridge.onTerminalResize(w, h);
        });

    // 初始布局计算
    int initW = this.terminal.getWidth();
    int initH = this.terminal.getHeight();
    logger.info("[ScreenManager] init terminal size: {}x{}", initW, initH);
    layout.recalculate(initW, initH);
    logger.info(
        "[ScreenManager] layout: sepRow={}, inputRow={}, sep2Row={}, footerRow={}",
        layout.separatorRow(),
        layout.inputRow(),
        layout.separator2Row(),
        layout.footerRow());

    // 禁用终端回显 —— 由 LineReader 管理
    this.terminal.echo(false);
  }

  /**
   * 创建 JLine 3 Terminal。
   *
   * <p>优先创建系统终端；如果失败（例如 Windows 上缺少 --enable-native-access）， 则回退到 dumb terminal（带 UTF-8 编码支持）。
   */
  private static Terminal createTerminal() throws IOException {
    try {
      return TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).build();
    } catch (Exception e) {
      // 系统终端创建失败：回退到 dumb terminal
      logger.warn(
          "Failed to create system terminal ({}), falling back to dumb terminal", e.getMessage());
      return TerminalBuilder.builder().dumb(true).encoding(StandardCharsets.UTF_8).build();
    }
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

    // 标记内容为脏，确保 runInputLoop() 首次迭代触发 redrawScrollArea()，
    // 使光标同步到正确的输入行位置，避免首字符出现在底部 footer 下方。
    contentDirty.set(true);
  }

  // ========== 事件监听设置 ==========

  private void setupEventListeners() {
    eventBridge.addListener(
        event -> {
          switch (event) {
            case TuiEvent.StartThinking e -> {
              // User input already displayed via addUserMessage in runInputLoop();
              // No duplicate content needed — just trigger re-render.
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
              String result = e.result();
              if (result != null && !result.isBlank()) {
                layout.thought().addAgentMessage(result);
              }
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
            case TuiEvent.TerminalResize e -> {
              int w = e.width();
              int h = e.height();
              logger.info("TerminalResize event: {}x{}", w, h);
              layout.recalculate(w, h);
              reader.setVariable(LineReader.LINE_OFFSET, layout.inputRow());
              lastPollWidth = w;
              lastPollHeight = h;
              needsFullClear.set(true);
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

  /**
   * 将光标移动到终端的指定行（0-indexed），列固定为 0。
   *
   * <p>使用 JLine 的 {@code terminal.puts(Capability.cursor_address)} 进行跨平台光标定位， 确保 JLine
   * 内部光标状态与终端物理光标位置保持同步（修复 Windows 上光标偏左/偏移问题）。
   *
   * <p>仅在 {@link #fullRedraw()}（输入循环开始前）和 {@link #runInputLoop()} 中使用。 后台渲染线程请使用 {@link
   * #cursorLineRaw(int)} 以避免干扰 JLine 的输入光标追踪。
   */
  private void cursorLine(int row) {
    terminal.puts(org.jline.utils.InfoCmp.Capability.cursor_address, row, 0);
    terminal.flush();
  }

  /**
   * 使用原始 ANSI 转义码将光标移动到指定行（0-indexed），列固定为 1。
   *
   * <p>不经过 {@code terminal.puts()}，因此不会更新 JLine 的内部光标状态。 专用于 {@link #redrawScrollArea()}
   * 等从后台渲染线程调用的场景， 避免与主线程中 {@code reader.readLine()} 的 JLine 光标追踪发生冲突。
   */
  private void cursorLineRaw(int row) {
    java.io.Writer writer = terminal.writer();
    try {
      writer.write(String.format(ANSI_CURSOR_LINE, row + 1));
    } catch (IOException e) {
      // 静默失败，下次重绘会覆盖
    }
  }

  /**
   * 全屏重绘（v2.6 布局）。
   *
   * <p>绘制顺序：Header → 滚动区内容 → 分割线 → 输入区 → 分割线 → Footer。
   */
  private void fullRedraw() {
    java.io.Writer writer = terminal.writer();

    try {
      // 1. Header
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

      // 3. 分割线 (滚动区和输入区之间)
      cursorLine(layout.separatorRow());
      writer.write(layout.separatorLine());
      writer.write(ANSI_CLEAR_LINE);

      // 4. 输入区
      List<String> inputLines = layout.input().render();
      for (int i = 0; i < inputLines.size(); i++) {
        cursorLine(layout.inputRow() + i);
        writer.write(inputLines.get(i));
        writer.write(ANSI_CLEAR_LINE);
      }

      // 5. 分割线 (输入区和 footer 之间)
      cursorLine(layout.separator2Row());
      writer.write(layout.separatorLine());
      writer.write(ANSI_CLEAR_LINE);

      // 6. Footer (终端最底行)
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
   * 重绘所有静态区域（Header + 滚动内容 + 上下分割线 + 底部状态栏）。
   *
   * <p>不触碰输入行（由 LineReader 管理），避免闪烁。分割线使用 ANSI 暗色渲染。
   *
   * <p>底部状态栏（Footer）在此重绘，确保 {@code runInputLoop()} 中的 {@code \033[2K} 或其他清屏操作不会导致状态栏永久消失。
   */
  private void redrawScrollArea() {
    synchronized (terminalLock) {
      java.io.Writer writer = terminal.writer();

      try {
        // 终端尺寸变更后必须先全屏清除旧内容，否则旧位置的 header/footer
        // 残留像素会与新布局位置重叠，导致 footer 不在底部等视觉错乱。
        if (needsFullClear.compareAndSet(true, false)) {
          writer.write(ANSI_CLEAR_SCREEN);
        }

        // 使用 cursorLine()（terminal.puts）定位光标，与 fullRedraw 一致。
        // cursorLine 使用 JLine 的 terminfo 机制生成正确的转义序列，
        // 确保在各类终端上光标精确定位到正确的物理行。

        // 1. Header
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

        // 3. 分割线 (滚动区和输入区之间)
        cursorLine(layout.separatorRow());
        writer.write(layout.separatorLine());
        writer.write(ANSI_CLEAR_LINE);

        // 4. 分割线 (输入区和 footer 之间)
        cursorLine(layout.separator2Row());
        writer.write(layout.separatorLine());
        writer.write(ANSI_CLEAR_LINE);

        // 5. Footer (终端最底行)
        List<String> footerLines = layout.footer().render();
        for (int i = 0; i < footerLines.size(); i++) {
          cursorLine(layout.footerRow() + i);
          writer.write(footerLines.get(i));
          writer.write(ANSI_CLEAR_LINE);
        }

        writer.flush();
      } catch (IOException e) {
        logger.error("Scroll area redraw failed", e);
      }
    }
  }

  /**
   * 渲染循环：仅在 contentDirty 时重绘静态区域。
   *
   * <p>不在无内容变更时写入终端。任何非主线程的原始 ANSI 写入 （包括 {@link #restoreLowerArea()}）都会与 JLine 的 {@code
   * readLine()} 竞争终端输出流，导致输入响应延迟或光标位置错乱。 底部区域的恢复仅由主线程在 {@code readLine()} 返回后 通过 {@link
   * #restoreLowerArea()} 完成。
   */
  private void renderLoop() {
    while (running.get()) {
      try {
        // 1. 常规内容脏标记检查
        if (inputActive.get()) {
          // 输入活跃期间不写入终端，避免与 JLine 的 readLine() 竞争输出流。
          // 积压的重绘由主线程在 readLine() 返回后处理。
          if (contentDirty.get()) {
            pendingRedraw.set(true);
            contentDirty.set(false);
          }
        } else if (contentDirty.compareAndSet(true, false)) {
          redrawScrollArea();
        }

        // 2. 轮询终端尺寸（保底 resize 检测）
        frameCount++;
        if (frameCount % SIZE_POLL_INTERVAL_FRAMES == 0) {
          int w = terminal.getWidth();
          int h = terminal.getHeight();
          if (w != lastPollWidth || h != lastPollHeight) {
            logger.info(
                "Size poll detected resize: {}x{} -> {}x{}", lastPollWidth, lastPollHeight, w, h);
            // 通过 EventBridge 事件系统统一分发 resize 事件
            eventBridge.onTerminalResize(w, h);
          }
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
   * <p>使用 JLine LineReader 的 readLine() 方法处理用户输入。 LineReader 原生支持 AUTO_MENU（自动完成弹窗）， LIST_MAX=3
   * 强制限制补全菜单最大行数，从根源锁死渲染边界。
   *
   * <p>对应 Layout.md §1 视口与边界数学防御策略 + §7.2 Inline Completion Mode。
   *
   * <p>首次 readLine 初始化：JLine 的显示层在首次 readLine() 调用时初始化。
   * 调用 terminal.getHeight() 发送终端尺寸查询（终端 I/O 同步点），
   * 确保终端完成处理所有先前输出；raw ANSI 定位光标后 readLine 启动时，
   * 光标已在正确行。LINE_OFFSET=2 保留 input 下方 2 行不被 JLine 覆盖。
   */
  public void runInputLoop() {
    while (running.get()) {
      if (contentDirty.get()) {
        redrawScrollArea();
        contentDirty.set(false);
      }

      // 在 sync 外提前读取布局值和终端尺寸，加入终端 I/O 间隔
      int inputRow = layout.inputRow();
      int termH = terminal.getHeight();
      reader.getVariable(LineReader.LINE_OFFSET);

      synchronized (terminalLock) {
        terminal.writer().write(String.format(ANSI_CURSOR_LINE, inputRow + 1));
        terminal.writer().write("\033[2K");
        terminal.writer().flush();
      }

      reader.setVariable(LineReader.LINE_OFFSET, 2);

      inputActive.set(true);
      String line;
      try {
        line = reader.readLine(layout.input().prompt());
      } catch (EndOfFileException e) {
        inputActive.set(false);
        break;
      } catch (UserInterruptException e) {
        inputActive.set(false);
        running.set(false);
        if (onExit != null) onExit.run();
        break;
      } finally {
        inputActive.set(false);
      }

      // 处理输入期间积压的 deferred 重绘
      if (pendingRedraw.compareAndSet(true, false)) {
        contentDirty.set(true);
        redrawScrollArea();
        contentDirty.set(false);
      }

      // JLine 补全菜单可能在输入行上方渲染，覆盖了分割线和状态栏。
      restoreLowerArea();

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

  public ScreenManager onAgentCommand(Consumer<AgentCommand> callback) {
    this.commandHandler.onAgentCommand(callback);
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
