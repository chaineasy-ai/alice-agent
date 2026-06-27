package org.cland.alice.facade.tui.layout;

import java.util.List;
import org.cland.alice.facade.tui.component.*;

/**
 * TUI 布局管理器 — 三区对齐布局 (v4.1)。
 *
 * <p>将终端窗口划分为三个清晰对齐的区域, 每个区域之间由 {@link LineComponent} 分隔:
 *
 * <pre>
 *  ┌─ Main Area ───────────────────────────────────────────┐
 *  │  🤖 alice-agent v0.60.0 ───────────────────────────── │  ← Header (1行, row 0)
 *  │                                                       │
 *  │  together debug current program...                    │
 *  │  ╸ Step 1 ╸                                          │
 *  │  analyzing the request...                             │
 *  │  ⮞ TOOL_CALL: execute (cmd=ls)                      │
 *  │  ⮞ -rw------- 1 alice alice 111 config.json         │
 *  │  ⮞ (Took 0.0s)                                       │
 *  ├────────────────────────────────────────────────────── │  ← LineComponent 1
 *  │  📋 2 queued messages                                 │  ← 队列状态 (1行)
 *  │  █                                                    │  ← InputComponent (1行)
 *  ├────────────────────────────────────────────────────── │  ← LineComponent 2
 *  │  [💰 $0.041]  [📊 125 t/s]  [🧠 gpt-4o] ── 🔌 none │  ← FooterComponent (1行)
 *  └───────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>组件共 6 个: Header, MessageArea, LineComponent×2, Input, Footer <br>
 * 固定非内容行数 = Header(1) + 分割线(1) + 队列(1) + 输入(1) + 分割线(1) + Footer(1) = 6
 */
public class TuiLayout {

  /** 各行高度常量（单位：行） */
  public static final int HEADER_HEIGHT = 1;

  public static final int SEPARATOR_HEIGHT = 1;
  public static final int QUEUE_HEIGHT = 1;
  public static final int INPUT_HEIGHT = 1;
  public static final int FOOTER_HEIGHT = 1;

  /** 固定非内容行数 */
  public static final int FIXED_ROWS =
      HEADER_HEIGHT
          + SEPARATOR_HEIGHT
          + QUEUE_HEIGHT
          + INPUT_HEIGHT
          + SEPARATOR_HEIGHT
          + FOOTER_HEIGHT;

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

  /** 根据当前终端尺寸重新计算所有组件位置。通常在终端 resize 时调用。 */
  public void recalculate(int terminalWidth, int terminalHeight) {
    this.terminalWidth = Math.max(terminalWidth, 40);
    this.terminalHeight = Math.max(terminalHeight, FIXED_ROWS + 5);

    // ── 三区对齐布局 (从顶到底) ──────────────────────────────────
    int currentRow = 0;

    // ── 1. Main Area ─────────────────────────────────────────────
    header.setBounds(currentRow, 0, this.terminalWidth, HEADER_HEIGHT);
    currentRow += HEADER_HEIGHT;

    int remainingForMessage =
        this.terminalHeight
            - HEADER_HEIGHT
            - SEPARATOR_HEIGHT // separator (Main → Input)
            - QUEUE_HEIGHT
            - INPUT_HEIGHT
            - SEPARATOR_HEIGHT // separator2 (Input → Footer)
            - FOOTER_HEIGHT;
    messageAreaHeight = Math.max(remainingForMessage, 1);
    messageAreaStartRow = currentRow;
    int oldMsgHeight = messageArea.height();
    messageArea.setBounds(messageAreaStartRow, 0, this.terminalWidth, messageAreaHeight);
    messageArea.onResize(oldMsgHeight);
    currentRow = messageAreaStartRow + messageAreaHeight;

    // ── 2. Input Area ───────────────────────────────────────────
    separatorRow = currentRow;
    separator.setBounds(separatorRow, 0, this.terminalWidth, SEPARATOR_HEIGHT);

    queueRow = separatorRow + 1;

    inputRow = queueRow + 1;
    input.setBounds(inputRow, 0, this.terminalWidth, INPUT_HEIGHT);

    // ── separator2: between Input Area and Footer ────────────────
    separator2Row = inputRow + 1;
    separator2.setBounds(separator2Row, 0, this.terminalWidth, SEPARATOR_HEIGHT);

    // ── 3. Footer ───────────────────────────────────────────────
    footerRow = separator2Row + 1;
    footer.setBounds(footerRow, 0, this.terminalWidth, FOOTER_HEIGHT);

    markAllDirty();
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
