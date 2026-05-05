package org.cland.alice.facade.tui.component;

import com.googlecode.lanterna.graphics.TextGraphics;

/**
 * TUI 组件抽象基类，对应设计文档 §2 中的 Component 抽象类。
 * <p>
 * 所有 UI 组件（ChatComponent, ThoughtComponent, StatusComponent 等）
 * 继承自此基类，由 ScreenManager 统一管理生命周期与渲染。
 */
public abstract class Component {

    /** 组件名称，用于日志/调试 */
    protected final String name;

    /** 组件在屏幕上的位置与大小 */
    protected volatile int row;
    protected volatile int col;
    protected volatile int width;
    protected volatile int height;

    /** 是否可见 */
    protected volatile boolean visible;

    /** 是否需要重绘 */
    protected volatile boolean dirty;

    protected Component(String name) {
        this.name = name;
        this.visible = true;
        this.dirty = true;
    }

    // ========== 抽象方法 ==========

    /** 绘制组件内容 */
    public abstract void draw(TextGraphics graphics);

    // ========== 布局管理 ==========

    public void setBounds(int row, int col, int width, int height) {
        this.row = row;
        this.col = col;
        this.width = width;
        this.height = height;
        markDirty();
    }

    public void setPosition(int row, int col) {
        this.row = row;
        this.col = col;
        markDirty();
    }

    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
        markDirty();
    }

    // ========== 可见性 ==========

    public boolean isVisible()       { return visible; }
    public void setVisible(boolean v) { this.visible = v; markDirty(); }
    public void show()               { setVisible(true); }
    public void hide()               { setVisible(false); }

    // ========== 脏标记 ==========

    public boolean isDirty()         { return dirty; }
    public void markDirty()          { this.dirty = true; }
    public void clearDirty()         { this.dirty = false; }

    // ========== 辅助方法 ==========

    /** 绘制组件背景填充 */
    protected void fillBackground(TextGraphics g, char ch) {
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                g.setCharacter(col + c, row + r, ch);
            }
        }
    }

    /** 在组件区域内绘制带换行的文本 */
    protected void drawWrappedText(TextGraphics g, String text, int startRow, int startCol) {
        if (text == null || text.isEmpty()) return;

        String[] lines = text.split("\n", -1);
        int maxLines = height - startRow;
        int displayLines = Math.min(lines.length, maxLines);

        for (int i = 0; i < displayLines; i++) {
            int targetRow = row + startRow + i;
            if (targetRow >= row + height) break;

            String line = lines[i];
            if (line.length() > width - startCol) {
                line = line.substring(0, width - startCol);
            }
            for (int c = 0; c < line.length(); c++) {
                int targetCol = col + startCol + c;
                if (targetCol < col + width) {
                    g.setCharacter(targetCol, targetRow, line.charAt(c));
                }
            }
        }
    }

    protected void drawWrappedText(TextGraphics g, String text) {
        drawWrappedText(g, text, 0, 0);
    }

    @Override
    public String toString() {
        return "Component{" + name + ", pos=(" + row + "," + col + "), size="
            + width + "x" + height + ", visible=" + visible + "}";
    }
}
