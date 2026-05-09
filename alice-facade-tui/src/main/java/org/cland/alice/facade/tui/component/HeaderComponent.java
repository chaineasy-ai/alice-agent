package org.cland.alice.facade.tui.component;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;

/**
 * 顶部标题栏组件。
 *
 * <p>显示在整体边框的顶部，单行显示 Agent 版本、当前模型、运行状态。 边框由 {@link org.cland.alice.facade.tui.ScreenManager} 统一绘制。
 */
public class HeaderComponent extends Component {

  private static final String DEFAULT_TITLE = " Alice Agent v1.0 ";

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

  public String title() {
    return title;
  }

  public String modelId() {
    return modelId;
  }

  public String status() {
    return status;
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

    g.setForegroundColor(TextColor.ANSI.WHITE);

    // 内容：在边框内部绘制标题信息
    // 边框占了 col 0 和 col width-1，内容从 col+1 开始，到 col+width-2
    // 格式： Alice Agent v1.0 ── Model: xxx ── Status: yyy
    String text = title + " ── Model: " + modelId + " ── Status: " + status;

    int contentStart = col + 2; // 跳过 ┌ 和 ─
    int contentEnd = col + width - 2; // 跳过 ─ 和 ┐
    int maxLen = contentEnd - contentStart + 1;

    if (text.length() > maxLen) {
      text = text.substring(0, maxLen);
    }

    for (int i = 0; i < text.length(); i++) {
      g.setCharacter(contentStart + i, row, text.charAt(i));
    }

    clearDirty();
  }
}
