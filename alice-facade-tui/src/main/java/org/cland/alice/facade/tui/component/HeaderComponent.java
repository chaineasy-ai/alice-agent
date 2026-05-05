package org.cland.alice.facade.tui.component;

import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.TextColor;

/**
 * 顶部标题栏组件，对应设计文档 §7.1 布局中的 Header 区域。
 * <p>
 * 显示：Agent 版本、当前模型、运行状态。
 * <pre>
 * [Alice Agent v1.0] | Model: GPT-4o | Status: Thinking...
 * </pre>
 */
public class HeaderComponent extends Component {

    private static final String DEFAULT_TITLE = "Alice Agent v1.0";

    private String title;
    private String modelId;
    private String status;

    public HeaderComponent() {
        super("Header");
        this.title = DEFAULT_TITLE;
        this.modelId = "N/A";
        this.status = "Idle";
    }

    // ========== 状态更新 ==========

    public void setTitle(String title) {
        this.title = title;
        markDirty();
    }

    public void setModel(String modelId) {
        this.modelId = modelId;
        markDirty();
    }

    public void setStatus(String status) {
        this.status = status;
        markDirty();
    }

    // ========== 绘制 ==========

    @Override
    public void draw(TextGraphics g) {
        if (!visible || width <= 0 || height <= 0) return;

        // 背景填充
        g.setBackgroundColor(TextColor.ANSI.BLUE);
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                g.setCharacter(col + c, row + r, ' ');
            }
        }
        g.setForegroundColor(TextColor.ANSI.WHITE);

        // 标题（居中或左对齐）
        String leftText = "[" + title + "]";
        String midText = "Model: " + modelId;
        String rightText = "Status: " + status;

        // 清空行
        int y = row;
        StringBuilder line = new StringBuilder();
        line.append(leftText).append("  |  ").append(midText).append("  |  ").append(rightText);

        String display = line.toString();
        if (display.length() > width) {
            display = display.substring(0, width);
        }
        for (int i = 0; i < display.length(); i++) {
            g.setCharacter(col + i, y, display.charAt(i));
        }

        clearDirty();
    }
}
