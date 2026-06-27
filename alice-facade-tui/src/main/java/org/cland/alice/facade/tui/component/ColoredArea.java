package org.cland.alice.facade.tui.component;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * 带整块背景色的自定义区块Area（适配聊天Main/Input/Footer分区）。
 *
 * <p>首先绘制完整块状背景色填充整个区域，然后由子类覆盖渲染内部内容。 适用于需要统一底色的大区块（如聊天主区域、输入区、底部状态栏）。
 */
public class ColoredArea extends Area {

  private final AttributedStyle bgStyle;
  private Area content;

  public ColoredArea(AttributedStyle bgStyle) {
    this.bgStyle = bgStyle;
    this.content = null;
  }

  public ColoredArea(AttributedStyle bgStyle, Area content) {
    this.bgStyle = bgStyle;
    this.content = content;
  }

  /** 设置内部内容区域（渲染在底色之上）。 */
  public void setContent(Area content) {
    this.content = content;
  }

  @Override
  public void render(AttributedStringBuilder buf) {
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0) return;

    // ========== 第一步：绘制完整块状背景 ==========
    String fullBlank = " ".repeat(w);
    for (int y = 0; y < h; y++) {
      buf.append(new AttributedString(fullBlank, bgStyle));
      buf.append("\n");
    }

    // ========== 第二步：渲染内部子组件（覆盖在底色之上） ==========
    if (content != null) {
      content.render(buf);
    }
  }
}
