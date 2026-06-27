package org.cland.alice.facade.tui.component;

import java.util.List;

/**
 * 底部状态栏组件（单行）— 统一暗灰底色块。
 *
 * <p>所有指标色块统一使用暗灰底(48;5;239) 白色文字(37)：
 *
 * <pre>
 *   \u001B[48;5;239m\u001B[37m  💰 $0.041  \u001B[0m  \u001B[48;5;239m\u001B[37m  📊 125 t/s  \u001B[0m  \u001B[48;5;239m\u001B[37m  🧠 gpt-4o  \u001B[0m ── 🔌 Active: cland-pay-mcp
 * </pre>
 */
public class FooterComponent extends Component {

  /** ANSI 重置码 */
  private static final String ANSI_RESET = "\u001B[0m";

  /** 统一色块前缀：暗灰底(239) 白色文字(37) */
  private static final String BLOCK_PRE = "\u001B[48;5;239m\u001B[37m  ";

  /** 色块后缀：每个色块闭合前留 2 空格内边距 */
  private static final String BLOCK_SUF = "  \u001B[0m";

  /** 色块之间的分隔：2 空格 */
  private static final String BLOCK_SEP = "  ";

  /** 工具信息前缀：暗色双线 + 图标 */
  private static final String TOOL_PREFIX = "\u001B[38;5;242m\u2500\u2500\u001B[0m \uD83D\uDD0C ";

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

    // 统一暗灰底(239) 白色文字(37) 色块格式：
    //   [239:💰 $0.041]  [239:📊 125 t/s]  [239:🧠 gpt-4o] ── 🔌 Active: cland-pay-mcp
    String text =
        BLOCK_PRE
            + "\uD83D\uDCB0 "
            + costInfo
            + BLOCK_SUF
            + BLOCK_SEP
            + BLOCK_PRE
            + "\uD83D\uDCCA "
            + speedInfo
            + BLOCK_SUF
            + BLOCK_SEP
            + BLOCK_PRE
            + "\uD83E\uDDE0 "
            + modelInfo
            + BLOCK_SUF
            + BLOCK_SEP
            + TOOL_PREFIX
            + toolInfo;

    // 去除 ANSI 码后计算实际显示宽度
    String plain = stripAnsi(text);
    StringBuilder sb = new StringBuilder(width + 80); // extra for ANSI codes

    if (plain.length() > width) {
      // 可见字符超出宽度：逐字符采集，保留 ANSI 码
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
