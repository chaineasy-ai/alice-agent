package org.cland.alice.facade.tui.component;

import java.util.List;

/**
 * 分割线组件（单行）— 三区对齐布局的区域分隔。
 *
 * <p>渲染一个 ANSI 暗色水平分割线，延伸至整个终端宽度：
 *
 * <pre>
 *   ──────────────────────────────────────────
 * </pre>
 *
 * <p>作为独立 Component 参与布局管线和脏标记系统，取代旧的 inline writeRow() 调用。
 */
public class LineComponent extends Component {

  /** 分割线字符 */
  private static final char SEPARATOR_CHAR = '\u2500'; // ─

  private static final String ANSI_DIM = "\u001B[38;5;242m";
  private static final String ANSI_RESET = "\u001B[0m";

  public LineComponent() {
    super("Separator");
  }

  @Override
  public List<String> render() {
    if (!visible || width <= 0 || height <= 0) {
      clearDirty();
      return List.of();
    }
    clearDirty();

    StringBuilder sb = new StringBuilder(width + 16);
    sb.append(ANSI_DIM);
    for (int i = 0; i < width; i++) {
      sb.append(SEPARATOR_CHAR);
    }
    sb.append(ANSI_RESET);

    return List.of(sb.toString());
  }
}
