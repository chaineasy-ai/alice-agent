package org.cland.alice.facade.tui.layout;

import java.util.List;
import org.cland.alice.facade.tui.component.*;

/**
 * TUI 布局管理器 — 动态增长布局 (v5.0)。
 *
 * <p>布局序列（从上到下）：
 *
 * <pre>
 *  Header          (固定 1 行)
 *  Main Area       [0..N] 动态 — 行数等于实际内容行数
 *  QueueMsg        [0..1] 仅队列非空时显示
 *  Line1           分割线
 *  Input           (1 行) — 警告光标
 *  Line2           分割线
 *  Footer          (固定 1 行)
 * </pre>
 *
 * <p>Main Area 高度由 {@link MessageAreaComponent#contentLineCount()} 动态决定， 随内容增长自动增加，内容超出终端可用空间时自动滚动。
 */
public class TuiLayout {

  /** 各行高度常量（单位：行） */
  public static final int HEADER_HEIGHT = 1;

  public static final int SEPARATOR_HEIGHT = 1;
  public static final int QUEUE_HEIGHT = 1;
  public static final int INPUT_HEIGHT = 1;
  public static final int FOOTER_HEIGHT = 1;

  /**
   * 固定非内容行数（旧版兼容常量）。布局已改为动态计算，保留此常量供测试使用。 布局序列：Header(1) + Main Area [0..N] + QueueMsg [0..1] +
   * Line1(1) + Input(1) + Line2(1) + Footer(1)
   */
  public static final int FIXED_ROWS = 6;

  /** 分割线 ANSI 暗色 */
  static final String ANSI_DIM_SEP = "\u001B[38;5;242m";

  static final String ANSI_RESET = "\u001B[0m";

  private final HeaderComponent header;
  private final MessageAreaComponent messageArea;
  private final LineComponent separator;
  private final LineComponent separator2;
  private final InputComponent input;
  private final FooterComponent footer;

  /** 当前终端尺寸 */
  private int terminalWidth;

  private int terminalHeight;

  /** 各区域的起始行 */
  private int messageAreaStartRow;

  private int messageAreaHeight;
  private int separatorRow;
  private int queueRow;
  private int inputRow;
  private int separator2Row;
  private int footerRow;

  /** 当前队列中待处理的消息数量 */
  private volatile int queueCount;

  public TuiLayout(
      HeaderComponent header,
      MessageAreaComponent messageArea,
      LineComponent separator,
      LineComponent separator2,
      InputComponent input,
      FooterComponent footer) {
    this.header = header;
    this.messageArea = messageArea;
    this.separator = separator;
    this.separator2 = separator2;
    this.input = input;
    this.footer = footer;
  }

