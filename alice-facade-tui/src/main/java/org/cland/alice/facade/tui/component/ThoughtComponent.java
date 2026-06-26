package org.cland.alice.facade.tui.component;

import java.util.ArrayList;
import java.util.List;

/**
 * 滚动日志区组件（上方滚动区）。
 *
 * <p>对应 Layout.md §7.1 中三条区域的第一块——"上方滚动区"。 集中展示业务日志、思考/动作/观测流输出，内容正常向上滚动。
 *
 * <p>v2.3 进化亮点：
 *
 * <ul>
 *   <li>废弃 {@code [T Thought]} / {@code [A Action]} / {@code [O Observe]} 文本前缀
 *   <li>替换为 {@link TaoTag} 等宽满背景填充实体矩形标签，消除界面碎屑
 *   <li>用户/系统/Agent 消息保留简化文本前缀
 * </ul>
 *
 * <p>TAO 行输出格式（v2.3）：
 *
 * <pre>
 *   THOUGHT  监测到 uncommitted 悬空状态，自动触发双式记账平衡等式校验。
 *   ACTION   调用本地 Bash 执行器: $ gradle test
 *   OBSERVE  BUILD SUCCESSFUL in 3s (1 test passed)
 * </pre>
 *
 * <p>其中 {@code THOUGHT} / {@code ACTION} / {@code OBSERVE} 为 ANSI 背景色全填充纯色块。
 */
public class ThoughtComponent extends Component {

  private static final int MAX_LINES = 1000;

  /** TAO 行缩进：标签前 2 空格 */
  private static final String TAO_INDENT = "  ";

  /** TAO 行：标签与内容之间的分隔 */
  private static final String TAO_SEPARATOR = "  ";

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

  /**
   * 追加思考片段（v2.3 色块标签）。
   *
   * <p>输出格式：{@code " " + TaoTag.THOUGHT.render() + " " + thought}
   */
  public void addThought(String thought, int step) {
    appendLine(
        TAO_INDENT + TaoTag.THOUGHT.render() + TAO_SEPARATOR + (thought != null ? thought : ""));
  }

  /**
   * 追加动作执行（v2.3 色块标签）。
   *
   * <p>输出格式：{@code " " + TaoTag.ACTION.render() + " " + actionDescription}
   */
  public void addAction(String actionDescription) {
    appendLine(
        TAO_INDENT
            + TaoTag.ACTION.render()
            + TAO_SEPARATOR
            + (actionDescription != null ? actionDescription : ""));
  }

  /**
   * 追加观测反馈（v2.3 色块标签）。
   *
   * <p>输出格式：{@code " " + TaoTag.OBSERVE.render() + " " + observation}
   */
  public void addObservation(String observation) {
    appendLine(
        TAO_INDENT
            + TaoTag.OBSERVE.render()
            + TAO_SEPARATOR
            + (observation != null ? observation : ""));
  }

  /**
   * 将内容按行拆分，处理 {@code \n} 转义序列为实际换行。
   *
   * <p>Agent 响应常包含 {@code \n} 转义序列（例如 JSON 序列化后的文本）， 需要转换为实际换行符后再按行拆分和展示。
   *
   * @return 拆分后的行列表，每行不包含换行符
   */
  private static String[] resolveLines(String content) {
    if (content == null) return new String[0];
    // Step 1: 将字面量 \n (反斜杠+n) 转换为实际换行符
    String normalized = content.replace("\\n", "\n");
    // Step 2: 修复受损 \n（上游处理消耗了反斜杠，留下裸 n）
    // 无 guard 条件，与现有实际换行符同时处理
    normalized =
        normalized
            // 双 n (受损 \n\n) 出现在句末标点后
            .replaceAll("([.!?])\\s*n\\s*n", "$1\n\n")
            // n 出现在 [.!?] 后 + 大写字母/结构字符前
            .replaceAll("([.!?])\\s*n\\s*([A-Z\"'`<{(\\[])", "$1\n$2")
            // 小写字母后 n + 大写字母（如 txtnI → 受损 \n，an Important 不触发）
            .replaceAll("([a-z])n([A-Z])", "$1\n$2")
            // n 后紧跟结构字符 { " < ` [
            .replaceAll("n(\\s*)([<{(\\[\"'`])", "\n$1$2")
            // n 后紧跟反引号（受损的代码块起始）
            .replaceAll("n```", "\n```");
    // Step 3: 按行拆分
    return normalized.split("\n", -1);
  }

  /**
   * 追加用户消息（支持多行内容，自动处理 {@code \n} 转义）。
   *
   * <p>v2.3 净化设计：移除 {@code User:} 角色前缀，直接展示内容，与 TAO 色块行对齐。
   */
  public void addUserMessage(String content) {
    if (content == null) return;
    for (String line : resolveLines(content)) {
      appendLine("  " + line);
    }
  }

  /**
   * 追加系统消息（支持多行内容，自动处理 {@code \n} 转义）。
   *
   * <p>v2.3 净化设计：移除 {@code System:} 角色前缀，直接展示内容，与 TAO 色块行对齐。
   */
  public void addSystemMessage(String content) {
    if (content == null) return;
    for (String line : resolveLines(content)) {
      appendLine("  " + line);
    }
  }

  /**
   * 追加 Agent 消息（支持多行内容，自动处理 {@code \n} 转义）。
   *
   * <p>v2.3 净化设计：移除 {@code Agent:} 角色前缀，直接展示内容，与 TAO 色块行对齐。
   */
  /** Internal agent protocol markers to strip from display */
  private static final java.util.regex.Pattern AGENT_MARKERS =
      java.util.regex.Pattern.compile("\\[FINISH\\]");

  /**
   * 追加 Agent 消息（支持多行内容，自动处理 {@code \n} 转义）。
   *
   * <p>v2.3 净化设计：
   *
   * <ul>
   *   <li>自动剥除内部协议标记（如 {@code [FINISH]}），不污染显示
   *   <li>标记剥除后若无实际内容则不添加空行
   *   <li>处理 {@code \n} 转义序列为实际换行
   * </ul>
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
