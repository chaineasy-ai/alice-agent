package org.cland.alice.facade.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/**
 * 思考内容区组件（TAO 三段式 — 中间推理块）。
 *
 * <p>对应 Layout_TAO.md §2 "思考内容区（中间推理块）"，集中展示 Agent 的推理/思考过程。
 *
 * <p>视觉风格：
 *
 * <ul>
 *   <li>ANSI 亮灰背景 (48;5;255 ≈ #f6f8fa)
 *   <li>深色文字 (30m)
 *   <li>支持 TAO THOUGHT 色块标签（复用 {@link TaoTag}）
 *   <li>支持上下滚动
 * </ul>
 *
 * <p>典型输出：
 *
 * <pre>
 *   The user wants to debug the current program. They mention:
 *   - session dir: ~/.alice/wal/
 *   - logs dir: ~/.alice/logs/
 * </pre>
 */
public class ThinkBlockComponent extends Component {

  private static final int MAX_LINES = 1000;

  /** TAO 行缩进 */
  private static final String TAO_INDENT = "  ";

  /** ANSI 常量 */
  private static final String ANSI_RESET = "\u001B[0m";

  private static final String ANSI_BG_LIGHT = "\u001B[48;5;255m";
  private static final String ANSI_FG_DARK = "\u001B[30m";
  private static final String ANSI_BOLD = "\u001B[1m";
  private static final String ANSI_DIM = "\u001B[38;5;242m";

  /** ACTION 色块：橙黄底 + 黑字 — 9字符等宽 */
  private static final String ACTION_TAG =
      new AttributedString(
              " ACTION  ",
              AttributedStyle.DEFAULT.background(255, 135, 0).foreground(AttributedStyle.BLACK))
          .toAnsi();

  /** OBSERVE 色块：绿底 + 黑字 — 9字符等宽 */
  private static final String OBSERVE_TAG =
      new AttributedString(
              " OBSERVE ",
              AttributedStyle.DEFAULT.background(0, 175, 75).foreground(AttributedStyle.BLACK))
          .toAnsi();

  /** 缩进 + ACTION 色块 + 恢复 ThinkBlock 背景 */
  private static final String ACTION_PREFIX =
      TAO_INDENT + ACTION_TAG + ANSI_BG_LIGHT + ANSI_FG_DARK + " ";

  /** 缩进 + OBSERVE 色块 + 恢复 ThinkBlock 背景 */
  private static final String OBSERVE_PREFIX =
      TAO_INDENT + OBSERVE_TAG + ANSI_BG_LIGHT + ANSI_FG_DARK + " ";

  /** 耗时前缀（暗色） */
  private static final String TIMING_PREFIX = TAO_INDENT + "  " + ANSI_DIM;

  /** Agent 内部协议标记 */
  private static final Pattern AGENT_MARKERS = Pattern.compile("\\[FINISH\\]");

  private final List<String> logLines;
  private int scrollOffset;

  public ThinkBlockComponent() {
    super("ThinkBlock");
    this.logLines = new ArrayList<>();
    this.scrollOffset = 0;
  }

  // ========== 内容管理 ==========

  /** 追加原始日志行。 */
  public void appendLine(String line) {
    if (line == null) return;
    if (logLines.size() >= MAX_LINES) {
      logLines.remove(0);
    }
    logLines.add(line);
    scrollToBottom();
    markDirty();
  }

  /**
   * 追加思考片段。
   *
   * <p>使用暗色 step 标记前缀区分连续推理块，避免多段推理视觉粘连。 区域背景色已由 ThinkBlock 亮色背景区分。
   */
  public void addThought(String thought, int step) {
    addThought(thought, step, null);
  }

  /**
   * 追加思考片段（含 traceId）。
   *
   * <p>step 标记后附加 traceId 短哈希，方便关联同一 trace 下的 t/a/o 微单元。
   */
  public void addThought(String thought, int step, String traceId) {
    if (thought != null) {
      if (step > 0) {
        var sb = new StringBuilder();
        sb.append(TAO_INDENT).append(ANSI_DIM).append("\u2508 Step ").append(step);
        if (traceId != null && !traceId.isBlank()) {
          String shortTrace = traceId.length() > 8 ? traceId.substring(0, 8) : traceId;
          sb.append(" [").append(shortTrace).append("]");
        }
        sb.append(" \u2508").append(ANSI_RESET);
        appendLine(sb.toString());
      }
      for (String line : resolveLines(thought)) {
        appendLine(TAO_INDENT + line);
      }
    }
  }

  /**
   * 追加用户消息（支持多行，自动处理 \n 转义）。
   *
   * <p>v2.3 净化设计：不显示角色前缀。
   */
  public void addUserMessage(String content) {
    if (content == null) return;
    for (String line : resolveLines(content)) {
      appendLine("  " + line);
    }
  }

