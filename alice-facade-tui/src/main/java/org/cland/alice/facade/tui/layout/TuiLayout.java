package org.cland.alice.facade.tui.layout;

import java.util.List;
import org.cland.alice.facade.tui.component.*;

/**
 * TUI 布局管理器 — TAO 四段式布局 (v3.1)。
 *
 * <p>对应 Layout_TAO.md 三段式原型扩展为四段式：输入区 / 思考区 / 动作区 / 观察区。
 *
 * <p>布局结构（v3.1）:
 *
 * <pre>
 *  🤖 alice-agent v0.60.0 ──────────────────────────────  ← Header (1行, row 0)
 *  ✓ New session started                                  ← InputBlock (2行)
 *  together debug current program...
 *  THOUGHT  监测到悬空状态。                               ← ThinkBlock (~45% 内容区)
 *  $ ls -la (timeout 120s)                                ← ActionBlock (2行, 同InputBlock风格)
 *  -rw------- 1 alice alice 111 config.json               ← ObserveBlock (~55% 内容区)
 *  Took 0.0s
 * ──────────────────────────────────────────────────────  ← 分割线 (1行)
 *  📋 2 queued messages                                    ← 队列状态行 (1行, 有消息时显示)
 *  █                                                     ← 输入区 (1行)
 *  [48;5;208m💰 $0.041[0m  [48;5;35m📊 125 t/s[0m ...  ← Footer (1行, 终端最底行)
 * </pre>
 *
 * <p>固定非内容行数 = Header(1) + InputBlock(2) + ActionBlock(2) + Footer(1) = 6
 */
public class TuiLayout {

  /** 各行高度常量（单位：行） */
  public static final int HEADER_HEIGHT = 1;

  public static final int INPUT_BLOCK_HEIGHT = 2;
  public static final int ACTION_BLOCK_HEIGHT = 2;
  public static final int STATUS_HEIGHT = 1;

  /** 固定非内容行数 */
  public static final int FIXED_ROWS =
      HEADER_HEIGHT + INPUT_BLOCK_HEIGHT + ACTION_BLOCK_HEIGHT + STATUS_HEIGHT;

  /** 队列状态行高度 */
  public static final int QUEUE_HEIGHT = 1;

  /** 分割线字符 (ANSI 暗色) */
  static final char SEPARATOR_CHAR = '\u2500'; // ─

  static final String ANSI_DIM_SEP = "\u001B[38;5;242m";
  static final String ANSI_RESET = "\u001B[0m";

  private final HeaderComponent header;
  private final InputBlockComponent inputBlock;
  private final ThinkBlockComponent thinkBlock;
  private final ActionBlockComponent actionBlock;
  private final ObserveBlockComponent observeBlock;
  private final InputComponent input;
  private final FooterComponent footer;

  /** 当前终端尺寸 */
  private int terminalWidth;

  private int terminalHeight;

  /** 各区域的起始行 */
  private int inputBlockStartRow;

  private int inputBlockHeight;
  private int thinkBlockStartRow;
  private int thinkBlockHeight;
  private int actionBlockStartRow;
  private int actionBlockHeight;
  private int observeBlockStartRow;
  private int observeBlockHeight;
  private int inputRow;
  private int separatorRow;
  private int queueRow;
  private int footerRow;

  /** 当前队列中待处理的消息数量 */
  private volatile int queueCount;

  public TuiLayout(
      HeaderComponent header,
      InputBlockComponent inputBlock,
      ThinkBlockComponent thinkBlock,
      ActionBlockComponent actionBlock,
      ObserveBlockComponent observeBlock,
      InputComponent input,
      FooterComponent footer) {
    this.header = header;
    this.inputBlock = inputBlock;
    this.thinkBlock = thinkBlock;
    this.actionBlock = actionBlock;
    this.observeBlock = observeBlock;
    this.input = input;
    this.footer = footer;
  }

