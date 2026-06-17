package org.cland.alice.facade.tui.layout;

import java.util.List;
import org.cland.alice.facade.tui.component.*;

/**
 * TUI 三层单线分割布局管理器。
 *
 * <p>对应 docs/alice-facade-tui/Layout.md §7.1 沉浸式三看板常态布局（TAO Standard Mode）。
 *
 * <p>布局结构（v2.0 终极版）：
 *
 * <pre>
 *  \uD83E\uDD16 alice-agent v0.1.0 \u2500\u2500\u2500\u2500\u2500 [Session: xxx]          ← Header (1行，自带末端分隔线)
 *  [T Thought]: ...                                                                  ← 上方滚动区（可变高度）
 *  [A Action ]: ...
 *  [O Observe]: ...
 * \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500   ← 上分割线 (1行)
 *  > /_                                                                              ← 居中输入区 (1行)
 * \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500   ← 下分割线 (1行)
 *  \u001B[38;5;214m\uD83D\uDCB0 $0.041\u001B[0m \u001B[38;5;242m\u2502\u001B[0m ...       ← 底部状态栏 (1行，ANSI 256 色)
 * </pre>
 *
 * <p>分区规则（v2.0）：
 *
 * <ol>
 *   <li>上方滚动区：Header 行之后（Header 自带暗色分隔线），业务日志向上滚动
 *   <li>中间输入区：被两条独立分割线包裹
 *   <li>底部状态栏：ANSI 彩色渲染，全程物理固定
 * </ol>
 *
 * <p>固定非内容行数 = Header(1) + 上分割线(1) + Input(1) + 下分割线(1) + Status(1) = 5
 */
public class TuiLayout {

  /** 各行高度常量（单位：行） */
  public static final int HEADER_HEIGHT = 1;

  public static final int SEPARATOR_HEIGHT = 1;
  public static final int INPUT_HEIGHT = 1;
  public static final int STATUS_HEIGHT = 1;

  /** 固定非内容行数 = Header + 上分割线 + Input + 下分割线 + Status = 5 */
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
  private int separator1Row; // content 和 input 之间的分割线
  private int inputRow;
  private int separator2Row; // input 和 footer 之间的分割线
  private int footerRow;

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

    // 1. Header: row 0 (自带暗色分隔线，不占用额外行)
    header.setBounds(currentRow, 0, this.terminalWidth, HEADER_HEIGHT);
    currentRow += HEADER_HEIGHT;

    // 2. 上方滚动区: 直接从 Header 下一行开始
    contentStartRow = currentRow;
    contentHeight = this.terminalHeight - FIXED_ROWS;
    thought.setBounds(contentStartRow, 0, this.terminalWidth, contentHeight);
    currentRow = contentStartRow + contentHeight;

    // 3. 上分割线 (content 和 input 之间)
    separator1Row = currentRow;
    currentRow += SEPARATOR_HEIGHT;

    // 4. 输入区
    inputRow = currentRow;
    input.setBounds(inputRow, 0, this.terminalWidth, INPUT_HEIGHT);
    currentRow += INPUT_HEIGHT;

    // 5. 下分割线 (input 和 footer 之间)
    separator2Row = currentRow;
    currentRow += SEPARATOR_HEIGHT;

    // 6. 底部状态栏
    footerRow = currentRow;
    footer.setBounds(footerRow, 0, this.terminalWidth, STATUS_HEIGHT);

    // 标记所有组件为脏
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

  /** 获取输入区行号 */
  public int inputRow() {
    return inputRow;
  }

  /** 获取 content 下方分割线行号 */
  public int separator1Row() {
    return separator1Row;
  }

  /** 获取 input 下方分割线行号 */
  public int separator2Row() {
    return separator2Row;
  }

  /** 获取底部状态栏行号 */
  public int footerRow() {
    return footerRow;
  }

  /** 获取底部状态栏最后一个行号 */
  public int lastRow() {
    return footerRow + STATUS_HEIGHT - 1;
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