  /**
   * 追加 Action 执行行到思考区域（保持 PAO 时间序）。
   *
   * <p>在思考内容之后插入橙黄色箭头行，形如：
   *
   * <pre>
   *     ⮞ TOOL_CALL: list_dir ({path:.})
   * </pre>
   */
  public void addActionLine(String desc) {
    addActionLine(desc, null);
  }

  /**
   * 追加 Action 执行行到思考区域（含 traceId）。
   *
   * <p>在 action 描述后附加 traceId 短哈希，方便关联到所属的 thought。
   */
  public void addActionLine(String desc, String traceId) {
    if (desc == null || desc.isBlank()) return;
    appendLine(ACTION_PREFIX + desc + ANSI_RESET);
  }

  /**
   * 追加 Observation 结果行到思考区域（保持 PAO 时间序）。
   *
   * <p>在 action 行之后插入绿色箭头行，形如：
   *
   * <pre>
   *     ⮞ # Alice Agent...
   *     (Took 0.0s)
   * </pre>
   *
   * @param observation 观测内容（多行自动拆分）
   * @param elapsedSec 执行耗时（秒）
   */
  /** Pattern to match leading "$ command" lines (skip them since action is already shown) */
  private static final java.util.regex.Pattern ACTION_CMD_PREFIX =
      java.util.regex.Pattern.compile("^\\s*\\$\\s+\\S+.*");

  public void addObservationLine(String observation, double elapsedSec) {
    if (observation == null || observation.isBlank()) return;
    // 取观测内容的前 3 行摘要，避免撑爆 ThinkBlock
    String[] lines = observation.split("\n", -1);
    int printed = 0;
    int totalLines = 0;
    for (String line : lines) {
      // Skip "$ command" lines (already shown by addActionLine) and blank lines
      if (line.isBlank() || ACTION_CMD_PREFIX.matcher(line).matches()) continue;
      totalLines++;
      if (printed >= 3) continue;
      appendLine(OBSERVE_PREFIX + line);
      printed++;
    }
    if (totalLines > 3) {
      appendLine(
          OBSERVE_PREFIX + ANSI_DIM + "... (" + (totalLines - 3) + " more lines)" + ANSI_RESET);
    }
    // 耗时行
    appendLine(TIMING_PREFIX + "(Took " + String.format("%.1f", elapsedSec) + "s)" + ANSI_RESET);
  }

  /** 追加系统消息（支持多行，自动处理 \n 转义）。 */
  public void addSystemMessage(String content) {
    if (content == null) return;
    for (String line : resolveLines(content)) {
      appendLine("  " + line);
    }
  }

  /**
   * 追加 Agent 消息（支持多行，自动处理 \n 转义）。
   *
   * <p>自动剥除内部协议标记（如 [FINISH]）。
   */
  public void addAgentMessage(String content) {
    if (content == null) return;
    String clean = AGENT_MARKERS.matcher(content).replaceAll("").trim();
    if (clean.isEmpty()) return;
    for (String line : resolveLines(clean)) {
      appendLine("  " + line);
    }
  }

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
    if (!visible || width <= 0 || height <= 0) {
      clearDirty();
      return List.of();
    }
    if (logLines.isEmpty()) {
      clearDirty();
      java.util.List<String> empty = new java.util.ArrayList<>(height);
      for (int i = 0; i < height; i++) {
        empty.add(ANSI_BG_LIGHT + " ".repeat(width) + ANSI_RESET);
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
      raw = ANSI_BG_LIGHT + ANSI_FG_DARK + raw;
      raw = padWithSpaces(raw, width);
      result.add(raw);
    }

    // 填充剩余行为空行（带背景色）
    while (result.size() < height) {
      result.add(ANSI_BG_LIGHT + " ".repeat(width) + ANSI_RESET);
    }

    return result;
  }

  // ========== 工具 ==========

  /** 将内容按行拆分，处理 \n 转义序列。 */
  private static String[] resolveLines(String content) {
    if (content == null) return new String[0];
    String normalized = content.replace("\\n", "\n");
    normalized =
        normalized
            .replaceAll("([.!?])\\s*n\\s*n", "$1\n\n")
            .replaceAll("([.!?])\\s*n\\s*([A-Z\"'`<{(\\[])", "$1\n$2")
            .replaceAll("([a-z])n([A-Z])", "$1\n$2")
            .replaceAll("n(\\s*)([<{(\\[\"'`])", "\n$1$2")
            .replaceAll("n```", "\n```");
    return normalized.split("\n", -1);
  }

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
