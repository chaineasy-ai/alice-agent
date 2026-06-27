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
 * ScreenManager：基于 JLine 3 的 TUI 核心管理器 — TAO 四段式布局 (v3.1)。
 *
 * <p>对应 Layout_TAO.md 扩展四段式布局：
 *
 * <ul>
 *   <li>输入内容区（InputBlock）—— 用户输入和会话上下文
 *   <li>思考内容区（ThinkBlock）—— Agent 推理/思考过程
 *   <li>动作内容区（ActionBlock）—— 执行的命令（同 InputBlock 风格）
 *   <li>观察内容区（ObserveBlock）—— 命令执行输出的结果
 * </ul>
 *
 * <p>参考 docs/alice-facade-tui/Layout_TAO.md
 */
public class ScreenManager implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(ScreenManager.class);

  private static final long FRAME_INTERVAL_MS = 100;
  private static final int SIZE_POLL_INTERVAL_FRAMES = 5;
  private static final int COMPLETION_LIST_MAX = 3;

  private static final String ANSI_CLEAR_SCREEN = "\033[2J\033[H";
  private static final String ANSI_CLEAR_LINE = "\033[K";
  private static final String ANSI_CURSOR_LINE = "\033[%d;1H";

  private final Terminal terminal;
  private final LineReader reader;
  private final TuiLayout layout;
  private final TuiState state;
  private final EventBridge eventBridge;
  private final CommandHandler commandHandler;
  private final AtomicBoolean running;
  private final Thread renderThread;
  private final Object terminalLock = new Object();

  private final Deque<String> inputHistory;
  private final Deque<String> inputQueue;
  private int historyIndex;
  private Consumer<String> onTaskSubmit;
  private Runnable onClear;
  private Runnable onExit;
  private Consumer<String> onModelSwitch;
  private final AtomicBoolean contentDirty;

  /** 输入活跃标记 — 主线程在 readLine 中时置为 true，阻止渲染线程竞争写入终端 */
  private final AtomicBoolean inputActive;

  /** 输入期间积压的重绘标记 — inputActive 期间 contentDirty 被设置时记录，输入结束后处理 */
  private final AtomicBoolean pendingRedraw;

  private final AtomicBoolean needsFullClear;
  private volatile int lastPollWidth;
  private volatile int lastPollHeight;
  private int frameCount;

  private static final List<String> MODEL_CANDIDATES =
      List.of(
          "deepseek-v4-flash \u2022 medium",
          "deepseek-v4-reasoning \u2022 deep",
          "gpt-4o-mini",
          "claude-3.5-sonnet");

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

    System.setProperty("file.encoding", "UTF-8");
    System.setProperty("sun.stdout.encoding", "UTF-8");
    System.setProperty("sun.stderr.encoding", "UTF-8");

    this.terminal = createTerminal();

    var allCandidates = new java.util.ArrayList<String>();
    allCandidates.addAll(SLASH_COMMAND_CANDIDATES);
    allCandidates.addAll(MODEL_CANDIDATES);
    StringsCompleter cmdCompleter = new StringsCompleter(allCandidates);

    this.reader =
        LineReaderBuilder.builder()
            .terminal(this.terminal)
            .completer(cmdCompleter)
            .option(LineReader.Option.AUTO_MENU, true)
            .variable(LineReader.LIST_MAX, COMPLETION_LIST_MAX)
            .build();

    // 初始化 TAO 四段式组件
    HeaderComponent header = new HeaderComponent();
    InputBlockComponent inputBlock = new InputBlockComponent();
    ThinkBlockComponent thinkBlock = new ThinkBlockComponent();
    ActionBlockComponent actionBlock = new ActionBlockComponent();
    ObserveBlockComponent observeBlock = new ObserveBlockComponent();
    InputComponent input = new InputComponent();
    FooterComponent footer = new FooterComponent();

    this.layout =
        new TuiLayout(header, inputBlock, thinkBlock, actionBlock, observeBlock, input, footer);
    this.state = new TuiState();
    this.commandHandler = new CommandHandler(eventBridge);
    this.running = new AtomicBoolean(true);
    this.renderThread = new Thread(this::renderLoop, "alice-tui-render");
    this.renderThread.setDaemon(true);

    this.inputHistory = new ArrayDeque<>();
    this.inputQueue = new ArrayDeque<>();
    this.historyIndex = 0;
    this.contentDirty = new AtomicBoolean(true);
    this.inputActive = new AtomicBoolean(false);
    this.pendingRedraw = new AtomicBoolean(false);
    this.needsFullClear = new AtomicBoolean(false);
    this.lastPollWidth = this.terminal.getWidth();
    this.lastPollHeight = this.terminal.getHeight();
    this.frameCount = 0;

    setupEventListeners();
    setupCommandCallbacks();

    this.terminal.handle(
        Terminal.Signal.WINCH,
        signal -> {
          int w = terminal.getWidth();
          int h = terminal.getHeight();
          logger.debug("WINCH signal received: {}x{}", w, h);
          eventBridge.onTerminalResize(w, h);
        });

    int initW = this.terminal.getWidth();
    int initH = this.terminal.getHeight();
    logger.info("[ScreenManager] init terminal size: {}x{}", initW, initH);
    layout.recalculate(initW, initH);
    logger.info(
        "[ScreenManager] TAO layout: input=[{}-{}] think=[{}-{}] action=[{}-{}] observe=[{}-{}] sep={} input={} footer={}",
        layout.inputBlockStartRow(),
        layout.inputBlockStartRow() + layout.inputBlockHeight() - 1,
        layout.thinkBlockStartRow(),
        layout.thinkBlockStartRow() + layout.thinkBlockHeight() - 1,
        layout.actionBlockStartRow(),
        layout.actionBlockStartRow() + layout.actionBlockHeight() - 1,
        layout.observeBlockStartRow(),
        layout.observeBlockStartRow() + layout.observeBlockHeight() - 1,
        layout.separatorRow(),
        layout.inputRow(),
        layout.footerRow());

    this.terminal.echo(false);
  }

  private static Terminal createTerminal() throws IOException {
    try {
      return TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).build();
    } catch (Exception e) {
      logger.warn(
          "Failed to create system terminal ({}), falling back to dumb terminal", e.getMessage());
      return TerminalBuilder.builder().dumb(true).encoding(StandardCharsets.UTF_8).build();
    }
  }

  // ========== 初始化 ==========

  public void start() throws IOException {
    java.io.Writer writer = terminal.writer();
    writer.write(ANSI_CLEAR_SCREEN);
    writer.flush();
    fullRedraw();
    renderThread.start();
    contentDirty.set(true);
  }

  // ========== TAO 事件路由 (v3.1) ==========

  /**
   * TAO 四段式事件路由:
   *
   * <ul>
   *   <li>StartThinking → InputBlock: 显示 ✓ + 用户输入
   *   <li>NewThought → ThinkBlock: 推理过程
   *   <li>ActionExecuting → ActionBlock: $ command (timeout 120s)
   *   <li>ObservationResult → ObserveBlock: 输出 + Took X.XXs
   *   <li>ChatMessage(User) → InputBlock
   *   <li>ChatMessage(System/Agent) → ThinkBlock
   *   <li>TaskComplete → ThinkBlock
   *   <li>TaskError → ThinkBlock
   * </ul>
   */
  private void setupEventListeners() {
    eventBridge.addListener(
        event -> {
          switch (event) {
            case TuiEvent.StartThinking e -> {
              // 用户输入已由 runInputLoop() 直接写入 InputBlock，此处不重复
              contentDirty.set(true);
            }
            case TuiEvent.NewThought e -> {
              layout.thinkBlock().addThought(e.thought(), e.step(), e.traceId());
              contentDirty.set(true);
            }
            case TuiEvent.ActionExecuting e -> {
              String desc =
                  e.action().type().name()
                      + (e.action().target() != null ? " (" + e.action().target() + ")" : "");
              layout.actionBlock().addCommand(desc);
              // Also route to ThinkBlock for chronological PAO flow
              String actionTarget = e.action().target();
              if (actionTarget != null && !actionTarget.isBlank()) {
                layout.thinkBlock().addActionLine(actionTarget, e.traceId());
              }
              contentDirty.set(true);
            }
            case TuiEvent.ObservationResult e -> {
              layout.observeBlock().addOutput(e.summary());
              layout.observeBlock().addTiming(e.elapsedSec());
              // Also route to ThinkBlock for chronological PAO flow
              layout.thinkBlock().addObservationLine(e.summary(), e.elapsedSec());
              contentDirty.set(true);
            }
            case TuiEvent.ChatMessage e -> {
              if ("User".equalsIgnoreCase(e.sender())) {
                layout.thinkBlock().addUserMessage(e.content());
              } else if ("System".equalsIgnoreCase(e.sender())) {
                layout.thinkBlock().addSystemMessage(e.content());
              } else {
                layout.thinkBlock().addAgentMessage(e.content());
              }
              contentDirty.set(true);
            }
            case TuiEvent.TaskComplete e -> {
              String result = e.result();
              if (result != null && !result.isBlank()) {
                layout.thinkBlock().addAgentMessage(result);
              }
              state.transitionTo(TuiState.State.IDLE);
              dispatchNextFromQueue();
              contentDirty.set(true);
            }
            case TuiEvent.TaskError e -> {
              layout.thinkBlock().addSystemMessage("\u9519\u8BEF: " + e.errorMessage());
              state.transitionTo(TuiState.State.ERROR);
              contentDirty.set(true);
            }
            case TuiEvent.TokenUpdate e -> contentDirty.set(true);
            case TuiEvent.TerminalResize e -> {
              int w = e.width();
              int h = e.height();
              logger.info("TerminalResize event: {}x{}", w, h);
              layout.recalculate(w, h);
              reader.setVariable(
                  LineReader.LINE_OFFSET,
                  Math.max(
                      1, layout.footerRow() + layout.footer().height() - layout.inputRow() - 1));
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
              layout.inputBlock().clear();
              layout.thinkBlock().clear();
              layout.actionBlock().clear();
              layout.observeBlock().clear();
              state.transitionTo(TuiState.State.IDLE);
              contentDirty.set(true);
            })
        .onClear(
            () -> {
              layout.thinkBlock().clear();
              layout.actionBlock().clear();
              layout.observeBlock().clear();
              contentDirty.set(true);
            })
        .onExit(
            () -> {
              running.set(false);
              if (onExit != null) onExit.run();
            })
        .onCommandOutput(
            output -> {
              if (onTaskSubmit != null) onTaskSubmit.accept(output);
            })
        .onModelSwitch(
            modelId -> {
              layout.footer().setModel(modelId);
              if (onModelSwitch != null) onModelSwitch.accept(modelId);
              contentDirty.set(true);
            });
  }

  // ========== 渲染 ==========

  private void cursorLine(int row) {
    terminal.puts(org.jline.utils.InfoCmp.Capability.cursor_address, row, 0);
    terminal.flush();
  }

  /** 写入单行内容到指定行（含行尾清除）。row 为 0-indexed。 */
  private void writeRow(java.io.Writer writer, int row, String content) throws java.io.IOException {
    writer.write(String.format(ANSI_CURSOR_LINE, row + 1));
    writer.write(content != null ? content : "");
    writer.write(ANSI_CLEAR_LINE);
  }

  /** 全屏重绘 — 每个组件通过 {@code renderTo(writer)} 自行处理光标定位和行尾清除。 */
  private void fullRedraw() {
    java.io.Writer writer = terminal.writer();
    try {
      layout.header().renderTo(writer);
      layout.inputBlock().renderTo(writer);
      layout.thinkBlock().renderTo(writer);
      layout.actionBlock().renderTo(writer);
      layout.observeBlock().renderTo(writer);

      writeRow(writer, layout.separatorRow(), layout.separatorLine());
      writeRow(writer, layout.queueRow(), layout.queueLine());

      layout.input().renderTo(writer);

      writeRow(writer, layout.separator2Row(), layout.separatorLine());
      layout.footer().renderTo(writer);

      writer.flush();
    } catch (IOException e) {
      logger.error("Full redraw failed", e);
    }
  }

  /**
   * 重绘所有静态区域 — TAO 四段式。
   *
   * <p>不触碰输入行（由 LineReader 管理）。
   */
  private void redrawScrollArea() {
    synchronized (terminalLock) {
      java.io.Writer writer = terminal.writer();
      try {
        if (needsFullClear.compareAndSet(true, false)) {
          writer.write(ANSI_CLEAR_SCREEN);
        }

        layout.header().renderTo(writer);
        layout.inputBlock().renderTo(writer);
        layout.thinkBlock().renderTo(writer);
        layout.actionBlock().renderTo(writer);
        layout.observeBlock().renderTo(writer);

        writeRow(writer, layout.separatorRow(), layout.separatorLine());
        writeRow(writer, layout.queueRow(), layout.queueLine());

        writeRow(writer, layout.separator2Row(), layout.separatorLine());
        layout.footer().renderTo(writer);

        writer.flush();
      } catch (IOException e) {
        logger.error("Scroll area redraw failed", e);
      }
    }
  }

  private void renderLoop() {
    while (running.get()) {
      try {
        if (contentDirty.compareAndSet(true, false)) {
          // safe: redrawScrollArea() only writes to rows above the input line
          // (header, inputBlock, thinkBlock, actionBlock, observeBlock, separator,
          // queue, separator2, footer). It never touches the JLine-managed input
          // row (inputRow), so no interference with reader.readLine().
          redrawScrollArea();
        }
        frameCount++;
        if (frameCount % SIZE_POLL_INTERVAL_FRAMES == 0) {
          int w = terminal.getWidth();
          int h = terminal.getHeight();
          if (w != lastPollWidth || h != lastPollHeight) {
            logger.info(
                "Size poll detected resize: {}x{} -> {}x{}", lastPollWidth, lastPollHeight, w, h);
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

  public void runInputLoop() {
    contentDirty.set(true);

    while (running.get()) {
      // 同步块外：先重绘静态内容区，然后设置 JLine 保留行数。
      // LINE_OFFSET 必须在光标定位之前设置，确保 JLine 在显示初始化时
      // 已经知道保留行数，不会将 scroll region 设置到覆盖 footer。
      if (contentDirty.get()) {
        redrawScrollArea();
        // Intentionally NOT clearing contentDirty here — leave it for the renderLoop's
        // atomic CAS. If we clear it, we risk losing concurrent contentDirty.set(true)
        // calls from event listeners firing during redrawScrollArea().
        // The renderLoop (run every 100ms) will pick it up atomically.
      }

      // LINE_OFFSET：保留 input 组件下方所有行不被 JLine 覆盖（separator2 + Footer）。
      // 从 input 组件底部到 Footer 底部之间的行数为保留行数。
      int inputRow = layout.inputRow();
      int linesBelow = layout.footerRow() + layout.footer().height() - inputRow - 1;
      reader.setVariable(LineReader.LINE_OFFSET, Math.max(1, linesBelow));

      // 终端 I/O 同步点：getHeight() 使终端完成处理所有先前输出的缓冲区。
      terminal.getHeight();

      // 光标定位：使用 raw ANSI 直接写入 terminal.writer()，
      // 避免 terminal.puts() 可能产生的 JLine 内部状态干扰。
      inputActive.set(true);

      synchronized (terminalLock) {
        terminal.writer().write(String.format(ANSI_CURSOR_LINE, inputRow + 1));
        terminal.writer().write("\033[2K");
        terminal.writer().flush();
      }

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
      } catch (Exception e) {
        inputActive.set(false);
        if (!running.get()) break;
        logger.warn("Unexpected error in input loop, retrying", e);
        continue;
      } finally {
        inputActive.set(false);
      }

      // 处理输入期间积压的 deferred 重绘
      if (pendingRedraw.compareAndSet(true, false)) {
        contentDirty.set(true);
        redrawScrollArea();
        // Same rationale: leave contentDirty for renderLoop's atomic CAS.
      }

      // JLine 补全菜单可能覆盖分割线和状态行，readLine 返回后显式恢复
      restoreLowerArea();

      if (line == null) break;

      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;

      inputHistory.addLast(trimmed);
      historyIndex = inputHistory.size();
      layout.input().setText(trimmed);

      SlashCommand slashCmd = SlashCommand.parse(trimmed);
      if (slashCmd != null) {
        boolean handled = commandHandler.execute(slashCmd);
        if (handled) {
          contentDirty.set(true);
          continue;
        }
      }

      if (state.isRunning()) {
        // Agent 忙碌时入队，不阻塞用户输入
        inputQueue.addLast(trimmed);
        layout.setQueueCount(inputQueue.size());
        contentDirty.set(true);
        continue;
      }

      layout.inputBlock().showUserInput(trimmed);
      state.transitionTo(TuiState.State.RUNNING);
      contentDirty.set(true);

      if (onTaskSubmit != null) onTaskSubmit.accept(trimmed);
      eventBridge.onStartThinking(trimmed);
    }
  }

  private void restoreLowerArea() {
    synchronized (terminalLock) {
      java.io.Writer writer = terminal.writer();
      try {
        writeRow(writer, layout.separatorRow(), layout.separatorLine());
        writeRow(writer, layout.queueRow(), layout.queueLine());
        writeRow(writer, layout.separator2Row(), layout.separatorLine());
        layout.footer().renderTo(writer);
        writer.flush();
      } catch (IOException e) {
        logger.warn("Failed to restore lower area after JLine completion menu", e);
      }
    }
  }

  /** 从队列中取出下一个待处理输入并提交（由 TaskComplete/TaskError 回调调用）。 */
  private void dispatchNextFromQueue() {
    if (inputQueue.isEmpty()) return;
    String next = inputQueue.pollFirst();
    layout.setQueueCount(inputQueue.size());
    if (next != null && !next.isBlank()) {
      layout.inputBlock().showUserInput(next);
      state.transitionTo(TuiState.State.RUNNING);
      contentDirty.set(true);
      if (onTaskSubmit != null) onTaskSubmit.accept(next);
      eventBridge.onStartThinking(next);
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
