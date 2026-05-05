package org.cland.alice.facade.tui.component;

import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.TextColor;

/**
 * 底部状态栏 / 统计信息组件，用于展示 Token 计数、运行状态等。
 * <p>
 * 通常位于底部输入区域上方或作为 Footer 的一部分。
 */
public class StatusComponent extends Component {

    private int tokenCount;
    private String status;
    private int iteration;

    public StatusComponent() {
        super("Status");
        this.tokenCount = 0;
        this.status = "Idle";
        this.iteration = 0;
    }

    // ========== 状态更新 ==========

    public void updateStats(int tokenCount, String status) {
        this.tokenCount = tokenCount;
        this.status = status;
        markDirty();
    }

    public void setTokenCount(int tokenCount) {
        this.tokenCount = tokenCount;
        markDirty();
    }

    public void setStatus(String status) {
        this.status = status;
        markDirty();
    }

    public void setIteration(int iteration) {
        this.iteration = iteration;
        markDirty();
    }

    // ========== 绘制 ==========

    @Override
    public void draw(TextGraphics g) {
        if (!visible || width <= 0 || height <= 0) return;

        // 背景填充
        g.setBackgroundColor(TextColor.ANSI.BLACK);
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                g.setCharacter(col + c, row + r, ' ');
            }
        }

        g.setForegroundColor(TextColor.ANSI.WHITE);

        // 分隔线（顶部）
        if (height > 1) {
            for (int c = 0; c < width; c++) {
                g.setCharacter(col + c, row, '─');
            }
        }

        // 状态信息行
        if (height > 1) {
            String statusText = "Iter: " + iteration
                + " | Tokens: " + tokenCount
                + " | Status: " + status;
            if (statusText.length() > width) {
                statusText = statusText.substring(0, width);
            }
            int y = row + 1;
            for (int i = 0; i < statusText.length(); i++) {
                g.setCharacter(col + i, y, statusText.charAt(i));
            }
        }

        clearDirty();
    }
}
