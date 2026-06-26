package org.cland.alice.facade.tui.component;

/**
 * TAO 标签色块枚举。
 *
 * <p>对应 Layout.md §4.1 色块枚举核心实现。使用 ANSI 256 色背景控制码 {@code 48;5;xxxm} 生成满宽填充矩形色块，替代传统 {@code [T
 * Thought]} 文本前缀。
 *
 * <p>色块设计（v2.3 终极工业版）：
 *
 * <table border="1">
 *   <caption>TAO 色块配色方案</caption>
 *   <tr><th>标签</th><th>背景色</th><th>ANSI 码</th><th>文字色</th></tr>
 *   <tr><td>THOUGHT</td><td>暗灰</td><td>48;5;239</td><td>白色 (37)</td></tr>
 *   <tr><td>ACTION</td><td>橙黄</td><td>48;5;214</td><td>黑色 (30)</td></tr>
 *   <tr><td>OBSERVE</td><td>绿色</td><td>48;5;35</td><td>黑色 (30)</td></tr>
 * </table>
 *
 * <p>所有标签文本固定 9 字符宽度（含前后空格），确保等宽对齐。
 */
public enum TaoTag {

  /** 思考过程：暗灰底(239) + 白色文字(37) */
  THOUGHT(" THOUGHT ", "\u001B[48;5;239m", "\u001B[37m"),
  /** 动作执行：橙黄底(214) + 黑色文字(30) */
  ACTION(" ACTION  ", "\u001B[48;5;214m", "\u001B[30m"),
  /** 观测反馈：绿色底(35) + 黑色文字(30) */
  OBSERVE(" OBSERVE ", "\u001B[48;5;35m", "\u001B[30m");

  private static final String ANSI_RESET = "\u001B[0m";

  private final String text;
  private final String bgAnsi;
  private final String fgAnsi;

  TaoTag(String text, String bgAnsi, String fgAnsi) {
    this.text = text;
    this.bgAnsi = bgAnsi;
    this.fgAnsi = fgAnsi;
  }

  /** 输出带背景色块的完整 ANSI 文本。例如 {@code \033[48;5;239m\033[37m THOUGHT \033[0m} */
  public String render() {
    return bgAnsi + fgAnsi + text + ANSI_RESET;
  }

  /** 纯文本标签（不含 ANSI 码，用于宽度计算）。 */
  public String plainText() {
    return text;
  }
}
