package org.cland.alice.facade.tui.component;

import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.TextColor;

/**
 * 底部快捷键提示栏组件，对应设计文档 §7.1 布局中的 Footer 区域。
 * <p>
 * <pre>
 * F1:Help | F2:Settings | F5:Stop | Ctrl+C:Quit
 * </pre>
 */
public class FooterComponent extends Component {

    /** 功能键提示文本 */
    private String hintText;

    public FooterComponent() {
        super("Footer");
        this.hintText = defaultHint();
    }

    public void setHintText(String hintText) {
        this.hintText = hintText;
        markDirty();
    }

    public void resetToDefault() {
        this.hintText = defaultHint();
        markDirty();
    }

    private static String defaultHint() {
        return "F1:Help  |  F5:Stop  |  Tab:Focus  |  PgUp/PgDn:Scroll  |  /help  |  Ctrl+Q:Quit";
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

        String display = hintText;
        if (display.length() > width) {
            display = display.substring(0, width);
        }

        int y = row;
        for (int i = 0; i < display.length(); i++) {
            g.setCharacter(col + i, y, display.charAt(i));
        }

        clearDirty();
    }
}
