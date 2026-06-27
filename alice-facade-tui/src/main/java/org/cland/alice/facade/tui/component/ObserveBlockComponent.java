package org.cland.alice.facade.tui.component;

import java.util.ArrayList;
import java.util.List;

/**
 * 观察内容区组件（TAO 四段式 — 底部终端观察块）。
 *
 * <p>对应 Layout_TAO.md §3 "观察内容区"，以终端仿真风格展示 Agent 命令执行的输出和结果。 注意：Agent 执行的命令（ACTION）展示在 {@link
 * ActionBlockComponent} 中。
 *
 * <p>视觉风格：
 *
 * <ul>
 *   <li>ANSI 深色终端背景 (48;5;234 ≈ #1e2329)
 *   <li>目录模式行亮黄 (38;5;222 ≈ #e6c07b)
 *   <li>耗时统计 "Took X.XXs" 暗色 (38;5;246 ≈ #999)
 *   <li>支持上下滚动
 * </ul>
 *
 * <p>典型输出：
 *
 * <pre>
 *   ... (3 earlier lines, ctrl+o to expand)
 *   -rw------- 1 alice alice  111 6月 26 22:01 config.json
 *   drwxrwxr-x 2 alice alice 4096 6月 27 00:01 logs
 *   Took 0.0s
 * </pre>
 */
public class ObserveBlockComponent extends Component {

  private static final int MAX_LINES = 1000;

  /** ANSI 常量 */
  private static final String ANSI_RESET = "\u001B[0m";

  private static final String ANSI_BG_TERMINAL = "\u001B[48;5;234m";
  private static final String ANSI_FG_LIGHT = "\u001B[37m";

  /** 目录模式颜色：亮黄 */
  private static final String ANSI_DIR_MODE = "\u001B[38;5;222m";

  /** 耗时统计颜色：暗灰 */
  private static final String ANSI_TIME_TIP = "\u001B[38;5;246m";

  /** 暗色文本 */
  private static final String ANSI_DIM = "\u001B[38;5;242m";

  /** 缩进 */
  private static final String INDENT = "  ";

  private final List<String> logLines;
  private int scrollOffset;

  public ObserveBlockComponent() {
    super("ObserveBlock");
    this.logLines = new ArrayList<>();
    this.scrollOffset = 0;
  }

  // ========== 内容管理 ==========

  /** 追加原始行。 */
  private void appendLine(String line) {
    if (line == null) return;
    if (logLines.size() >= MAX_LINES) {
      logLines.remove(0);
    }
    String safeLine = line;
    if (!safeLine.endsWith(ANSI_RESET)) {
      safeLine = safeLine + ANSI_RESET;
    }
    logLines.add(safeLine);
    scrollToBottom();
    markDirty();
  }

  /**
   * 添加命令输出行。
   *
   * <p>自动检测目录/文件模式行（以 drwx 或 lrwx 开头），使用亮黄色高亮。
   *
   * @param output 命令输出文本（多行将拆分为多行）
   */
  public void addOutput(String output) {
    if (output == null || output.isBlank()) return;
    for (String line : output.split("\n", -1)) {
      if (line.isBlank()) {
        appendLine(""); // 保留空行
      } else if (line.matches("^[dl-][rwxst-]{9}.*")) {
        // 目录/文件模式行（ls -la 输出），亮黄高亮
        appendLine(INDENT + "\u001B[38;5;222m" + line + ANSI_RESET);
      } else {
        appendLine(INDENT + line);
      }
    }
  }

  /**
   * 添加耗时统计行。
   *
   * <p>格式: {@code Took X.XXs}
   *
   * @param seconds 消耗的秒数
   */
  public void addTiming(double seconds) {
    appendLine(
        INDENT + ANSI_TIME_TIP + "Took " + String.format("%.1f", seconds) + "s" + ANSI_RESET);
    // 命令间空行（留给 ActionBlock 和 ObserveBlock 之间的视觉分隔）
  }

