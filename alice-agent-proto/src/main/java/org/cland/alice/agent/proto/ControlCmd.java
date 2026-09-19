/*
 * Alice Agent — Control Commands（控制与反馈）
 *
 * 对应 docs/app/AgentCommand.md 中的 ControlCmd 分支：
 *   ResetSessionCmd   — /new       （重置会话）
 *   FeedbackCmd       — /feedback  （人类在环响应）
 *   InterruptCmd      — Ctrl+C     （强制终止）
 *   ClearContextCmd   — /clear     （清除上下文）
 *   ViewContextCmd    — /context   （查看上下文）
 *   CompactContextCmd — /compact   （压缩上下文）
 *   ResumeSessionCmd  — /resume    （继续历史会话）
 *   SteerCmd          — /steer     （人工插话·忙时插队，2026-09-19）
 *   AbortCmd          — /abort     （中止当前轮·会话保留，2026-09-19）
 */
package org.cland.alice.agent.proto;

import java.time.Instant;
import java.util.Objects;

/**
 * 控制与反馈指令 — 生命周期、HITL（Human-In-The-Loop）与上下文管理。
 *
 * <p>继承自 {@link AgentCommand}，密封许可给 {@link ResetSessionCmd}、{@link FeedbackCmd}、 {@link
 * InterruptCmd}、{@link ClearContextCmd}、{@link ViewContextCmd}、{@link CompactContextCmd}、{@link
 * ResumeSessionCmd}、{@link SteerCmd}、{@link AbortCmd}。
 *
 * <p><b>终止类语义区分</b>：{@link InterruptCmd}（Ctrl+C / {@code /exit}）= 退出进程；{@link AbortCmd} （{@code
 * /abort}）= 只中止当前轮，**会话保留**（上下文/记忆/排期不变），随后可直接继续下一轮。
 */
public sealed interface ControlCmd extends AgentCommand {

  /** 控制操作说明或原因 */
  String reason();

