/*
 * Alice Agent — Control Commands（控制与反馈）
 *
 * 对应 docs/app/AgentCommand.md 中的 ControlCmd 分支：
 *   ResetSessionCmd  — /new       （重置会话）
 *   FeedbackCmd      — /feedback  （人类在环响应）
 *   InterruptCmd     — Ctrl+C     （强制终止）
 *   ClearContextCmd  — /clear     （清除上下文）
 *   ViewContextCmd   — /context   （查看上下文）
 *   CompactContextCmd — /compact  （压缩上下文）
 */
package org.cland.alice.agent.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 控制与反馈指令 — 生命周期、HITL（Human-In-The-Loop）与上下文管理。
 *
 * <p>继承自 {@link AgentCommand}，密封许可给 {@link ResetSessionCmd}、{@link FeedbackCmd}、 {@link
 * InterruptCmd}、{@link ClearContextCmd}、{@link ViewContextCmd}、{@link CompactContextCmd}。
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
}
