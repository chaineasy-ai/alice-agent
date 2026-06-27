package org.cland.alice.facade.tui.component;

/**
 * TUI 组件抽象基类。
 *
 * <p>配合 JLine 3 三层单线分割布局（TAO Standard Mode），所有组件继承自此基类。 组件不再依赖 Lanterna TextGraphics，而是通过 {@link
 * #render()} 返回渲染后的行文本列表， 由 {@link org.cland.alice.facade.tui.ScreenManager} 统一输出到终端。
 *
 * <p>参考 docs/alice-facade-tui/Layout.md
 */
public abstract class Component {

  /** 组件名称，用于日志/调试 */
  protected final String name;

  /** 组件在屏幕上的位置与大小 */
  protected volatile int row;

  protected volatile int col;
  protected volatile int width;
  protected volatile int height;

  /** 是否可见 */
  protected volatile boolean visible;

  /** 是否需要重绘 */
  protected volatile boolean dirty;

  protected Component(String name) {
    this.name = name;
    this.visible = true;
    this.dirty = true;
  }

  // ========== 抽象方法 ==========

  /**
   * 渲染组件内容为行文本列表。 每行字符串即为该行应在终端对应位置输出的内容（不含光标定位）。
   *
   * @return 组件内容的行列表，长度不应超过 height
   */
  public abstract java.util.List<String> render();

  /**
   * 将组件内容渲染到终端 Writer 的指定位置。
   *
   * <p>每个组件根据自身 {@link #row} 和 {@link #height} 决定输出行号， 并在每行后附加 {@code \033[K}（清除行尾）避免残留字符。
   *
   * @param writer 终端 Writer 实例
   */
  public void renderTo(java.io.Writer writer) throws java.io.IOException {
    if (!visible || width <= 0 || height <= 0) {
      clearDirty();
      return;
    }
    clearDirty();
    java.util.List<String> lines = render();
    // row 是 0-indexed，\033[%d;1H 是 1-indexed
    for (int i = 0; i < lines.size(); i++) {
      writer.write(String.format("\033[%d;1H%s\033[K", row + i + 1, lines.get(i)));
    }
  }

  // ========== 布局管理 ==========

  public void setBounds(int row, int col, int width, int height) {
    this.row = row;
    this.col = col;
    this.width = width;
    this.height = height;
    markDirty();
  }

  public void setPosition(int row, int col) {
    this.row = row;
    this.col = col;
    markDirty();
  }

  public void setSize(int width, int height) {
    this.width = width;
    this.height = height;
    markDirty();
  }

  // ========== 位置与大小查询 ==========

  public int row() {
    return row;
  }

  public int col() {
    return col;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  // ========== 可见性 ==========

  public boolean isVisible() {
    return visible;
  }

  public void setVisible(boolean v) {
    this.visible = v;
    markDirty();
  }

  public void show() {
    setVisible(true);
  }

  public void hide() {
    setVisible(false);
  }

  // ========== 脏标记 ==========

  public boolean isDirty() {
    return dirty;
  }

  public void markDirty() {
    this.dirty = true;
  }

  public void clearDirty() {
    this.dirty = false;
  }

  @Override
  public String toString() {
    return "Component{"
        + name
        + ", pos=("
        + row
        + ","
        + col
        + "), size="
        + width
        + "x"
        + height
        + ", visible="
        + visible
        + "}";
  }
}
