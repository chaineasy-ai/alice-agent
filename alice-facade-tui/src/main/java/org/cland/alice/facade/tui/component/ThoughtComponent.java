package org.cland.alice.facade.tui.component;

import java.util.ArrayList;
import java.util.List;

/**
 * 滚动日志区组件（上方滚动区）。
 *
 * <p>对应 Layout.md §7.1 中三条区域的第一块——"上方滚动区"。 集中展示业务日志、思考/动作/观测流输出，内容正常向上滚动。
 *
 * <p>前缀规范（参考 Layout.md 示例）：
 *
 * <ul>
 *   <li>[T Thought]: 思考过程
 *   <li>[A Action ]: 动作执行
 *   <li>[O Observe]: 观测反馈
 *   <li>[User]: 用户消息
 *   <li>[System]: 系统消息
 * </ul>
 */
public class ThoughtComponent extends Component {

  private static final int MAX_LINES = 1000;

  private final List<String> logLines;
  private int scrollOffset;

  public ThoughtComponent() {
    super("Thought");
    this.logLines = new ArrayList<>();
    this.scrollOffset = 0;
  }

  // ========== 日志管理 ==========

  /** 追加原始日志行 */
  public void appendLine(String line) {
    if (line == null) return;
    if (logLines.size() >= MAX_LINES) {
      logLines.remove(0);
    }
    logLines.add(line);
    scrollToBottom();
    markDirty();
  }

  /** 追加思考片段：[T Thought] */
  public void addThought(String thought, int step) {
    appendLine("[T Thought]: " + thought);
  }

  /** 追加动作执行：[A Action] */
  public void addAction(String actionDescription) {
    appendLine("[A Action ]: " + actionDescription);
  }

  /** 追加观测反馈：[O Observe] */
  public void addObservation(String observation) {
    appendLine("[O Observe]: " + observation);
  }

  /** 追加用户消息（支持多行内容，自动逐行加前缀） */
  public void addUserMessage(String content) {
    if (content == null) return;
    String[] lines = content.split("\n", -1);
    for (String line : lines) {
      appendLine("[User]: " + line);
    }
  }

  /** 追加系统消息（支持多行内容，自动逐行加前缀） */
  public void addSystemMessage(String content) {
    if (content == null) return;
    String[] lines = content.split("\n", -1);
    for (String line : lines) {
      appendLine("[System]: " + line);
    }
  }

  /** 追加 Agent 消息（支持多行内容，自动逐行加前缀） */
  public void addAgentMessage(String content) {
    if (content == null) return;
    String[] lines = content.split("\n", -1);
    for (String line : lines) {
      appendLine("[Agent]: " + line);
    }
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
      if (raw.length() > width) {
        raw = raw.substring(0, width);
      }
      result.add(raw);
    }

    // 填充剩余行为空行
    while (result.size() < height) {
      result.add("");
    }

    return result;
  }
}
