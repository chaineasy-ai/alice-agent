package org.cland.alice.facade.tui.component;

import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * 思考流面板组件。
 * <p>
 * 实时展示 Agent 的思考过程。
 * 边框和分隔线由 {@link org.cland.alice.facade.tui.ScreenManager} 统一绘制，
 * 本组件只负责绘制内容区域。
 */
public class ThoughtComponent extends Component {

    private static final int MAX_THOUGHTS = 200;

    private final List<ThoughtEntry> thoughts;
    private int scrollOffset;

    public ThoughtComponent() {
        super("Thought");
        this.thoughts = new ArrayList<>();
        this.scrollOffset = 0;
    }

    // ========== 思考管理 ==========

    /**
     * 追加思考片段。
     *
     * @param thought 思考内容
     * @param step    步骤编号
     */
    public void addThought(String thought, int step) {
        if (thoughts.size() >= MAX_THOUGHTS) {
            thoughts.remove(0);
        }
        thoughts.add(new ThoughtEntry(step, thought));
        scrollToBottom();
        markDirty();
    }

    /**
     * 追加动作执行记录。
     *
     * @param actionDescription 动作描述
     */
    public void addAction(String actionDescription) {
        if (thoughts.size() >= MAX_THOUGHTS) {
            thoughts.remove(0);
        }
        thoughts.add(new ThoughtEntry(-1, "\u26A1 " + actionDescription));
        scrollToBottom();
        markDirty();
    }

    /**
     * 追加观测反馈。
     *
     * @param observation 观测结果
     */
    public void addObservation(String observation) {
        if (thoughts.size() >= MAX_THOUGHTS) {
            thoughts.remove(0);
        }
        thoughts.add(new ThoughtEntry(-1, "\u25C9 " + observation));
        scrollToBottom();
        markDirty();
    }

    public void clear() {
        thoughts.clear();
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

        // 内容绘制
        List<String> renderedLines = renderThoughts();

        int startLine = scrollOffset;
        for (int i = 0; i < height; i++) {
            int lineIdx = startLine + i;
            if (lineIdx >= renderedLines.size()) break;

            String line = renderedLines.get(lineIdx);
            if (line.length() > width) {
                line = line.substring(0, width);
            }

            // 根据前缀着色
            g.setForegroundColor(TextColor.ANSI.WHITE);
            if (line.startsWith("\u26A1 ")) {
                g.setForegroundColor(TextColor.ANSI.YELLOW);
            } else if (line.startsWith("\u25C9 ")) {
                g.setForegroundColor(TextColor.ANSI.MAGENTA);
            } else if (line.matches("^\\[\\d+\\].*")) {
                g.setForegroundColor(TextColor.ANSI.GREEN);
            }

            for (int c = 0; c < line.length(); c++) {
                g.setCharacter(col + c, row + i, line.charAt(c));
            }
        }

        clearDirty();
    }

    private List<String> renderThoughts() {
        List<String> lines = new ArrayList<>();
        for (ThoughtEntry entry : thoughts) {
            String prefix = entry.step() > 0
                ? "[" + entry.step() + "]> "
                : "    ";
            String[] parts = entry.thought().split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i == 0) {
                    lines.add(prefix + parts[i]);
                } else {
                    lines.add("     " + parts[i]);
                }
            }
        }
        return lines;
    }

    private int calculateContentLines() {
        return renderThoughts().size();
    }

    private record ThoughtEntry(int step, String thought) {}
}
