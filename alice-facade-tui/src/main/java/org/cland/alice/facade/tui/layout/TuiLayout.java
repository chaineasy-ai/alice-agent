package org.cland.alice.facade.tui.layout;

import org.cland.alice.facade.tui.component.*;

import java.util.List;

/**
 * TUI 布局管理器，对应设计文档 §7.1 的布局设计。
 * <p>
 * 负责计算各组件在终端屏幕中的位置与大小，并在终端尺寸变化时重新布局。
 * <p>
 * 布局结构：
 * <pre>
 * +-----------------------------------------------------------+
 * | [Alice Agent v1.0] | Model: GPT-4o | Status: Thinking...  |  <- Header (3行)
 * +---------------------------+-------------------------------+
 * |                           | [Thought Stream]              |
 * | [Chat History]            | ...                           |
 * | ...                       |                               |
 * +---------------------------+-------------------------------+
 * | Iter: 5 | Tokens: 1024 | Status: Running                  |  <- Status (2行)
 * +-----------------------------------------------------------+
 * | [Input]: /exec ls -la_____________________________________|  <- Input (1行)
 * +-----------------------------------------------------------+
 * | F1:Help | F5:Stop | Tab:Focus | Ctrl+Q:Quit               |  <- Footer (1行)
 * +-----------------------------------------------------------+
 * </pre>
 */
public class TuiLayout {

    /** 各行高度常量 */
    public static final int HEADER_HEIGHT = 2;
    public static final int STATUS_HEIGHT = 2;
    public static final int INPUT_HEIGHT = 1;
    public static final int FOOTER_HEIGHT = 1;
    public static final int MIN_CONTENT_HEIGHT = 10;

    private final HeaderComponent header;
    private final ChatComponent chat;
    private final ThoughtComponent thought;
    private final StatusComponent status;
    private final InputComponent input;
    private final FooterComponent footer;

    /** 当前终端尺寸 */
    private int terminalWidth;
    private int terminalHeight;

    public TuiLayout(
            HeaderComponent header,
            ChatComponent chat,
            ThoughtComponent thought,
            StatusComponent status,
            InputComponent input,
            FooterComponent footer) {
        this.header = header;
        this.chat = chat;
        this.thought = thought;
        this.status = status;
        this.input = input;
        this.footer = footer;
    }

    /**
     * 根据当前终端尺寸重新计算所有组件位置。
     * 通常在终端 resize 时调用。
     */
    public void recalculate(int terminalWidth, int terminalHeight) {
        this.terminalWidth = Math.max(terminalWidth, 60);
        this.terminalHeight = Math.max(terminalHeight, MIN_CONTENT_HEIGHT
            + HEADER_HEIGHT + STATUS_HEIGHT + INPUT_HEIGHT + FOOTER_HEIGHT);

        int totalFixedHeight = HEADER_HEIGHT + STATUS_HEIGHT + INPUT_HEIGHT + FOOTER_HEIGHT;
        int contentHeight = this.terminalHeight - totalFixedHeight;

        // Header: row=0, col=0, width=terminalWidth, height=HEADER_HEIGHT
        header.setBounds(0, 0, this.terminalWidth, HEADER_HEIGHT);

        // 内容区域分为左右两列（默认 50%/50%）
        int chatWidth = this.terminalWidth / 2;
        int thoughtWidth = this.terminalWidth - chatWidth;

        // Chat (左列)
        int chatStartRow = HEADER_HEIGHT;
        chat.setBounds(chatStartRow, 0, chatWidth, contentHeight);

        // Thought Stream (右列)
        int thoughtStartRow = HEADER_HEIGHT;
        thought.setBounds(thoughtStartRow, chatWidth, thoughtWidth, contentHeight);

        // Status (状态栏)
        int statusStartRow = HEADER_HEIGHT + contentHeight;
        status.setBounds(statusStartRow, 0, this.terminalWidth, STATUS_HEIGHT);

        // Input (输入框)
        int inputStartRow = HEADER_HEIGHT + contentHeight + STATUS_HEIGHT;
        input.setBounds(inputStartRow, 0, this.terminalWidth, INPUT_HEIGHT);

        // Footer
        int footerStartRow = HEADER_HEIGHT + contentHeight + STATUS_HEIGHT + INPUT_HEIGHT;
        footer.setBounds(footerStartRow, 0, this.terminalWidth, FOOTER_HEIGHT);

        // 标记所有组件为脏
        markAllDirty();
    }

    /** 标记所有组件为需要重绘 */
    public void markAllDirty() {
        header.markDirty();
        chat.markDirty();
        thought.markDirty();
        status.markDirty();
        input.markDirty();
        footer.markDirty();
    }

    /** 获取所有需要绘制的可见组件 */
    public List<Component> getComponents() {
        return List.of(header, chat, thought, status, input, footer);
    }

    // ========== Getters ==========

    public HeaderComponent header()    { return header; }
    public ChatComponent chat()        { return chat; }
    public ThoughtComponent thought()  { return thought; }
    public StatusComponent status()    { return status; }
    public InputComponent input()      { return input; }
    public FooterComponent footer()    { return footer; }

    public int terminalWidth()         { return terminalWidth; }
    public int terminalHeight()        { return terminalHeight; }
}
