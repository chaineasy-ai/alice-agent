package org.cland.alice.facade.tui.component;

import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天历史面板组件，对应设计文档 §7.1 布局中左侧的 Chat History 区域。
 * <p>
 * 显示用户与 Agent 之间的对话记录。
 * 支持自动滚动到最新消息。
 */
public class ChatComponent extends Component {

    /** 最大保留消息数 */
    private static final int MAX_MESSAGES = 500;

    private final List<ChatLine> messages;
    private int scrollOffset;

    public ChatComponent() {
        super("Chat");
        this.messages = new ArrayList<>();
        this.scrollOffset = 0;
    }

    // ========== 消息管理 ==========

    /**
     * 添加一条消息。
     *
     * @param sender  发送者名称（如 User, Agent, System）
     * @param content 消息内容（可包含多行）
     */
    public void addMessage(String sender, String content) {
        if (messages.size() >= MAX_MESSAGES) {
            messages.remove(0);
        }
        messages.add(new ChatLine(sender, content));
        // 自动滚动到底部
        scrollToBottom();
        markDirty();
    }

    public void clearMessages() {
        messages.clear();
        scrollOffset = 0;
        markDirty();
    }

    // ========== 滚动 ==========

    public void scrollUp() {
        if (scrollOffset > 0) {
            scrollOffset--;
            markDirty();
        }
    }

    public void scrollDown() {
        int totalLines = calculateTotalLines();
        int visibleLines = height > 0 ? height : 1;
        int maxOffset = Math.max(0, totalLines - visibleLines);
        if (scrollOffset < maxOffset) {
            scrollOffset++;
            markDirty();
        }
    }

    public void scrollToBottom() {
        int totalLines = calculateTotalLines();
        int visibleLines = height > 0 ? height : 1;
        scrollOffset = Math.max(0, totalLines - visibleLines);
        markDirty();
    }

    public void pageUp() {
        int pageSize = Math.max(1, height - 2);
        scrollOffset = Math.max(0, scrollOffset - pageSize);
        markDirty();
    }

    public void pageDown() {
        int totalLines = calculateTotalLines();
        int visibleLines = height > 0 ? height : 1;
        int pageSize = Math.max(1, height - 2);
        scrollOffset = Math.min(Math.max(0, totalLines - visibleLines), scrollOffset + pageSize);
        markDirty();
    }

    // ========== 绘制 ==========

    @Override
    public void draw(TextGraphics g) {
        if (!visible || width <= 0 || height <= 0) return;

        // 清空区域
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                g.setCharacter(col + c, row + r, ' ');
            }
        }

        // 边框标题
        g.setForegroundColor(TextColor.ANSI.CYAN);
        String titleText = " [Chat History] ";
        int titleX = col + 2;
        for (int i = 0; i < titleText.length() && titleX + i < col + width; i++) {
            g.setCharacter(titleX + i, row, titleText.charAt(i));
        }

        // 分隔线
        if (height > 1) {
            for (int c = 0; c < width; c++) {
                g.setCharacter(col + c, row + 1, '─');
            }
        }

        // 绘制消息
        g.setForegroundColor(TextColor.ANSI.WHITE);
        List<String> renderedLines = renderMessages();
        int contentStartRow = row + 2;
        int maxContentRows = height - 3; // 预留标题行、分隔行、底部行

        int startLine = scrollOffset;
        for (int i = 0; i < maxContentRows; i++) {
            int lineIdx = startLine + i;
            if (lineIdx >= renderedLines.size()) break;

            String line = renderedLines.get(lineIdx);
            int y = contentStartRow + i;
            if (y >= row + height - 1) break;

            // 截断过长的行
            if (line.length() > width) {
                line = line.substring(0, width);
            }
            for (int c = 0; c < line.length(); c++) {
                g.setCharacter(col + c, y, line.charAt(c));
            }
        }

        clearDirty();
    }

    /** 将消息列表渲染为纯文本行列表（考虑换行） */
    private List<String> renderMessages() {
        List<String> lines = new ArrayList<>();
        for (ChatLine msg : messages) {
            // 发送者标签
            String prefix = switch (msg.sender.toLowerCase()) {
                case "user"   -> "User: ";
                case "agent"  -> "Agent: ";
                case "system" -> "  ⚙ ";
                default       -> msg.sender + ": ";
            };

            String[] contentLines = msg.content.split("\n", -1);
            for (int i = 0; i < contentLines.length; i++) {
                if (i == 0) {
                    lines.add(prefix + contentLines[i]);
                } else {
                    lines.add("  " + contentLines[i]);
                }
            }
        }
        return lines;
    }

    /** 计算所有消息渲染后的总行数 */
    private int calculateTotalLines() {
        return renderMessages().size();
    }

    // ========== 记录类型 ==========

    private record ChatLine(String sender, String content) {}
}
