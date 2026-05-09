package org.cland.alice.facade.tui.layout;

import java.util.List;
import org.cland.alice.facade.tui.component.*;

/**
 * TUI 布局管理器。
 *
 * <p>负责计算各组件在终端屏幕中的位置与大小，并在终端尺寸变化时重新布局。
 *
 * <p>布局结构：
 *
 * <pre>
 * ┌─ Alice Agent v1.0 ── Model: gpt-4o-mini ── Status: Idle ────────────┐  (Header, 1行)
 * │                                                                      │  (Header padding, 1行)
 * ├─ Chat History ────────────┬─ Thought Stream ────────────────────────┤  (Title divider, 1行)
 * │ User: hello               │ [1]> Analyzing...                       │  (Content, 可变)
 * │ Agent: Hi!                │ ⚡ Execute search                        │
 * ├───────────────────────────┴─────────────────────────────────────────┤  (Input divider, 1行)
 * │ > /exec ls -la                                                       │  (Input, 1行)
 * ├─────────────────────────────────────────────────────────────────────┤  (Footer divider, 1行)
 * │ F1:Help | F5:Stop | Tab:Focus | PgUp/PgDn | /help | Ctrl+Q:Quit     │  (Footer, 1行)
 * └─────────────────────────────────────────────────────────────────────┘  (Bottom border, 1行)
 * </pre>
 */
public class TuiLayout {

  /** 各行高度常量 */
  public static final int HEADER_HEIGHT = 1;

  public static final int HEADER_PADDING_HEIGHT = 1;
  public static final int TITLE_DIVIDER_HEIGHT = 1;
  public static final int INPUT_DIVIDER_HEIGHT = 1;
  public static final int INPUT_HEIGHT = 1;
  public static final int FOOTER_DIVIDER_HEIGHT = 1;
  public static final int FOOTER_HEIGHT = 1;
  public static final int BOTTOM_BORDER_HEIGHT = 1;

  public static final int MIN_CONTENT_HEIGHT = 3;

  private final HeaderComponent header;
  private final ChatComponent chat;
  private final ThoughtComponent thought;
  private final InputComponent input;
  private final FooterComponent footer;

  /** 边框字符 */
  static final char BOX_TOP_LEFT = '┌';

  static final char BOX_TOP_RIGHT = '┐';
  static final char BOX_BOTTOM_LEFT = '└';
  static final char BOX_BOTTOM_RIGHT = '┘';
  static final char BOX_HORIZONTAL = '─';
  static final char BOX_VERTICAL = '│';
  static final char BOX_TITLE_LEFT = '├';
  static final char BOX_TITLE_RIGHT = '┤';
  static final char BOX_TITLE_CROSS = '┼';
  static final char BOX_TITLE_DOWN = '┬';
  static final char BOX_TITLE_UP = '┴';

  /** 当前终端尺寸 */
  private int terminalWidth;

  private int terminalHeight;

  /** 各区域的起始行与高度 */
  private int contentStartRow;

  private int contentHeight;
  private int inputStartRow;
  private int footerStartRow;
  private int chatWidth;
  private int thoughtWidth;

  public TuiLayout(
      HeaderComponent header,
      ChatComponent chat,
      ThoughtComponent thought,
      InputComponent input,
      FooterComponent footer) {
    this.header = header;
    this.chat = chat;
    this.thought = thought;
    this.input = input;
    this.footer = footer;
  }

  /** 根据当前终端尺寸重新计算所有组件位置。 通常在终端 resize 时调用。 */
  public void recalculate(int terminalWidth, int terminalHeight) {
    // 确保最小尺寸
    this.terminalWidth = Math.max(terminalWidth, 60);
    this.terminalHeight =
        Math.max(
            terminalHeight,
            MIN_CONTENT_HEIGHT
                + HEADER_HEIGHT
                + HEADER_PADDING_HEIGHT
                + TITLE_DIVIDER_HEIGHT
                + INPUT_DIVIDER_HEIGHT
                + INPUT_HEIGHT
                + FOOTER_DIVIDER_HEIGHT
                + FOOTER_HEIGHT
                + BOTTOM_BORDER_HEIGHT);

    int fixedNonContent =
        HEADER_HEIGHT
            + HEADER_PADDING_HEIGHT
            + TITLE_DIVIDER_HEIGHT
            + INPUT_DIVIDER_HEIGHT
            + INPUT_HEIGHT
            + FOOTER_DIVIDER_HEIGHT
            + FOOTER_HEIGHT
            + BOTTOM_BORDER_HEIGHT;

    contentHeight = this.terminalHeight - fixedNonContent;

    // 计算各行起始位置
    int currentRow = 0;
    // Header: row 0
    header.setBounds(currentRow, 0, this.terminalWidth, HEADER_HEIGHT);

    // Header padding: invisible spacer row
    currentRow += HEADER_HEIGHT + HEADER_PADDING_HEIGHT;

    // Title divider row: ├─ Chat ──┬─ Thought ─┤
    contentStartRow = currentRow + TITLE_DIVIDER_HEIGHT;

    // 内容区域分为左右两列（50%/50%）
    chatWidth = this.terminalWidth / 2;
    thoughtWidth = this.terminalWidth - chatWidth;

    // Chat (左列) 和 Thought (右列)：从 contentStartRow 开始
    chat.setBounds(contentStartRow, 0, chatWidth, contentHeight);
    thought.setBounds(contentStartRow, chatWidth, thoughtWidth, contentHeight);

    // Input 分隔行
    inputStartRow = contentStartRow + contentHeight;
    currentRow = inputStartRow + INPUT_DIVIDER_HEIGHT;

    // Input 行
    input.setBounds(currentRow, 0, this.terminalWidth, INPUT_HEIGHT);

    // Footer 分隔行
    footerStartRow = currentRow + INPUT_HEIGHT;
    currentRow = footerStartRow + FOOTER_DIVIDER_HEIGHT;

    // Footer 行
    footer.setBounds(currentRow, 0, this.terminalWidth, FOOTER_HEIGHT);

    // 标记所有组件为脏
    markAllDirty();
  }

  // ========== 布局信息查询 ==========

  /** 获取内容区域起始行（边框之后的第一行） */
  public int contentStartRow() {
    return contentStartRow;
  }

  /** 获取内容区域高度 */
  public int contentHeight() {
    return contentHeight;
  }

  /** 获取输入区域起始行 */
  public int inputStartRow() {
    return inputStartRow;
  }

  /** 获取输入分隔行 */
  public int inputDividerRow() {
    return inputStartRow;
  }

  /** 获取 Footer 分隔行 */
  public int footerDividerRow() {
    return footerStartRow + INPUT_HEIGHT;
  }

  /** 获取最后一个绘制行 */
  public int lastRow() {
    return footerStartRow + FOOTER_DIVIDER_HEIGHT + FOOTER_HEIGHT + BOTTOM_BORDER_HEIGHT - 1;
  }

  /** 获取 Chat 面板宽度 */
  public int chatWidth() {
    return chatWidth;
  }

  /** 获取 Thought 面板宽度 */
  public int thoughtWidth() {
    return thoughtWidth;
  }

  /** 标记所有组件为需要重绘 */
  public void markAllDirty() {
    header.markDirty();
    chat.markDirty();
    thought.markDirty();
    input.markDirty();
    footer.markDirty();
  }

  /** 获取所有需要绘制的可见组件 */
  public List<Component> getComponents() {
    return List.of(header, chat, thought, input, footer);
  }

  // ========== Getters ==========

  public HeaderComponent header() {
    return header;
  }

  public ChatComponent chat() {
    return chat;
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
