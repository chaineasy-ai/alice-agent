package org.cland.alice.memory.dreaming;

/**
 * WalSession 生命周期状态枚举。
 *
 * <p>状态机:
 *
 * <pre>
 *   CREATED → RUNNING → COMPLETED
 *                     → CRASHED
 *   COMPLETED → DREAMING → ARCHIVED
 *   CRASHED   → DREAMING → ARCHIVED
 *   DREAMING  → COMPLETED (失败回退)
 *   DREAMING  → CRASHED   (失败回退)
 *   COMPLETED → ARCHIVED (重放检测跳过)
 * </pre>
 */
public enum SessionState {
  /** 会话已创建，尚未开始执行 */
  CREATED,
  /** 会话正在执行中（在线 ReAct 循环） */
  RUNNING,
  /** 会话正常完成 */
  COMPLETED,
  /** 会话崩溃或异常结束 */
  CRASHED,
  /** 会话正在被 Dreaming 处理中 */
  DREAMING,
  /** 会话已被 Dreaming 处理完毕，归档只读 */
  ARCHIVED
}
