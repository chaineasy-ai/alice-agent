package org.cland.alice.facade.tui.component;

import java.util.List;

/**
 * 底部计费状态栏组件（单行）。
 *
 * <p>对应 Layout.md §7.1 中最底行的： "💰 Cost: $0.041 | 📊 Speed: 125 t/s | 🧠 Model: deepseek-v4-flash •
 * medium | 🔌 Active Tool: cland-pay-mcp"
 *
 * <p>全程固定在页面最底端，永不偏移。
 */
public class FooterComponent extends Component {

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

    // 格式： 💰 Cost: $0.041 | 📊 Speed: 125 t/s | 🧠 Model: xxx | 🔌 Active Tool: yyy
    String text =
        " \uD83D\uDCB0 Cost: "
            + costInfo
            + " | \uD83D\uDCCA Speed: "
            + speedInfo
            + " | \uD83E\uDDE0 Model: "
            + modelInfo
            + " | \uD83D\uDD0C Active Tool: "
            + toolInfo
            + " ";

    StringBuilder sb = new StringBuilder(width);
    if (text.length() > width) {
      sb.append(text, 0, width);
    } else {
      sb.append(text);
      sb.append(" ".repeat(width - text.length()));
    }

    return List.of(sb.toString());
  }
}
