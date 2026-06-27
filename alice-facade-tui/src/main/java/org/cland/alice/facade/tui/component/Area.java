package org.cland.alice.facade.tui.component;

import org.jline.utils.AttributedStringBuilder;

/**
 * 矩形区域基类 — 支持子组件组合渲染。
 *
 * <p>提供宽度/高度管理及 {@link #render(AttributedStringBuilder)} 抽象方法， 子类可通过 {@code super.render(buf)}
 * 调用父类渲染管线。
 */
public abstract class Area {

  protected int width;
  protected int height;

  public Area() {
    this(0, 0);
  }

  public Area(int width, int height) {
    this.width = width;
    this.height = height;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  public void setSize(int width, int height) {
    this.width = width;
    this.height = height;
  }

  /**
   * 将当前区域内容渲染到 {@link AttributedStringBuilder} 中。
   *
   * @param buf 目标缓冲区
   */
  public abstract void render(AttributedStringBuilder buf);
}
