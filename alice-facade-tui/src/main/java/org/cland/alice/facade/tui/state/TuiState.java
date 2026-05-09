package org.cland.alice.facade.tui.state;

/**
 * TUI 界面状态机，对应设计文档 §6 状态机描述。
 *
 * <p>状态转换规则：
 *
 * <pre>
 *        +---------+          +----------+          +------------+
 * ------>|  IDLE   |----+---->| INPUTING |----+---->|  RUNNING   |
 *        | (空闲)  |    |     | (输入中) |    |     | (思考执行) |
 *        +----^----+    |     +----------+    |     +-----+------+
 *             |         |                     |           |
 *             |         v                     v           |
 *             |    +---------+          +----------+      |
 *             +----+  ERROR  |<---------+ INTERVENE|<-----+
 *                  | (报错)  |          | (人工干预)|
 *                  +---------+          +----------+
 * </pre>
 */
public final class TuiState {

  /** 界面状态枚举 */
  public enum State {
    IDLE,
    INPUTING,
    RUNNING,
    INTERVENE,
    ERROR
  }

  private volatile State current;

  public TuiState() {
    this.current = State.IDLE;
  }

  public TuiState(State initial) {
    this.current = initial;
  }

  /** 获取当前状态 */
  public State current() {
    return current;
  }

  /** 判断当前状态是否可接受键盘输入 */
  public boolean isInputable() {
    return current == State.IDLE || current == State.INPUTING;
  }

  /** 判断是否正在运行 Agent 任务 */
  public boolean isRunning() {
    return current == State.RUNNING;
  }

  /** 判断是否处于错误状态 */
  public boolean isError() {
    return current == State.ERROR;
  }

  /** 状态转换，违反规则时抛出 IllegalStateException。 */
  public synchronized void transitionTo(State target) {
    if (!canTransition(current, target)) {
      throw new IllegalStateException("Invalid TUI state transition: " + current + " -> " + target);
    }
    this.current = target;
  }

  private static boolean canTransition(State from, State to) {
    return switch (from) {
      case IDLE -> to == State.INPUTING || to == State.ERROR;
      case INPUTING -> to == State.RUNNING || to == State.IDLE || to == State.ERROR;
      case RUNNING -> to == State.IDLE || to == State.INTERVENE || to == State.ERROR;
      case INTERVENE -> to == State.RUNNING || to == State.IDLE || to == State.ERROR;
      case ERROR -> to == State.IDLE;
    };
  }

  @Override
  public String toString() {
    return "TuiState{" + current + "}";
  }
}
