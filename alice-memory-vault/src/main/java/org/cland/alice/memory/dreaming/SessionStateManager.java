package org.cland.alice.memory.dreaming;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.cland.alice.core.agent.wal.WalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话状态管理器 — 使用内部 ConcurrentMap 跟踪 WalSession 生命周期状态。
 *
 * <p>不修改 WalStore 接口以保持向后兼容性。 状态转换遵循 data-model.md 中定义的状态机规则。
 */
public final class SessionStateManager {

  private static final Logger log = LoggerFactory.getLogger(SessionStateManager.class);

  private final ConcurrentMap<String, SessionState> stateMap = new ConcurrentHashMap<>();

  /**
   * @param walStore WAL 存储层（状态管理器仅使用 session ID，walStore 用于获取活跃会话）
   */
  public SessionStateManager(WalStore walStore) {
    Objects.requireNonNull(walStore, "walStore must not be null");
    // 不需要存储 walStore 引用 — 状态管理器只使用自己的 ConcurrentMap
  }

  /**
   * 获取指定会话的当前状态。如果不存在，返回 CREATED（默认初始状态）。
   *
   * @param sessionId 会话 ID
   * @return 当前状态
   */
  public SessionState getState(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    return stateMap.getOrDefault(sessionId, SessionState.CREATED);
  }

  /**
   * 尝试将会话从 {@code from} 状态转换到 {@code to} 状态。 线程安全：使用 ConcurrentHashMap.replace() 实现 CAS。
   *
   * @param sessionId 会话 ID
   * @param from 当前状态（预期）
   * @param to 目标状态
   * @return 如果转换成功返回 true
   * @throws StateTransitionException 如果转换无效
   */
  public boolean transition(String sessionId, SessionState from, SessionState to) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");

    // 验证转换是否合法
    if (!isValidTransition(from, to)) {
      StateTransitionException ex = new StateTransitionException(sessionId, from, to);
      log.warn(
          "[SessionStateManager] Invalid transition: {} -> {} for session={}", from, to, sessionId);
      throw ex;
    }

    // CAS: 使用从参数传入的期望当前状态值做原子替换
    // 如果 sessionId 不存在，相当于从 CREATED 转换
    SessionState current = stateMap.get(sessionId);
    if (current == null) {
      // 初始状态默认为 CREATED
      if (from == SessionState.CREATED) {
        stateMap.put(sessionId, to);
        log.debug("[SessionStateManager] Session={}: {} -> {}", sessionId, from, to);
        return true;
      }
      // 如果当前为 null 但 from != CREATED，转换失败
      return false;
    }

    // 使用预期的 from 值做原子 CAS — 防止并发竞态
    boolean replaced = stateMap.replace(sessionId, from, to);
    if (replaced) {
      log.debug("[SessionStateManager] Session={}: {} -> {}", sessionId, from, to);
      // Notify on state change
      stateChanged(sessionId, from, to);
    } else {
      log.trace(
          "[SessionStateManager] Session={}: CAS failed for {} -> {} (current={})",
          sessionId,
          from,
          to,
          stateMap.get(sessionId));
    }
    return replaced;
  }

  /**
   * 检查会话是否可以被 Dreaming 处理（状态为 COMPLETED 或 CRASHED）。
   *
   * @param sessionId 会话 ID
   * @return 如果可处理返回 true
   */
  public boolean isDreamable(String sessionId) {
    SessionState state = getState(sessionId);
    return state == SessionState.COMPLETED || state == SessionState.CRASHED;
  }

  /**
   * 原子地将会话标记为 DREAMING 状态。 只有当当前状态为 COMPLETED 或 CRASHED 时成功。
   *
   * @param sessionId 会话 ID
   * @return 如果成功锁定返回 true（另一个线程将返回 false）
   */
  public boolean tryLockForDreaming(String sessionId) {
    // 使用 CAS 直接进行原子替换 — 这是线程安全的并发锁定
    if (stateMap.replace(sessionId, SessionState.COMPLETED, SessionState.DREAMING)) {
      log.debug("[SessionStateManager] Session={}: COMPLETED -> DREAMING", sessionId);
      return true;
    }
    if (stateMap.replace(sessionId, SessionState.CRASHED, SessionState.DREAMING)) {
      log.debug("[SessionStateManager] Session={}: CRASHED -> DREAMING", sessionId);
      return true;
    }
    return false;
  }

  /**
   * 设置会话的初始状态。用于在创建会话时初始化状态。
   *
   * @param sessionId 会话 ID
   * @param initialState 初始状态
   */
  public void setInitialState(String sessionId, SessionState initialState) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(initialState, "initialState must not be null");
    stateMap.putIfAbsent(sessionId, initialState);
    log.debug("[SessionStateManager] Initialized session={} as {}", sessionId, initialState);
  }

  // ============================================================
  // State Machine Validation
  // ============================================================

  /** 验证从 {@code from} 到 {@code to} 的转换是否合法。 遵循 data-model.md 中定义的状态机规则。 */
  static boolean isValidTransition(SessionState from, SessionState to) {
    return switch (from) {
      case CREATED -> to == SessionState.RUNNING;
      case RUNNING -> to == SessionState.COMPLETED || to == SessionState.CRASHED;
      case COMPLETED -> to == SessionState.DREAMING || to == SessionState.ARCHIVED;
      case CRASHED -> to == SessionState.DREAMING;
      case DREAMING ->
          to == SessionState.ARCHIVED || to == SessionState.COMPLETED || to == SessionState.CRASHED;
      case ARCHIVED -> false; // 归档后不可变
    };
  }

  // ============================================================
  // Hook: State Change Notification
  // ============================================================

  /** 状态变更回调（可被子类重写或用于日志/监控）。 */
  void stateChanged(String sessionId, SessionState from, SessionState to) {
    // 默认实现仅做日志 — 子类可扩展为发布事件
  }
}
