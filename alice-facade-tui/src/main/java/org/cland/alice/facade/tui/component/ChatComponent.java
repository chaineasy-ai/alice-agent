package org.cland.alice.facade.tui.component;

import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天历史面板组件。
 * <p>
 * 显示用户与 Agent 之间的对话记录。
 * 边框和分隔线由 {@link org.cland.alice.facade.tui.ScreenManager} 统一绘制，
 * 本组件只负责绘制内容区域。
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
        int totalLines = calculateContentLines();
        int visibleLines = height;
        int maxOffset = Math.max(0, totalLines - visibleLines);
        if (scrollOffset < maxOffset) {
            scrollOffset++;
            markDirty();
        }
    }

    public void scrollToBottom() {
        int totalLines = calculateContentLines();
        int visibleLines = height;
        scrollOffset = Math.max(0, totalLines - visibleLines);
        markDirty();
    }

    public void pageUp() {
        int pageSize = Math.max(1, height - 1);
        scrollOffset = Math.max(0, scrollOffset - pageSize);
        markDirty();
    }

    public void pageDown() {
        int totalLines = calculateContentLines();
        int visibleLines = height;
        int pageSize = Math.max(1, height - 1);
        scrollOffset = Math.min(Math.max(0, totalLines - visibleLines), scrollOffset + pageSize);
        markDirty();
    }

    // ========== 绘制 ==========

    @Override
    public void draw(TextGraphics g) {
        if (!visible || width <= 0 || height <= 0) return;

        // 清空内容区域
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                g.setCharacter(col + c, row + r, ' ');
            }
        }

        // 绘制消息内容
        g.setForegroundColor(TextColor.ANSI.WHITE);
        List<String> renderedLines = renderMessages();

        int startLine = scrollOffset;
        for (int i = 0; i < height; i++) {
            int lineIdx = startLine + i;
            if (lineIdx >= renderedLines.size()) break;

            String line = renderedLines.get(lineIdx);
            if (line.length() > width) {
                line = line.substring(0, width);
            }
            for (int c = 0; c < line.length(); c++) {
                g.setCharacter(col + c, row + i, line.charAt(c));
            }
        }

        clearDirty();
    }

    /** 将消息列表渲染为纯文本行列表（考虑换行） */
    private List<String> renderMessages() {
        List<String> lines = new ArrayList<>();
        for (ChatLine msg : messages) {
            String prefix = switch (msg.sender.toLowerCase()) {
                case "user"   -> "User: ";
                case "agent"  -> "Agent: ";
                case "system" -> "  \u2699 ";
                default       -> msg.sender + ": ";
            };

            String[] contentLines = msg.content.split("\n", -1);
            for (int i = 0; i < contentLines.length; i++) {
                if (i == 0) {
                    lines.add(prefix + contentLines[i]);
                } else {
                    lines.add("    " + contentLines[i]);
                }
            }
        }
        return lines;
    }

    /** 计算所有消息渲染后的总行数 */
    private int calculateContentLines() {
        return renderMessages().size();
    }

    // ========== 记录类型 ==========

    private record ChatLine(String sender, String content) {}
}
