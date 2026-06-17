package org.cland.alice.facade.tui.component;

import java.util.List;

/**
 * 底部计费状态栏组件（单行）。
 *
 * <p>对应 Layout.md §7.1 中最底行的全局数据仪表盘，采用 ANSI 256 色分级渲染：
 *
 * <pre>
 * \u001B[38;5;214m💰 $0.041\u001B[0m \u001B[38;5;242m│\u001B[0m \u001B[38;5;75m📊 125 t/s\u001B[0m ...
 * </pre>
 *
 * <p>全程固定在页面最底端，通过 ANSI 定位精确刷新，永不偏移。
 */
public class FooterComponent extends Component {

  /** ANSI 256 色常量 */
  private static final String ANSI_RESET = "\u001B[0m";

  private static final String ANSI_DIM = "\u001B[38;5;242m"; // 暗色分隔符
  private static final String ANSI_COST = "\u001B[38;5;214m"; // 橙色 — 费用
  private static final String ANSI_SPEED = "\u001B[38;5;75m"; // 蓝色 — 速率
  private static final String ANSI_MODEL = "\u001B[38;5;118m"; // 绿色 — 模型
  private static final String ANSI_TOOL = "\u001B[38;5;141m"; // 紫色 — 工具

  private String costInfo;
  private String speedInfo;
  private String modelInfo;
  private String toolInfo;

  public FooterComponent() {
    super("Footer");
    this.costInfo = "$0.000";
    this.speedInfo = "0 t/s";
    this.modelInfo = "N/A";
    this.toolInfo = "none";
  }

  // ========== 状态更新 ==========

  public void setCost(String cost) {
    this.costInfo = cost;
    markDirty();
  }

  public void setSpeed(String speed) {
    this.speedInfo = speed;
    markDirty();
  }

  public void setModel(String modelId) {
    this.modelInfo = modelId;
    markDirty();
  }

  public void setTool(String tool) {
    this.toolInfo = tool;
    markDirty();
  }

  public String costInfo() {
    return costInfo;
  }

  public String speedInfo() {
    return speedInfo;
  }

  public String modelInfo() {
    return modelInfo;
  }

  public String toolInfo() {
    return toolInfo;
  }

  // ========== 渲染 ==========

  @Override
  public List<String> render() {
    if (!visible || width <= 0 || height <= 0) {
      return List.of();
    }
    clearDirty();

    // 格式（含 ANSI 色码）：
    //   \033[38;5;214m💰 $0.041\033[0m \033[38;5;242m│\033[0m \033[38;5;75m📊 125 t/s\033[0m ...
    String text =
        ANSI_COST
            + "\uD83D\uDCB0 "
            + costInfo
            + ANSI_RESET
            + " "
            + ANSI_DIM
            + "\u2502"
            + ANSI_RESET
            + " "
            + ANSI_SPEED
            + "\uD83D\uDCCA "
            + speedInfo
            + ANSI_RESET
            + " "
            + ANSI_DIM
            + "\u2502"
            + ANSI_RESET
            + " "
            + ANSI_MODEL
            + "\uD83E\uDDE0 "
            + modelInfo
            + ANSI_RESET
            + " "
            + ANSI_DIM
            + "\u2502"
            + ANSI_RESET
            + " "
            + ANSI_TOOL
            + "\uD83D\uDD0C "
            + toolInfo
            + ANSI_RESET
            + " ";

    // 去除 ANSI 码后计算实际显示宽度
    String plain = stripAnsi(text);
    StringBuilder sb = new StringBuilder(width + 64); // extra for ANSI codes

    if (plain.length() > width) {
      // 可见字符超出宽度：保留左侧可见字符 + ANSI 码
      // 遍历 text，逐字符追加直到收集到 width 个可见字符
      int visibleCount = 0;
      boolean inAnsi = false;
      for (int i = 0; i < text.length() && visibleCount < width; i++) {
        char c = text.charAt(i);
        sb.append(c);
        if (c == '\u001B') {
          inAnsi = true;
        } else if (inAnsi) {
          if (c == 'm') {
            inAnsi = false;
          }
        } else {
          visibleCount++;
        }
      }
    } else {
      sb.append(text);
      // 用空格填充至 width
      int padLen = width - plain.length();
      if (padLen > 0) {
        sb.append(" ".repeat(padLen));
      }
    }

    return List.of(sb.toString());
  }

  /** 去除 ANSI 转义码，计算纯文本宽度 */
  private static String stripAnsi(String s) {
    return s.replaceAll("\u001B\\[[;\\d]*m", "");
  }
}
