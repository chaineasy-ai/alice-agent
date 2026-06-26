package org.cland.alice.facade.tui.component;

import java.util.List;

/**
 * 顶部标题栏组件（单行）。
 *
 * <p>对应 Layout.md §7.1 最顶行 v2.3 净化设计，显示格式：
 *
 * <pre>
 * 🤖 alice-agent v0.60.0 ────────────────────────────────────────────────────────
 * </pre>
 *
 * <p>v2.3 净化点：
 *
 * <ul>
 *   <li>删除会话 ID 等冗余文本
 *   <li>分割线动态自适应终端宽度延伸至视口最右侧
 *   <li>界面留白透气，无噪细线
 * </ul>
 *
 * <p>所有 ANSI 转义码不计入显示宽度，padding/truncation 基于实际可见字符计算。
 */
public class HeaderComponent extends Component {

  private static final String DEFAULT_LABEL = "alice-agent v0.60.0";

  /** ANSI 暗色分隔线: \033[38;5;242m */
  private static final String ANSI_DIM = "\u001B[38;5;242m";

  private static final String ANSI_RESET = "\u001B[0m";

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

    // 格式:  🤖 alice-agent v0.60.0 ─────────────────────
    // 使用 ANSI 暗色 (38;5;242) 绘制分隔线，延伸至视口最右侧
    String leftPart = " \uD83E\uDD16 " + label + " ";

    // 计算可见分隔线长度
    int visibleSepLen = width - leftPart.length();

    StringBuilder sb = new StringBuilder(width + 64);
    sb.append(leftPart);

    // ANSI 暗色分隔线，延伸至视口最右侧
    if (visibleSepLen > 0) {
      sb.append(ANSI_DIM);
      for (int i = 0; i < visibleSepLen; i++) {
        sb.append('\u2500'); // ─
      }
      sb.append(ANSI_RESET);
    }

    // 用空格填充剩余宽度
    int visibleLen = leftPart.length() + visibleSepLen;
    if (visibleLen < width) {
      sb.append(" ".repeat(width - visibleLen));
    } else if (visibleLen > width) {
      // 可见字符超出宽度，截断到 width（跳过 ANSI 码）
      sb.setLength(0);
      sb.append(leftPart);
      int maxSep = width - leftPart.length();
      if (maxSep > 0) {
        sb.append(ANSI_DIM);
        for (int i = 0; i < maxSep; i++) {
          sb.append('\u2500');
        }
        sb.append(ANSI_RESET);
      }
      visibleLen = leftPart.length() + Math.max(0, maxSep);
      if (visibleLen > width) {
        sb.setLength(width);
      }
    }

    return List.of(sb.toString());
  }
}
