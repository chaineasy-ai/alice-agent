/*
 * Alice Agent — Control Commands（控制与反馈）
 *
 * 对应 docs/app/AgentCommand.md 中的 ControlCmd 分支：
 *   ResetSessionCmd — /new       （重置会话）
 *   FeedbackCmd     — /feedback  （人类在环响应）
 *   InterruptCmd    — Ctrl+C     （强制终止）
 */
package org.cland.alice.agent.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 控制与反馈指令 — 生命周期与 HITL（Human-In-The-Loop）。
 *
 * <p>继承自 {@link AgentCommand}，密封许可给 {@link ResetSessionCmd}、{@link FeedbackCmd}、 {@link
 * InterruptCmd}。
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
}