  /** 根据当前终端尺寸重新计算所有组件位置。通常在终端 resize 时调用。 */
  public void recalculate(int terminalWidth, int terminalHeight) {
    this.terminalWidth = Math.max(terminalWidth, 40);
    this.terminalHeight = Math.max(terminalHeight, FIXED_ROWS + 6);

    // 布局计算（从顶到底, TAO 四段式）
    int currentRow = 0;

    // 1. Header: row 0
    header.setBounds(currentRow, 0, this.terminalWidth, HEADER_HEIGHT);
    currentRow += HEADER_HEIGHT;

    // 2. InputBlock (TAO 顶部块): 固定 2 行
    inputBlockStartRow = currentRow;
    inputBlockHeight = INPUT_BLOCK_HEIGHT;
    inputBlock.setBounds(inputBlockStartRow, 0, this.terminalWidth, inputBlockHeight);
    currentRow += inputBlockHeight;

    // 3. ThinkBlock (TAO 思考块): ~45% 内容高度
    int remainingBeforeAction =
        this.terminalHeight
            - currentRow
            - ACTION_BLOCK_HEIGHT // ActionBlock (2行)
            - 1 // 分割线
            - QUEUE_HEIGHT // 队列状态行 (1行)
            - 1 // 输入行
            - STATUS_HEIGHT; // Footer
    thinkBlockHeight = (int) Math.floor(remainingBeforeAction * 0.45);
    thinkBlockStartRow = currentRow;
    int oldThinkHeight = thinkBlock.height();
    thinkBlock.setBounds(thinkBlockStartRow, 0, this.terminalWidth, thinkBlockHeight);
    thinkBlock.onResize(oldThinkHeight);
    currentRow = thinkBlockStartRow + thinkBlockHeight;

    // 4. ActionBlock (TAO 动作块): 固定 2 行, 与 InputBlock 同风格
    actionBlockStartRow = currentRow;
    actionBlockHeight = ACTION_BLOCK_HEIGHT;
    actionBlock.setBounds(actionBlockStartRow, 0, this.terminalWidth, actionBlockHeight);
    currentRow += actionBlockHeight;

    // 5. ObserveBlock (TAO 观察块): 剩余内容高度
    int remainingAfterAction =
        this.terminalHeight
            - currentRow
            - 1 // 分割线
            - QUEUE_HEIGHT // 队列状态行 (1行)
            - 1 // 输入行
            - STATUS_HEIGHT; // Footer
    observeBlockHeight = Math.max(remainingAfterAction, 1);
    observeBlockStartRow = currentRow;
    int oldObserveHeight = observeBlock.height();
    observeBlock.setBounds(observeBlockStartRow, 0, this.terminalWidth, observeBlockHeight);
    observeBlock.onResize(oldObserveHeight);
    currentRow = observeBlockStartRow + observeBlockHeight;

    // 6. 分割线 (内容区和输入区之间)
    separatorRow = currentRow;

    // 7. 队列状态行 (有消息时显示, 无消息时留空)
    queueRow = separatorRow + 1;

    // 8. 输入区 (1行)
    inputRow = queueRow + 1;
    input.setBounds(inputRow, 0, this.terminalWidth, 1);

    // 9. Footer (终端最底行)
    footerRow = this.terminalHeight - 1;
    footer.setBounds(footerRow, 0, this.terminalWidth, STATUS_HEIGHT);

    markAllDirty();
  }

  // ========== 布局信息查询 ==========

  public int inputBlockStartRow() {
    return inputBlockStartRow;
  }

  public int inputBlockHeight() {
    return inputBlockHeight;
  }

  public int thinkBlockStartRow() {
    return thinkBlockStartRow;
  }

  public int thinkBlockHeight() {
    return thinkBlockHeight;
  }

  public int actionBlockStartRow() {
    return actionBlockStartRow;
  }

  public int actionBlockHeight() {
    return actionBlockHeight;
  }

  public int observeBlockStartRow() {
    return observeBlockStartRow;
  }

  public int observeBlockHeight() {
    return observeBlockHeight;
  }

  public int separatorRow() {
    return separatorRow;
  }

  /** 队列状态行（位于分割线与输入区之间）。满行显示 "📋 N queued messages"，空时显示空行。 */
  public int queueRow() {
    return queueRow;
  }

  /** 更新队列中待处理消息数量（由 ScreenManager 在入队/出队时调用）。 */
  public void setQueueCount(int count) {
    this.queueCount = Math.max(count, 0);
  }

  /** 队列中待处理消息数量。 */
  public int queueCount() {
    return queueCount;
  }

  /** 生成队列状态行文本（无消息时返回空字符串）。 */
  public String queueLine() {
    if (queueCount <= 0) return "";
    return ANSI_DIM_SEP + "\uD83D\uDCCB " + queueCount + " queued messages" + ANSI_RESET;
  }

  public int inputRow() {
    return inputRow;
  }

  public int footerRow() {
    return footerRow;
  }

  public int lastRow() {
    return footerRow;
  }

  public String separatorLine() {
    StringBuilder sb = new StringBuilder(terminalWidth + 16);
    sb.append(ANSI_DIM_SEP);
    for (int i = 0; i < terminalWidth; i++) {
      sb.append(SEPARATOR_CHAR);
    }
    sb.append(ANSI_RESET);
    return sb.toString();
  }

  public void markAllDirty() {
    header.markDirty();
    inputBlock.markDirty();
    thinkBlock.markDirty();
    actionBlock.markDirty();
    observeBlock.markDirty();
    input.markDirty();
    footer.markDirty();
  }

  public List<Component> getComponents() {
    return List.of(header, inputBlock, thinkBlock, actionBlock, observeBlock, input, footer);
  }

  // ========== Getters ==========

  public HeaderComponent header() {
    return header;
  }

  public InputBlockComponent inputBlock() {
    return inputBlock;
  }

  public ThinkBlockComponent thinkBlock() {
    return thinkBlock;
  }

  public ActionBlockComponent actionBlock() {
    return actionBlock;
  }

  public ObserveBlockComponent observeBlock() {
    return observeBlock;
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
