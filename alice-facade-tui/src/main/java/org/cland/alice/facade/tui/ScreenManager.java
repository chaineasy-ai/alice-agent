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
 * ScreenManager：基于 JLine 3 的 TUI 核心管理器 — 三区对齐布局 (v4.0)。
 *
 * <p>将终端窗口划分为三个清晰对齐的区域：
 *
 * <ul>
 *   <li><b>Main Area</b> — Header + MessageArea (unified scrollable message stream)
 *   <li><b>Input Area</b> — 分割线 + 队列状态 + 输入行
 *   <li><b>Footer</b> — 费用 + 模型 + 工具信息
 * </ul>
 *
 * <p>参考 docs/alice-facade-tui/Layout.md (v4.0 三区对齐)
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

    // 初始化三区对齐组件
    HeaderComponent header = new HeaderComponent();
    MessageAreaComponent messageArea = new MessageAreaComponent();
    LineComponent separator = new LineComponent();
    LineComponent separator2 = new LineComponent();
    InputComponent input = new InputComponent();
    FooterComponent footer = new FooterComponent();

    this.layout = new TuiLayout(header, messageArea, separator, separator2, input, footer);
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
        "[ScreenManager] 3-zone layout: messageArea=[{}-{}] sep={} input={} footer={}",
        layout.messageAreaStartRow(),
        layout.messageAreaStartRow() + layout.messageAreaHeight() - 1,
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

  // ========== 事件路由 (v4.0 三区对齐) ==========

  /**
   * 三区对齐事件路由：
   *
   * <ul>
   *   <li>所有消息类事件 → MessageArea (Main Area)
   *   <li>队列/状态 → layout queueRow
   *   <li>Footer 更新 → FooterComponent
   * </ul>
   */
  private void setupEventListeners() {
    eventBridge.addListener(
        event -> {
          switch (event) {
            case TuiEvent.StartThinking e -> {
              contentDirty.set(true);
            }
            case TuiEvent.NewThought e -> {
              layout.messageArea().addThought(e.thought(), e.step(), e.traceId());
              contentDirty.set(true);
            }
            case TuiEvent.ActionExecuting e -> {
              String desc =
                  e.action().type().name()
                      + (e.action().target() != null ? " (" + e.action().target() + ")" : "");
              layout.messageArea().addActionLine(desc, e.traceId());
              contentDirty.set(true);
            }
            case TuiEvent.ObservationResult e -> {
              layout.messageArea().addObservationLine(e.summary(), e.elapsedSec());
              contentDirty.set(true);
            }
            case TuiEvent.ChatMessage e -> {
              if ("User".equalsIgnoreCase(e.sender())) {
                layout.messageArea().addUserMessage(e.content());
              } else if ("System".equalsIgnoreCase(e.sender())) {
                layout.messageArea().addSystemMessage(e.content());
              } else {
                layout.messageArea().addAgentMessage(e.content());
              }
              contentDirty.set(true);
            }
            case TuiEvent.TaskComplete e -> {
              String result = e.result();
              if (result != null && !result.isBlank()) {
                layout.messageArea().addAgentMessage(result);
              }
              state.transitionTo(TuiState.State.IDLE);
              dispatchNextFromQueue();
              contentDirty.set(true);
            }
            case TuiEvent.TaskError e -> {
              layout.messageArea().addSystemMessage("\u9519\u8BEF: " + e.errorMessage());
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
                  LineReader.LINE_OFFSET, Math.max(1, layout.footerRow() - layout.inputRow()));
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
              layout.messageArea().clear();
              state.transitionTo(TuiState.State.IDLE);
              contentDirty.set(true);
            })
        .onClear(
            () -> {
              layout.messageArea().clear();
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

  /** 全屏重绘 — 三区对齐：Main Area / Input Area / Footer。 */
  private void fullRedraw() {
    java.io.Writer writer = terminal.writer();
    try {
      // ── Main Area ──────────────────────────────────────────────
      layout.header().renderTo(writer);
      layout.messageArea().renderTo(writer);

      // ── Input Area ─────────────────────────────────────────────
      layout.separator().renderTo(writer);
      writeRow(writer, layout.queueRow(), layout.queueLine());
      layout.input().renderTo(writer);

      // ── separator2 + Footer ────────────────────────────────────
      layout.separator2().renderTo(writer);
      layout.footer().renderTo(writer);

      // Restore cursor to input row
      writer.write(String.format(ANSI_CURSOR_LINE, layout.inputRow() + 1));
      writer.write("\033[2K");
      writer.flush();
    } catch (IOException e) {
      logger.error("Full redraw failed", e);
    }
  }

  /**
   * 重绘所有静态区域 — 三区对齐。
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

        // ── Main Area ────────────────────────────────────────────
        layout.header().renderTo(writer);
        layout.messageArea().renderTo(writer);

        // ── Input Area (separator + queue + input) ───────────────
        layout.separator().renderTo(writer);
        writeRow(writer, layout.queueRow(), layout.queueLine());
        layout.input().renderTo(writer);

        // ── separator2 + Footer ───────────────────────────────────
        layout.separator2().renderTo(writer);
        layout.footer().renderTo(writer);

        // Restore cursor to input row
        writer.write(String.format(ANSI_CURSOR_LINE, layout.inputRow() + 1));
        writer.write("\033[2K");
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
      if (contentDirty.get()) {
        redrawScrollArea();
      }

      // LINE_OFFSET：保留 Footer 行不被 JLine 覆盖
      int inputRow = layout.inputRow();
      int linesBelow = layout.footerRow() - inputRow;
      reader.setVariable(LineReader.LINE_OFFSET, Math.max(1, linesBelow));

      terminal.getHeight();

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

      if (pendingRedraw.compareAndSet(true, false)) {
        contentDirty.set(true);
        redrawScrollArea();
      }

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
        inputQueue.addLast(trimmed);
        layout.setQueueCount(inputQueue.size());
        contentDirty.set(true);
        continue;
      }

      layout.messageArea().addUserMessage(trimmed);
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
        layout.separator().renderTo(writer);
        writeRow(writer, layout.queueRow(), layout.queueLine());
        layout.separator2().renderTo(writer);
        layout.footer().renderTo(writer);
        // JLine manages input row, just restore cursor
        writer.write(String.format(ANSI_CURSOR_LINE, layout.inputRow() + 1));
        writer.write("\033[2K");
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
      layout.messageArea().addUserMessage(next);
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
