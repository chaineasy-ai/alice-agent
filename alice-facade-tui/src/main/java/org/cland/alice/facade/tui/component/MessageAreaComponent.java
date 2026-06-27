package org.cland.alice.facade.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Unified scrollable message area component — 3-zone layout main area (v4.0).
 *
 * <p>All messages use plain ANSI strings for styling (same approach as FooterComponent). No
 * per-line background codes — all messages render on the terminal's default background.
 */
public class MessageAreaComponent extends Component {

  private static final int MAX_LINES = 2000;

  // ── Foreground codes ──────────────────────────────────────────────

  private static final String FG_LIGHT = "\u001B[37m";
  private static final String FG_DIM = "\u001B[38;5;242m";
  private static final String FG_YELLOW = "\u001B[38;5;222m";
  private static final String FG_LIGHT_GRAY = "\u001B[38;5;252m";
  private static final String FG_TIME = "\u001B[38;5;246m";
  private static final String ANSI_RESET = "\u001B[0m";
  private static final String INDENT = "  ";

  /** Agent internal protocol markers to strip */
  private static final Pattern AGENT_MARKERS = Pattern.compile("\\[FINISH\\]");

  /** Action command prefix pattern (skip duplicate display in observation) */
  private static final Pattern ACTION_CMD_PREFIX = Pattern.compile("^\\s*\\$\\s+\\S+.*");

  // ── Message line storage (plain ANSI strings, same as FooterComponent) ──

  private static final class MessageLine {
    final String content; // Full ANSI-styled content
    final String bgCode; // ANSI background code (footer-style "\u001B[48;5;XXXm")

    MessageLine(String content, String bgCode) {
      this.content = content;
      this.bgCode = bgCode;
    }

    String toAnsiLine() {
      return bgCode + content;
    }
  }

  private final List<MessageLine> logLines;
  private int scrollOffset;

  public MessageAreaComponent() {
    super("MessageArea");
    this.logLines = new ArrayList<>();
    this.scrollOffset = 0;
  }

  // ==================================================================
  // Public API — adding messages
  // ==================================================================

  /** Append a user message. */
  public void addUserMessage(String content) {
    if (content == null) return;
    for (String line : resolveLines(content)) {
      appendLine(INDENT + line, "");
    }
  }

  /** Append a thought/reasoning step. */
  public void addThought(String thought, int step) {
    addThought(thought, step, null);
  }

  /** Append a thought/reasoning step with optional traceId. */
  public void addThought(String thought, int step, String traceId) {
    if (thought == null) return;
    if (step > 0) {
      var sb = new StringBuilder();
      sb.append(INDENT).append(FG_LIGHT_GRAY).append("\u2508 Step ").append(step);
      if (traceId != null && !traceId.isBlank()) {
        String shortTrace = traceId.length() > 8 ? traceId.substring(0, 8) : traceId;
        sb.append(" [").append(shortTrace).append("]");
      }
      sb.append(" \u2508").append(ANSI_RESET);
      appendLine(sb.toString(), "");
    }
    for (String line : resolveLines(thought)) {
      appendLine(FG_LIGHT_GRAY + INDENT + line + ANSI_RESET, "");
    }
  }

  /** Append an action execution line. */
  public void addActionLine(String desc) {
    addActionLine(desc, null);
  }

  /** Append an action execution line with optional traceId. */
  public void addActionLine(String desc, String traceId) {
    if (desc == null || desc.isBlank()) return;
    String line = INDENT + FG_LIGHT + "\u25AE " + desc + ANSI_RESET;
    appendLine(line, "");
  }

  /** Append an observation result line. */
  public void addObservationLine(String observation, double elapsedSec) {
    if (observation == null || observation.isBlank()) return;
    String[] lines = observation.split("\n", -1);
    int printed = 0;
    int totalLines = 0;
    for (String line : lines) {
      if (line.isBlank() || ACTION_CMD_PREFIX.matcher(line).matches()) continue;
      totalLines++;
      if (printed >= 3) continue;

      String styledLine;
      if (line.matches("^[dl-][rwxst-]{9}.*")) {
        styledLine = INDENT + FG_YELLOW + line + ANSI_RESET;
      } else {
        styledLine = INDENT + line;
      }
      appendLine(FG_LIGHT + " " + styledLine, "");
      printed++;
    }
    if (totalLines > 3) {
      appendLine(
          FG_LIGHT
              + " "
              + INDENT
              + FG_DIM
              + "... ("
              + (totalLines - 3)
              + " more lines)"
              + ANSI_RESET,
          "");
    }
    appendLine(
        INDENT + "  " + FG_TIME + "(Took " + String.format("%.1f", elapsedSec) + "s)" + ANSI_RESET,
        "");
  }

  /** Append a system message. */
  public void addSystemMessage(String content) {
    if (content == null) return;
    for (String line : resolveLines(content)) {
      appendLine(INDENT + line, "");
    }
  }

  /** Append an agent message (strips internal markers like [FINISH]). */
  public void addAgentMessage(String content) {
    if (content == null) return;
    String clean = AGENT_MARKERS.matcher(content).replaceAll("").trim();
    if (clean.isEmpty()) return;
    for (String line : resolveLines(clean)) {
      appendLine(INDENT + line, "");
    }
  }

  /** Add a raw line (for migration compatibility). */
  public void appendRaw(String line, String bgCode) {
    appendLine(line, bgCode);
  }

  // ==================================================================
  // Internal storage
  // ==================================================================

  private void appendLine(String content, String bgCode) {
    if (logLines.size() >= MAX_LINES) {
      logLines.remove(0);
    }
    String safeContent = content;
    if (!safeContent.endsWith(ANSI_RESET)) {
      safeContent = safeContent + ANSI_RESET;
    }
    logLines.add(new MessageLine(safeContent, bgCode));
    scrollToBottom();
    markDirty();
  }

  /** Clear all messages. */
  public void clear() {
    logLines.clear();
    scrollOffset = 0;
    markDirty();
  }

  // ==================================================================
  // Scrolling
  // ==================================================================

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

  /** Adjust scroll offset when component height changes. */
  public void onResize(int oldHeight) {
    if (oldHeight <= 0) return;
    int delta = height - oldHeight;
    if (delta == 0) return;
    scrollOffset = Math.clamp(scrollOffset - delta, 0, Math.max(0, logLines.size() - height));
    markDirty();
  }

  // ==================================================================
  // Rendering
  // ==================================================================

  @Override
  public List<String> render() {
    if (!visible || width <= 0 || height <= 0) {
      clearDirty();
      return List.of();
    }
    if (logLines.isEmpty()) {
      clearDirty();
      List<String> empty = new ArrayList<>(height);
      String emptyLine = " ".repeat(width);
      for (int i = 0; i < height; i++) {
        empty.add(emptyLine);
      }
      return empty;
    }
    clearDirty();

    List<String> result = new ArrayList<>(height);
    int startIdx = Math.min(scrollOffset, Math.max(0, logLines.size() - height));
    int endIdx = Math.min(startIdx + height, logLines.size());

    for (int i = startIdx; i < endIdx; i++) {
      MessageLine ml = logLines.get(i);
      String raw = ml.toAnsiLine();
      if (visibleWidth(raw) > width) {
        raw = truncateWithAnsi(raw, width);
      }
      raw = padWithSpaces(raw, width);
      result.add(raw);
    }

    // Fill remaining rows (no background)
    String emptyLine = " ".repeat(width);
    while (result.size() < height) {
      result.add(emptyLine);
    }

    return result;
  }

  // ==================================================================
  // ANSI helpers
  // ==================================================================

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
