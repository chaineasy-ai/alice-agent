package org.cland.alice.facade.tui.component;

import java.util.List;

/**
 * 输入区域组件（中间输入区）。
 *
 * <p>对应 Layout.md §7.1 中三条区域的第二块——"中间输入区"。 被两条分割线包裹，输入提示符固定居中。
 *
 * <p>实际终端 I/O 由 JLine 3 的 LineReader 处理（支持 AUTO_MENU 向上补全弹窗）， 本组件仅维护输入缓冲区模型供 {@link
 * org.cland.alice.facade.tui.ScreenManager} 渲染参考。
 */
public class InputComponent extends Component {

  private static final int MAX_INPUT_LENGTH = 500;

  private final StringBuilder inputBuffer;
  private int cursorPos;
  private String prompt;

  public InputComponent() {
    super("Input");
    this.inputBuffer = new StringBuilder();
    this.cursorPos = 0;
    this.prompt = " > ";
  }

  public void setPrompt(String prompt) {
    this.prompt = prompt;
    markDirty();
  }

  public String prompt() {
    return prompt;
  }

  // ========== 输入管理 ==========

  public void insertChar(char ch) {
    if (inputBuffer.length() < MAX_INPUT_LENGTH) {
      inputBuffer.insert(cursorPos, ch);
      cursorPos++;
      markDirty();
    }
  }

  public void deleteBeforeCursor() {
    if (cursorPos > 0) {
      inputBuffer.deleteCharAt(cursorPos - 1);
      cursorPos--;
      markDirty();
    }
  }

  public void deleteAtCursor() {
    if (cursorPos < inputBuffer.length()) {
      inputBuffer.deleteCharAt(cursorPos);
      markDirty();
    }
  }

  public void cursorLeft() {
    if (cursorPos > 0) {
      cursorPos--;
      markDirty();
    }
  }

  public void cursorRight() {
    if (cursorPos < inputBuffer.length()) {
      cursorPos++;
      markDirty();
    }
  }

  public void cursorHome() {
    cursorPos = 0;
    markDirty();
  }

  public void cursorEnd() {
    cursorPos = inputBuffer.length();
    markDirty();
  }

  /** 获取当前输入文本并清空 */
  public String commitInput() {
    String text = inputBuffer.toString();
    inputBuffer.setLength(0);
    cursorPos = 0;
    markDirty();
    return text;
  }

  /** 清空输入 */
  public void clear() {
    inputBuffer.setLength(0);
    cursorPos = 0;
    markDirty();
  }

  /** 设置输入内容（用于历史回填） */
  public void setText(String text) {
    inputBuffer.setLength(0);
    inputBuffer.append(text);
    cursorPos = text.length();
    markDirty();
  }

  public String getText() {
    return inputBuffer.toString();
  }

  public int cursorPos() {
    return cursorPos;
  }

  // ========== 渲染 ==========

  @Override
  public List<String> render() {
    if (!visible || width <= 0 || height <= 0) {
      return List.of();
    }
    clearDirty();

    // 格式： > /_module_command_here
    // prompt 左对齐，输入文本紧随其后
    String display = prompt + inputBuffer.toString();

    StringBuilder sb = new StringBuilder(width);
    if (display.length() > width) {
      sb.append(display, display.length() - width, display.length());
    } else {
      sb.append(display);
      sb.append(" ".repeat(width - display.length()));
    }

    return List.of(sb.toString());
  }
}