  /**
   * 根据当前终端尺寸 + 内容行数重新计算所有组件位置。
   *
   * <p>布局序列：Header -> Main Area [0..N] -> QueueMsg [0..1] -> Line1 -> Input -> Line2 -> Footer。
   * 调用时机：终端 resize 或内容变化后。
   *
   * @param terminalWidth 终端宽度
   * @param terminalHeight 终端高度
   * @param contentLines 当前消息区实际内容行数
   */
  public void recalculate(int terminalWidth, int terminalHeight, int contentLines) {
    this.terminalWidth = Math.max(terminalWidth, 40);
    this.terminalHeight = Math.max(terminalHeight, FIXED_ROWS + 5);

    // ── Header (固定 1 行，始终在最顶部) ─────────────────────────
    int currentRow = 0;
    header.setBounds(currentRow, 0, this.terminalWidth, HEADER_HEIGHT);
    currentRow += HEADER_HEIGHT;

    // ── Main Area [0..N] ─────────────────────────────────────────
    // Main Area 填满 Header 与 Queue 之间的可用空间。
    // 保留 Queue + Line1 + Input + Line2 + Footer 的空间（5 行）。
    // 内容超出时自动滚动（scrollToBottom），最新消息始终可见。
    int fixedBelow =
        QUEUE_HEIGHT + SEPARATOR_HEIGHT + INPUT_HEIGHT + SEPARATOR_HEIGHT + FOOTER_HEIGHT;
    int maxAvailable = this.terminalHeight - currentRow - fixedBelow;
    // Main Area 填满 Header 与 Queue 之间的全部可用空间
    int mainHeight = Math.max(maxAvailable, 1);

    messageAreaHeight = mainHeight;
    messageAreaStartRow = currentRow;
    int oldMsgHeight = messageArea.height();
    messageArea.setBounds(messageAreaStartRow, 0, this.terminalWidth, messageAreaHeight);
    messageArea.onResize(oldMsgHeight);
    currentRow = messageAreaStartRow + messageAreaHeight;

    // ── QueueMsg [0..1] ─────────────────────────────────────────
    // 始终预留队列行位置（ScreenManager 根据 queueCount 决定是否渲染）
    queueRow = currentRow;
    currentRow += QUEUE_HEIGHT;

    // ── Line1 ────────────────────────────────────────────────────
    separatorRow = currentRow;
    separator.setBounds(separatorRow, 0, this.terminalWidth, SEPARATOR_HEIGHT);
    currentRow += SEPARATOR_HEIGHT;

    // ── Input ────────────────────────────────────────────────────
    inputRow = currentRow;
    input.setBounds(inputRow, 0, this.terminalWidth, INPUT_HEIGHT);
    currentRow += INPUT_HEIGHT;

    // ── Line2 ────────────────────────────────────────────────────
    separator2Row = currentRow;
    separator2.setBounds(separator2Row, 0, this.terminalWidth, SEPARATOR_HEIGHT);
    separator2.setVisible(true);
    currentRow += SEPARATOR_HEIGHT;

    // ── Footer ───────────────────────────────────────────────────
    footerRow = currentRow;
    footer.setBounds(footerRow, 0, this.terminalWidth, FOOTER_HEIGHT);

    // 确保各组件可见
    separator.setVisible(true);
    input.setVisible(true);
    footer.setVisible(true);

    markAllDirty();
  }

  /**
   * 旧版 2 参数兼容方法。使用当前内容行数进行布局。
   *
   * @deprecated 请使用 {@link #recalculate(int, int, int)} 或 {@link #relayout()}
   */
  @Deprecated
  public void recalculate(int terminalWidth, int terminalHeight) {
    recalculate(terminalWidth, terminalHeight, messageArea.contentLineCount());
  }

  /**
   * 内容变化后重新布局。根据当前内容行数和队列状态动态调整 Main Area 高度。
   *
   * <p>等价于以当前终端尺寸调用 {@link #recalculate(int, int, int)}。
   */
  public void relayout() {
    recalculate(terminalWidth, terminalHeight, messageArea.contentLineCount());
  }

  // ========== 布局信息查询 ==========

  public int messageAreaStartRow() {
    return messageAreaStartRow;
  }

  public int messageAreaHeight() {
    return messageAreaHeight;
  }

  public int separatorRow() {
    return separatorRow;
  }

  public int queueRow() {
    return queueRow;
  }

  public int inputRow() {
    return inputRow;
  }

  public int separator2Row() {
    return separator2Row;
  }

  public int footerRow() {
    return footerRow;
  }

  public int lastRow() {
    return footerRow;
  }

  public void setQueueCount(int count) {
    this.queueCount = Math.max(count, 0);
  }

  public int queueCount() {
    return queueCount;
  }

  public String queueLine() {
    if (queueCount <= 0) return "";
    return ANSI_DIM_SEP + "\uD83D\uDCCB " + queueCount + " queued messages" + ANSI_RESET;
  }

  public void markAllDirty() {
    header.markDirty();
    messageArea.markDirty();
    separator.markDirty();
    separator2.markDirty();
    input.markDirty();
    footer.markDirty();
  }

  public List<Component> getComponents() {
    return List.of(header, messageArea, separator, separator2, input, footer);
  }

  // ========== Getters ==========

  public HeaderComponent header() {
    return header;
  }

  public MessageAreaComponent messageArea() {
    return messageArea;
  }

  public LineComponent separator() {
    return separator;
  }

  public LineComponent separator2() {
    return separator2;
  }

  public InputComponent input() {
    return input;
  }

  public FooterComponent footer() {
    return footer;
  }

  public int terminalWidth() {
    return terminalWidth;
  }

  public int terminalHeight() {
    return terminalHeight;
  }
}
