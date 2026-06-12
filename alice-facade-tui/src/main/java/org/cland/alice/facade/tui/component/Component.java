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
