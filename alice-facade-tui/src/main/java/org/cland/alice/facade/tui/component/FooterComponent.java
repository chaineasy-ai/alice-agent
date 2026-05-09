package org.cland.alice.facade.tui.component;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;

/**
 * 底部快捷键提示栏组件。
 *
 * <p>显示功能键提示信息，位于整体边框的底部倒数第二行。 边框由 {@link org.cland.alice.facade.tui.ScreenManager} 统一绘制。
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

  public String hintText() {
    return hintText;
  }

  private static String defaultHint() {
    return " F1:Help | F5:Stop | Tab:Focus | PgUp/PgDn:Scroll | /help | Ctrl+Q:Quit ";
  }

  // ========== 绘制 ==========

  @Override
  public void draw(TextGraphics g) {
    if (!visible || width <= 0 || height <= 0) return;

    // 背景填充
    g.setBackgroundColor(TextColor.ANSI.BLUE);
    for (int c = 0; c < width; c++) {
      g.setCharacter(col + c, row, ' ');
    }

    // 边框已经由 ScreenManager 绘制，这里只绘制内容
    // 内容区域：col+1 到 col+width-2
    g.setForegroundColor(TextColor.ANSI.WHITE);

    int contentStart = col + 1;
    int contentEnd = col + width - 2;
    int maxLen = contentEnd - contentStart + 1;

    String display = hintText;
    if (display.length() > maxLen) {
      // 在中间截断
      int half = (maxLen - 3) / 2;
      display = display.substring(0, half) + "..." + display.substring(display.length() - half);
    }

    for (int i = 0; i < display.length(); i++) {
      g.setCharacter(contentStart + i, row, display.charAt(i));
    }

    clearDirty();
  }
}