  /**
   * 添加展开提示行（过多行折叠时）。
   *
   * <p>格式: {@code ... (N earlier lines, ctrl+o to expand)}
   *
   * @param count 折叠的行数
   */
  public void addCollapsedLines(int count) {
    if (count <= 0) return;
    String line =
        INDENT + ANSI_DIM + "... (" + count + " earlier lines, ctrl+o to expand)" + ANSI_RESET;
    appendLine(line);
  }

  /** 清空所有内容。 */
  public void clear() {
    logLines.clear();
    scrollOffset = 0;
    markDirty();
  }

  /** 当组件高度变化时调整滚动偏移量。 */
  public void onResize(int oldHeight) {
    if (oldHeight <= 0) return;
    int delta = height - oldHeight;
    if (delta == 0) return;
    int newOffset = scrollOffset - delta;
    scrollOffset = Math.clamp(newOffset, 0, Math.max(0, logLines.size() - height));
    markDirty();
  }

  // ========== 滚动 ==========

  public void scrollUp() {
    if (scrollOffset > 0) {
      scrollOffset--;
      markDirty();
    }
  }

  public void scrollDown() {
    int maxOffset = Math.max(0, logLines.size() - height);
    if (scrollOffset < maxOffset) {
      scrollOffset++;
      markDirty();
    }
  }

  public void scrollToBottom() {
    scrollOffset = Math.max(0, logLines.size() - height);
    markDirty();
  }

  public void pageUp() {
    int pageSize = Math.max(1, height - 1);
    scrollOffset = Math.max(0, scrollOffset - pageSize);
    markDirty();
  }

  public void pageDown() {
    int maxOffset = Math.max(0, logLines.size() - height);
    int pageSize = Math.max(1, height - 1);
    scrollOffset = Math.min(maxOffset, scrollOffset + pageSize);
    markDirty();
  }

  // ========== 渲染 ==========

  @Override
  public List<String> render() {
    if (!visible || width <= 0 || height <= 0 || logLines.isEmpty()) {
      clearDirty();
      return List.of();
    }
    clearDirty();

    List<String> result = new ArrayList<>(height);
    int startIdx = Math.min(scrollOffset, Math.max(0, logLines.size() - height));
    int endIdx = Math.min(startIdx + height, logLines.size());

    for (int i = startIdx; i < endIdx; i++) {
      String raw = logLines.get(i);
      if (visibleWidth(raw) > width) {
        raw = truncateWithAnsi(raw, width);
      }
      raw = ANSI_BG_TERMINAL + ANSI_FG_LIGHT + raw;
      raw = padWithSpaces(raw, width);
      result.add(raw);
    }

    // 填充剩余行为空行（带终端背景色）
    while (result.size() < height) {
      result.add(ANSI_BG_TERMINAL + " ".repeat(width) + ANSI_RESET);
    }

    return result;
  }

  // ========== ANSI 辅助 ==========

  private static int visibleWidth(String s) {
    return stripAnsi(s).length();
  }

  private static String stripAnsi(String s) {
    return s.replaceAll("\u001B\\[[;\\d]*m", "");
  }

  private static String truncateWithAnsi(String s, int maxVisible) {
    StringBuilder sb = new StringBuilder();
    int visible = 0;
    boolean inAnsi = false;
    for (int i = 0; i < s.length() && visible < maxVisible; i++) {
      char c = s.charAt(i);
      sb.append(c);
      if (c == '\u001B') {
        inAnsi = true;
      } else if (inAnsi) {
        if (c == 'm') {
          inAnsi = false;
        }
      } else {
        visible++;
      }
    }
    return sb.toString();
  }

  private static String padWithSpaces(String s, int targetWidth) {
    String plain = stripAnsi(s);
    int padLen = targetWidth - plain.length();
    if (padLen > 0) {
      return s + " ".repeat(padLen);
    }
    return s;
  }
}
