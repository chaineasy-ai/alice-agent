package org.cland.alice.facade.tui.layout;

import java.util.List;
import org.cland.alice.facade.tui.component.*;

/**
 * TUI 布局管理器 (v2.6).
 *
 * <p>布局结构（v2.6）:
 *
 * <pre>
 *  🤖 alice-agent v0.60.0 ──────────────────────────────  ← Header (1行)
 *   THOUGHT  监测到 uncommitted 悬空状态。               ← 上方滚动区
 *   ACTION   调用本地 Bash 执行器。
 *   OBSERVE  BUILD SUCCESSFUL in 3s.
 * ──────────────────────────────────────────────────────  ← 分割线 (1行)
 *  █                                                     ← 输入区 (1行)
 * ──────────────────────────────────────────────────────  ← 分割线 (1行)
 *  [48;5;208m💰 $0.041[0m  [48;5;35m📊 125 t/s[0m ...  ← Footer (1行，终端最底行)
 * </pre>
 *
 * <p>固定非内容行数 = Header(1) + 分割线(1) + Input(1) + 分割线(1) + Footer(1) = 5
 */
public class TuiLayout {

  /** 各行高度常量（单位：行） */
  public static final int HEADER_HEIGHT = 1;

  public static final int SEPARATOR_HEIGHT = 1;
  public static final int INPUT_HEIGHT = 1;
  public static final int STATUS_HEIGHT = 1;

  /** 固定非内容行数 = Header + 分割线 + Input + 分割线 + Footer = 5 */
  public static final int FIXED_ROWS =
      HEADER_HEIGHT + SEPARATOR_HEIGHT + INPUT_HEIGHT + SEPARATOR_HEIGHT + STATUS_HEIGHT;

  /** 分割线字符 (ANSI 暗色) */
  static final char SEPARATOR_CHAR = '\u2500'; // ─

  static final String ANSI_DIM_SEP = "\u001B[38;5;242m";
  static final String ANSI_RESET = "\u001B[0m";

  private final HeaderComponent header;
  private final ThoughtComponent thought;
  private final InputComponent input;
  private final FooterComponent footer;

  /** 当前终端尺寸 */
  private int terminalWidth;

  private int terminalHeight;

  /** 各区域的起始行 */
  private int contentStartRow;

  private int contentHeight;
  private int separatorRow; // 滚动区和输入区之间的分割线
  private int inputRow; // 输入区行
  private int separator2Row; // 输入区和 footer 之间的分割线
  private int footerRow; // 底部状态栏行（终端最底行）

  public TuiLayout(
      HeaderComponent header,
      ThoughtComponent thought,
      InputComponent input,
      FooterComponent footer) {
    this.header = header;
    this.thought = thought;
    this.input = input;
    this.footer = footer;
  }

  /** 根据当前终端尺寸重新计算所有组件位置。通常在终端 resize 时调用。 */
  public void recalculate(int terminalWidth, int terminalHeight) {
    this.terminalWidth = Math.max(terminalWidth, 40);
    this.terminalHeight = Math.max(terminalHeight, FIXED_ROWS + 3);

    // 布局计算（从顶到底）
    int currentRow = 0;

    // 1. Header: row 0
    header.setBounds(currentRow, 0, this.terminalWidth, HEADER_HEIGHT);
    currentRow += HEADER_HEIGHT;

    // 2. 上方滚动区: 直接从 Header 下一行开始
    contentStartRow = currentRow;
    int oldContentHeight = contentHeight;
    contentHeight = this.terminalHeight - FIXED_ROWS;
    thought.setBounds(contentStartRow, 0, this.terminalWidth, contentHeight);
    thought.onResize(oldContentHeight);
    currentRow = contentStartRow + contentHeight;

    // 3. 分割线 (滚动区和输入区之间)
    separatorRow = currentRow;
    currentRow += SEPARATOR_HEIGHT;

    // 4. 输入区
    inputRow = currentRow;
    input.setBounds(inputRow, 0, this.terminalWidth, INPUT_HEIGHT);
    currentRow += INPUT_HEIGHT;

    // 5. 分割线 (输入区和 footer 之间)
    separator2Row = currentRow;
    currentRow += SEPARATOR_HEIGHT;

    // 6. Footer (终端最底行)
    footerRow = currentRow;
    footer.setBounds(footerRow, 0, this.terminalWidth, STATUS_HEIGHT);

    markAllDirty();
  }

  // ========== 布局信息查询 ==========

  /** 获取上方滚动区起始行 */
  public int contentStartRow() {
    return contentStartRow;
  }

  /** 获取上方滚动区高度 */
  public int contentHeight() {
    return contentHeight;
  }

  /** 获取滚动区和输入区之间的分割线行号 */
  public int separatorRow() {
    return separatorRow;
  }

  /** 获取输入区和 footer 之间的分割线行号 */
  public int separator2Row() {
    return separator2Row;
  }

  /** 获取输入区行号 */
  public int inputRow() {
    return inputRow;
  }

  /** 获取底部状态栏行号（终端最底行） */
  public int footerRow() {
    return footerRow;
  }

  /** 获取终端最底行号 */
  public int lastRow() {
    return footerRow;
  }

  /**
   * 生成 ANSI 暗色分割线字符串。
   *
   * <p>使用 \u001B[38;5;242m（暗灰色）绘制，降低视觉噪音。
   */
  public String separatorLine() {
    StringBuilder sb = new StringBuilder(terminalWidth + 16);
    sb.append(ANSI_DIM_SEP);
    for (int i = 0; i < terminalWidth; i++) {
      sb.append(SEPARATOR_CHAR);
    }
    sb.append(ANSI_RESET);
    return sb.toString();
  }

  /** 标记所有组件为需要重绘 */
  public void markAllDirty() {
    header.markDirty();
    thought.markDirty();
    input.markDirty();
    footer.markDirty();
  }

  /** 获取所有需要绘制的可见组件 */
  public List<Component> getComponents() {
    return List.of(header, thought, input, footer);
  }

  // ========== Getters ==========

  public HeaderComponent header() {
    return header;
  }

  public ThoughtComponent thought() {
    return thought;
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
