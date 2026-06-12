package org.cland.alice.facade.tui.layout;

import java.util.List;
import org.cland.alice.facade.tui.component.*;

/**
 * TUI 三层单线分割布局管理器。
 *
 * <p>对应 docs/alice-facade-tui/Layout.md §7.1 沉浸式三看板常态布局（TAO Standard Mode）。
 *
 * <p>通过两条统一单线 `────────────────────────────────────────` 将终端垂直划分为三大固定区域：
 *
 * <pre>
 *  alice v0.1.0                                                                      ← Header (1行)
 * ────────────────────────────────────────────────────────────────────────────────   ← 上分割线 (1行)
 *  [T Thought]: ...                                                                   ← 上方滚动区（可变高度）
 *  [A Action ]: ...
 *  [O Observe]: ...
 *                                                                                     ← 空行
 * ────────────────────────────────────────────────────────────────────────────────   ← 下分割线 (1行)
 *  > /_                                                                              ← 居中输入区域 (1行)
 * ────────────────────────────────────────────────────────────────────────────────   ← 下分割线 (1行)
 *  💰 Cost: $0.041 | 📊 Speed: 125 t/s | 🧠 Model: ... | 🔌 Active Tool: ...        ← 底部状态栏 (1行)
 * </pre>
 *
 * <p>分区规则：
 *
 * <ol>
 *   <li>上方滚动区：业务日志、思考/动作/观测流输出，内容正常向上滚动
 *   <li>中间输入区：被两条分割线包裹
 *   <li>底部状态栏：计费、速率、模型、工具等核心指标，全程固定在页面最底端
 * </ol>
 */
public class TuiLayout {

  /** 各行高度常量（单位：行） */
  public static final int HEADER_HEIGHT = 1;

  public static final int SEPARATOR_HEIGHT = 1;
  public static final int INPUT_HEIGHT = 1;
  public static final int STATUS_HEIGHT = 1;

  /** 固定非内容行数 = Header + 上分割线 + 下分割线 + Input + 下分割线 + Status = 6 */
  public static final int FIXED_ROWS =
      HEADER_HEIGHT + SEPARATOR_HEIGHT + SEPARATOR_HEIGHT + INPUT_HEIGHT + STATUS_HEIGHT;

  /** 分割线字符 */
  static final char SEPARATOR_CHAR = '\u2500'; // ─

  private final HeaderComponent header;
  private final ThoughtComponent thought;
  private final InputComponent input;
  private final FooterComponent footer;

  /** 当前终端尺寸 */
  private int terminalWidth;

  private int terminalHeight;

  /** 各区域的起始行 */
  private int separator1Row;

  private int contentStartRow;
  private int contentHeight;
  private int separator2Row;
  private int inputRow;
  private int separator3Row;
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

    // 1. Header: row 0
    header.setBounds(currentRow, 0, this.terminalWidth, HEADER_HEIGHT);
    currentRow += HEADER_HEIGHT;

    // 2. 上分割线: row 1
    separator1Row = currentRow;
    currentRow += SEPARATOR_HEIGHT;

    // 3. 上方滚动区: 剩余高度减去固定底部区域
    contentStartRow = currentRow;
    contentHeight = this.terminalHeight - FIXED_ROWS;

    thought.setBounds(contentStartRow, 0, this.terminalWidth, contentHeight);
    currentRow = contentStartRow + contentHeight;

    // 4. 下分割线 (input上方)
    separator2Row = currentRow;
    currentRow += SEPARATOR_HEIGHT;

    // 5. 输入区
    inputRow = currentRow;
    input.setBounds(inputRow, 0, this.terminalWidth, INPUT_HEIGHT);
    currentRow += INPUT_HEIGHT;

    // 6. 下分割线 (input下方)
    separator3Row = currentRow;
    currentRow += SEPARATOR_HEIGHT;

    // 7. 底部状态栏
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

  /** 获取上分割线行号 */
  public int separator1Row() {
    return separator1Row;
  }

  /** 获取 input 上方分割线行号 */
  public int separator2Row() {
    return separator2Row;
  }

  /** 获取 input 下方分割线行号 */
  public int separator3Row() {
    return separator3Row;
  }

  /** 获取底部状态栏行号 */
  public int footerRow() {
    return footerRow;
  }

  /** 获取底部状态栏最后一个行号 */
  public int lastRow() {
    return footerRow + STATUS_HEIGHT - 1;
  }

  /** 生成分割线字符串 */
  public String separatorLine() {
    StringBuilder sb = new StringBuilder(terminalWidth);
    for (int i = 0; i < terminalWidth; i++) {
      sb.append(SEPARATOR_CHAR);
    }
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