  // ──────────────────────────────────────────────────────────────────────────
  // /new — 重置会话
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 重置会话指令 {@code /new}。
   *
   * <p>清空上下文，开启新对话。可选保留部分配置（如模型选择）。
   */
  record ResetSessionCmd(String sessionId, String traceId, Instant timestamp)
      implements ControlCmd {

    public ResetSessionCmd {
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public ResetSessionCmd(String sessionId, String traceId) {
      this(sessionId, traceId, Instant.now());
    }

    @Override
    public String reason() {
      return "reset-session";
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // /feedback — 人类在环响应
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 人类反馈指令 {@code /feedback}。
   *
   * <p>响应内核的 AskHumanCmd，解锁挂起状态。用户可提供指导或修正。
   *
   * @param message 用户的反馈内容
   */
  record FeedbackCmd(String message, String sessionId, String traceId, Instant timestamp)
      implements ControlCmd {

    public FeedbackCmd {
      Objects.requireNonNull(message, "message must not be null");
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public FeedbackCmd(String message, String sessionId, String traceId) {
      this(message, sessionId, traceId, Instant.now());
    }

    @Override
    public String reason() {
      return "human-feedback: " + message;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Ctrl+C / /exit — 强制终止
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 中断/终止指令（Ctrl+C 或 {@code /exit}）。
   *
   * <p>强制终止当前操作，可选携带原因说明。
   *
   * @param cause 中断原因描述
   */
  record InterruptCmd(String cause, String sessionId, String traceId, Instant timestamp)
      implements ControlCmd {

    public InterruptCmd {
      Objects.requireNonNull(cause, "cause must not be null");
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public InterruptCmd(String cause, String sessionId, String traceId) {
      this(cause, sessionId, traceId, Instant.now());
    }

    @Override
    public String reason() {
      return "interrupt: " + cause;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // /clear — 清除上下文
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 清除上下文指令 {@code /clear}。
   *
   * <p>显式清空当前 Session 的 M (Memory) 缓存（保留 System Prompt/Rules），重置 Token 计数器。
   */
  record ClearContextCmd(String sessionId, String traceId, Instant timestamp)
      implements ControlCmd {

    public ClearContextCmd {
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public ClearContextCmd(String sessionId, String traceId) {
      this(sessionId, traceId, Instant.now());
    }

    @Override
    public String reason() {
      return "clear-context";
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // /context — 查看上下文
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 查看上下文指令 {@code /context}。
   *
   * <p>从 M (Memory) 中拉取当前全量滑动窗口内的线索、对话历史及 Token 占用统计，并格式化输出。
   */
  record ViewContextCmd(String sessionId, String traceId, Instant timestamp) implements ControlCmd {

    public ViewContextCmd {
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public ViewContextCmd(String sessionId, String traceId) {
      this(sessionId, traceId, Instant.now());
    }

    @Override
    public String reason() {
      return "view-context";
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // /compact — 压缩上下文
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 压缩上下文指令 {@code /compact}。
   *
   * <p>强制触发 M (Memory) 总结机制，通过 LLM 将历史对话提炼为 Summary 事实快照，释放 Context Window。
   */
  record CompactContextCmd(String sessionId, String traceId, Instant timestamp)
      implements ControlCmd {

    public CompactContextCmd {
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public CompactContextCmd(String sessionId, String traceId) {
      this(sessionId, traceId, Instant.now());
    }

    @Override
    public String reason() {
      return "compact-context";
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // /resume — 继续历史会话
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 继续历史会话指令 {@code /resume}。
   *
   * <p>从持久化存储（WAL/Snapshot）中加载指定历史会话，重建上下文窗口、短期记忆与关联的快照/分支状态。
   *
   * @param sessionId 会话 ID
   * @param traceId 链路追踪 ID
   * @param snapshotId 快照 ID（可选，指定后从特定快照恢复）
   * @param timestamp 指令发起时间戳
   */
  record ResumeSessionCmd(String sessionId, String traceId, String snapshotId, Instant timestamp)
      implements ControlCmd {

    public ResumeSessionCmd {
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public ResumeSessionCmd(String sessionId, String traceId) {
      this(sessionId, traceId, null, Instant.now());
    }

    public ResumeSessionCmd(String sessionId, String traceId, String snapshotId) {
      this(sessionId, traceId, snapshotId, Instant.now());
    }

    @Override
    public String reason() {
      String r = "resume-session: " + sessionId;
      if (snapshotId != null) r += " snapshot=" + snapshotId;
      return r;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // /steer — 人工插话（忙时插队）
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 人工插话指令 {@code /steer}。
   *
   * <p>把人的一句话插进**当前轮**：忙时在当前助手回合的工具调用结束后、下一次 LLM 调用前投递； 空闲时等价一条新任务。与 {@link InterruptCmd}/{@link
   * AbortCmd} 不同，插话**不改变会话状态**。
   *
   * <p>协议面：HTTP {@code POST /chat/steer}、stdio {@code {"type":"steer","message":"…"}} 均映射到本指令。
   *
   * @param message 插话内容（人话指令；跨进程投递由运行侧统一加"人工插话·优先遵从"前缀）
   */
  record SteerCmd(String message, String sessionId, String traceId, Instant timestamp)
      implements ControlCmd {

    public SteerCmd {
      Objects.requireNonNull(message, "message must not be null");
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public SteerCmd(String message, String sessionId, String traceId) {
      this(message, sessionId, traceId, Instant.now());
    }

    @Override
    public String reason() {
      return "steer: " + message;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // /abort — 中止当前轮（会话保留）
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 中止当前轮指令 {@code /abort}。
   *
   * <p>只中止**当前一轮**执行，**会话保留**（上下文、记忆、排期不受影响）；与 {@link InterruptCmd} （Ctrl+C / {@code
   * /exit}，退出进程）语义区分。超时保护（单轮预算）也复用它：超时 ⇒ abort ⇒ 会话可用。
   *
   * <p>协议面：HTTP {@code POST /chat/interrupt}、stdio {@code {"type":"abort"}} 均映射到本指令。
   */
  record AbortCmd(String sessionId, String traceId, Instant timestamp) implements ControlCmd {

    public AbortCmd {
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public AbortCmd(String sessionId, String traceId) {
      this(sessionId, traceId, Instant.now());
    }

    @Override
    public String reason() {
      return "abort-round";
    }
  }
}
