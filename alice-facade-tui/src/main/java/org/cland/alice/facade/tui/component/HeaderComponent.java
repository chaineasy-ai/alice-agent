package org.cland.alice.facade.tui.component;

import java.util.List;

/**
 * 顶部标题栏组件（单行）。
 *
 * <p>对应 Layout.md 中最顶行信息行。仅显示 agent 名称加版本号。
 *
 * <p>参考 docs/alice-facade-tui/Layout.md §7.1
 */
public class HeaderComponent extends Component {

  private static final String DEFAULT_LABEL = "alice v0.1.0";

  private String label;

  public HeaderComponent() {
    super("Header");
    this.label = DEFAULT_LABEL;
  }

  // ========== 状态更新 ==========

  public void setLabel(String label) {
    this.label = label;
    markDirty();
  }

  public String label() {
    return label;
  }

  // ========== 渲染 ==========

  @Override
  public List<String> render() {
    if (!visible || width <= 0 || height <= 0) {
      return List.of();
    }
    clearDirty();

    // 格式： alice v0.1.0                                    （右对齐填充空格）
    String text = " " + label + " ";

    StringBuilder sb = new StringBuilder(width);
    sb.append(text);
    if (text.length() < width) {
      sb.append(" ".repeat(width - text.length()));
    } else {
      sb.setLength(width);
    }

    return List.of(sb.toString());
  }
}
