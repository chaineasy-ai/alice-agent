package org.cland.alice.facade.tui.component;

import java.util.ArrayList;
import java.util.List;

/**
 * 动作内容区组件（TAO 四段式 — 动作块）。
 *
 * <p>与 {@link InputBlockComponent} 结构一致，展示 Agent 正在执行的动作/命令。
 *
 * <p>视觉风格：
 *
 * <ul>
 *   <li>ANSI 深灰背景 (48;5;236 ≈ #2f3742) — 与 InputBlock 一致
 *   <li>命令提示符 "&#36;" 亮蓝 (38;5;39 ≈ #61dafb)
 *   <li>超时标签 "(timeout 120s)" 淡蓝 (38;5;147 ≈ #a0a0ff)
 *   <li>固定区域，可翻滚展示最近执行的动作
 * </ul>
 *
 * <p>典型输出：
 *
 * <pre>
 *   $ ls -la ~/.alice/ (timeout 120s)
 *   $ ls -la ~/.alice/wal/ (timeout 120s)
 * </pre>
 */
public class ActionBlockComponent extends Component {

  /** 最多保留的行数 */
  private static final int MAX_LINES = 50;

  /** ANSI 颜色常量 — 与 InputBlock 一致 */
  private static final String ANSI_RESET = "\u001B[0m";

  private static final String ANSI_BG_DARK = "\u001B[48;5;236m";
  private static final String ANSI_FG_LIGHT = "\u001B[37m";

  /** 命令提示符颜色：亮蓝 */
  private static final String ANSI_CMD_PREFIX = "\u001B[38;5;39m";

  /** 超时标签颜色：淡蓝 */
  private static final String ANSI_TIMEOUT = "\u001B[38;5;147m";

  /** 默认超时标签 */
  private static final String DEFAULT_TIMEOUT = "120s";

  /** 缩进 */
  private static final String INDENT = "  ";

  private final List<String> logLines;
  private int scrollOffset;

  public ActionBlockComponent() {
    super("ActionBlock");
    this.logLines = new ArrayList<>();
    this.scrollOffset = 0;
  }

  // ========== 内容管理 ==========

  /**
   * 添加命令执行行。
   *
   * <p>格式: {@code $ <command> (timeout 120s)}
   *
   * @param command 要执行的命令描述
   */
  public void addCommand(String command) {
    if (command == null || command.isBlank()) return;
    String line =
        INDENT
            + ANSI_CMD_PREFIX
            + "$"
            + ANSI_RESET
            + " "
            + command
            + " "
            + ANSI_TIMEOUT
            + "(timeout "
            + DEFAULT_TIMEOUT
            + ")"
            + ANSI_RESET;
    appendLine(line);
  }

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
      if (visibleWidth(raw) > width) {
        raw = truncateWithAnsi(raw, width);
      }
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
