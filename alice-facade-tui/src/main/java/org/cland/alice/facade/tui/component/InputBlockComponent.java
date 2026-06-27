package org.cland.alice.facade.tui.component;

import java.util.ArrayList;
import java.util.List;

/**
 * 输入内容区组件（TAO 三段式 — 顶部块）。
 *
 * <p>对应 Layout_TAO.md §1 "输入内容区（顶部块）"，展示用户输入和会话上下文。
 *
 * <p>视觉风格：
 *
 * <ul>
 *   <li>ANSI 深灰背景 (48;5;236 ≈ #2f3742)
 *   <li>绿色打勾 "✓ New session started" (ANSI 32m 亮绿)
 *   <li>用户输入文本缩进展示
 *   <li>固定区域，不滚动（展示最新用户输入）
 * </ul>
 *
 * <p>典型输出：
 *
 * <pre>
 *   ✓ New session started
 *
 *   together debug current program.session dir is ~/.alice/wal/...
 * </pre>
 */
public class InputBlockComponent extends Component {

  /** 最多保留的行数 */
  private static final int MAX_LINES = 50;

  /** ANSI 颜色常量 */
  private static final String ANSI_RESET = "\u001B[0m";

  private static final String ANSI_GREEN = "\u001B[32m";
  private static final String ANSI_BG_DARK = "\u001B[48;5;236m";
  private static final String ANSI_FG_LIGHT = "\u001B[37m";
  private static final String ANSI_DIM = "\u001B[38;5;242m";

  /** 缩进 */
  private static final String INDENT = "  ";

  private final List<String> logLines;
  private int scrollOffset;

  public InputBlockComponent() {
    super("InputBlock");
    this.logLines = new ArrayList<>();
    this.scrollOffset = 0;
  }

  // ========== 内容管理 ==========

  /**
   * 显示会话启动信息。
   *
   * <p>格式: {@code ✓ New session started}
   */
  public void showSession(String sessionInfo) {
    String line = INDENT + ANSI_GREEN + "\u2713" + ANSI_RESET + " " + sessionInfo;
    appendLine(line);
  }

  /**
   * 显示用户输入内容。
   *
   * <p>显示用户的最新输入，自动处理多行内容。
   */
  public void showUserInput(String input) {
    if (input == null || input.isBlank()) return;
    for (String line : input.split("\n", -1)) {
      appendLine(INDENT + line);
    }
  }

  /**
   * 追加原始行。
   *
   * <p>自动处理颜色重置：如果上一行不是以 ANSI_RESET 结尾，则在追加前先重置。 避免前一行残色的 ANSI 码泄露到本行。
   */
  private void appendLine(String line) {
    if (logLines.size() >= MAX_LINES) {
      logLines.remove(0);
    }
    // 确保每行以重置结束，防止 ANSI 泄露
    String safeLine = line;
    if (!safeLine.endsWith(ANSI_RESET)) {
      safeLine = safeLine + ANSI_RESET;
    }
    logLines.add(safeLine);
    scrollToBottom();
    markDirty();
  }

  public void clear() {
    logLines.clear();
    scrollOffset = 0;
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
    if (!visible || width <= 0 || height <= 0) {
      clearDirty();
      return List.of();
    }
    if (logLines.isEmpty()) {
      clearDirty();
      java.util.List<String> empty = new java.util.ArrayList<>(height);
      for (int i = 0; i < height; i++) {
        empty.add(ANSI_BG_DARK + " ".repeat(width) + ANSI_RESET);
      }
      return empty;
    }
    clearDirty();

    List<String> result = new ArrayList<>(height);
    int startIdx = Math.min(scrollOffset, Math.max(0, logLines.size() - height));
    int endIdx = Math.min(startIdx + height, logLines.size());

    for (int i = startIdx; i < endIdx; i++) {
      String raw = logLines.get(i);
      // 截断过长行
      if (visibleWidth(raw) > width) {
        raw = truncateWithAnsi(raw, width);
      }
      // 左侧填充背景色，右侧填充空格使整行背景连续
      raw = ANSI_BG_DARK + ANSI_FG_LIGHT + raw;
      raw = padWithSpaces(raw, width);
      result.add(raw);
    }

    // 填充剩余行为空行（带背景色）
    while (result.size() < height) {
      result.add(ANSI_BG_DARK + " ".repeat(width) + ANSI_RESET);
    }

    return result;
  }

  // ========== ANSI 辅助 ==========

  /** 计算字符串的可见宽度（去除 ANSI 码） */
  private static int visibleWidth(String s) {
    return stripAnsi(s).length();
  }

  /** 去除 ANSI 转义码 */
  private static String stripAnsi(String s) {
    return s.replaceAll("\u001B\\[[;\\d]*m", "");
  }

  /** 截断字符串到指定可见宽度，保留 ANSI 码 */
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

  /** 右侧填充空格使可见宽度达到指定值 */
  private static String padWithSpaces(String s, int targetWidth) {
    String plain = stripAnsi(s);
    int padLen = targetWidth - plain.length();
    if (padLen > 0) {
      return s + " ".repeat(padLen);
    }
    return s;
  }
}
