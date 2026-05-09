package org.cland.alice.facade.tui.component;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;

/**
 * 输入框组件。
 *
 * <p>支持文本输入、光标显示、历史浏览。 边框由 {@link org.cland.alice.facade.tui.ScreenManager} 统一绘制， 本组件只负责绘制输入内容和光标。
 */
public class InputComponent extends Component {

  private static final int MAX_INPUT_LENGTH = 500;

  private final StringBuilder inputBuffer;
  private int cursorPos;
  private boolean focused;

  public InputComponent() {
    super("Input");
    this.inputBuffer = new StringBuilder();
    this.cursorPos = 0;
    this.focused = false;
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

  // ========== 焦点 ==========

  public void setFocused(boolean focused) {
    this.focused = focused;
    markDirty();
  }

  public boolean isFocused() {
    return focused;
  }

  // ========== 绘制 ==========

  @Override
  public void draw(TextGraphics g) {
    if (!visible || width <= 0 || height <= 0) return;

    // 填充背景
    for (int r = 0; r < height; r++) {
      for (int c = 0; c < width; c++) {
        g.setCharacter(col + c, row + r, ' ');
      }
    }

    // 在最左侧显示提示符
    String prompt = focused ? "> " : "  ";
    g.setForegroundColor(TextColor.ANSI.GREEN);
    for (int i = 0; i < prompt.length() && i < width; i++) {
      g.setCharacter(col + i, row, prompt.charAt(i));
    }

    // 输入文本
    int promptLen = prompt.length();
    int inputAreaWidth = width - promptLen;

    g.setForegroundColor(TextColor.ANSI.WHITE);
    String displayText = inputBuffer.toString();
    if (displayText.length() > inputAreaWidth) {
      displayText = displayText.substring(displayText.length() - inputAreaWidth);
    }
    for (int i = 0; i < displayText.length() && i < inputAreaWidth; i++) {
      g.setCharacter(col + promptLen + i, row, displayText.charAt(i));
    }

    // 绘制光标（在 focused 时显示）
    if (focused) {
      int cursorDisplayPos = Math.min(cursorPos, inputAreaWidth - 1);
      if (cursorDisplayPos >= 0) {
        int cursorX = col + promptLen + cursorDisplayPos;
        if (cursorX < col + width) {
          g.setBackgroundColor(TextColor.ANSI.WHITE);
          g.setForegroundColor(TextColor.ANSI.BLACK);
          char cursorChar = cursorPos < inputBuffer.length() ? inputBuffer.charAt(cursorPos) : ' ';
          g.setCharacter(cursorX, row, cursorChar);
        }
      }
    }

    clearDirty();
  }
}
