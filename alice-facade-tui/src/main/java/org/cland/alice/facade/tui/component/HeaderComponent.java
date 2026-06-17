package org.cland.alice.facade.tui.component;

import java.util.List;

/**
 * 顶部标题栏组件（单行）。
 *
 * <p>对应 Layout.md §7.1 最顶行，显示格式：
 *
 * <pre>
 * 🤖 alice-agent v0.1.0 ────────────────────────────────── [Session: xxx]
 * </pre>
 *
 * <p>标题行尾部自带一条 ANSI 暗色半高细线延伸到 [Session: xxx] 标签， 不再需要外部独立的分割线行。
 *
 * <p>所有 ANSI 转义码不计入显示宽度，padding/truncation 基于实际可见字符计算。
 */
public class HeaderComponent extends Component {

  private static final String DEFAULT_LABEL = "alice-agent v0.1.0";

  /** ANSI 暗色分隔线: \033[38;5;242m */
  private static final String ANSI_DIM = "\u001B[38;5;242m";

  private static final String ANSI_RESET = "\u001B[0m";

  private String label;
  private String sessionLabel;

  public HeaderComponent() {
    super("Header");
    this.label = DEFAULT_LABEL;
    this.sessionLabel = "";
  }

  // ========== 状态更新 ==========

  public void setLabel(String label) {
    this.label = label;
    markDirty();
  }

  public String label() {
    return label;
  }

  /** 设置右侧会话标签，例如 "[Session: C-Land Pay]" */
  public void setSessionLabel(String sessionLabel) {
    this.sessionLabel = sessionLabel;
    markDirty();
  }

  public String sessionLabel() {
    return sessionLabel;
  }

  // ========== 渲染 ==========

  @Override
  public List<String> render() {
    if (!visible || width <= 0 || height <= 0) {
      return List.of();
    }
    clearDirty();

    // 格式:  🤖 alice-agent v0.1.0 ───────────── [Session: xxx]
    // 使用 ANSI 暗色 (38;5;242) 绘制分隔线字符
    // ANSI 码本身不计入显示宽度，单独跟踪
    String leftPart = " \uD83E\uDD16 " + label + " ";

    // 计算可见分隔线长度
    int visibleSepLen = width - leftPart.length();
    String rightPart = "";
    if (sessionLabel != null && !sessionLabel.isEmpty()) {
      rightPart = " " + sessionLabel + " ";
      visibleSepLen -= rightPart.length();
    }

    StringBuilder sb = new StringBuilder(width + 64);
    sb.append(leftPart);

    // ANSI 暗色分隔线
    if (visibleSepLen > 0) {
      sb.append(ANSI_DIM);
      for (int i = 0; i < visibleSepLen; i++) {
        sb.append('\u2500'); // ─
      }
      sb.append(ANSI_RESET);
    }

    // 右侧会话标签（纯文本，无 ANSI）
    sb.append(rightPart);

    // 计算实际可见字符数，用空格填充剩余宽度
    int visibleLen = leftPart.length() + visibleSepLen + rightPart.length();
    if (visibleLen < width) {
      sb.append(" ".repeat(width - visibleLen));
    } else if (visibleLen > width) {
      // 可见字符超出宽度，截断到 width（跳过 ANSI 码）
      sb.setLength(0);
      sb.append(leftPart);
      // 仅追加能放下的分隔符
      int maxSep = width - leftPart.length() - rightPart.length();
      if (maxSep > 0) {
        sb.append(ANSI_DIM);
        for (int i = 0; i < maxSep; i++) {
          sb.append('\u2500');
        }
        sb.append(ANSI_RESET);
      }
      sb.append(rightPart);
      // 最终的可见长度应该正好等于 width 或超出
      visibleLen = leftPart.length() + Math.max(0, maxSep) + rightPart.length();
      if (visibleLen > width) {
        sb.setLength(width);
      }
    }

    return List.of(sb.toString());
  }
}
