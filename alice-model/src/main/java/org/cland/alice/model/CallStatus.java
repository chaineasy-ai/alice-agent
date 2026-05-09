package org.cland.alice.model;

/**
 * Call 对象的生命周期状态，对应设计文档中的状态机。
 *
 * <pre>
 *        +---------+          +---------+          +------------+
 * ------>| CREATED |--------->| PENDING |--------->|  RUNNING   |
 *        +---------+          +---------+          +------------+
 *            |                     |                     |
 *            | (Validation Fail)   | (Network Timeout)   | (Stream Error)
 *            v                     v                     v
 *        +---------+          +---------+          +------------+
 *        | ABORTED |          |  RETRY  |          |   FAILED   |
 *        +---------+          +---------+          +------------+
 *                                  |                     |
 *                                  +----------+----------+
 *                                             |
 *                                             v
 *                                      +------------+
 *                                      |  FINISHED  | (Success / Terminal Fail)
 *                                      +------------+
 * </pre>
 */
public enum CallStatus {
  /** 对象已初始化，参数已校验 */
  CREATED,
  /** 进入调度队列，等待供应商槽位 */
  PENDING,
  /** 正在进行网络请求或流式读取 */
  RUNNING,
  /** 验证失败终止 */
  ABORTED,
  /** 网络超时，可重试 */
  RETRY,
  /** 流式错误等不可恢复错误 */
  FAILED,
  /** 最终态，已记录 Token 消耗并返回 */
  FINISHED;

  /** 判断当前状态是否可以转换到目标状态。 */
  public boolean canTransitionTo(CallStatus target) {
    return switch (this) {
      case CREATED -> target == PENDING || target == ABORTED;
      case PENDING -> target == RUNNING || target == RETRY || target == ABORTED;
      case RUNNING -> target == FINISHED || target == FAILED || target == RETRY;
      case RETRY -> target == PENDING || target == FAILED;
      case ABORTED, FAILED, FINISHED -> false; // 终态不可转换
    };
  }
}
