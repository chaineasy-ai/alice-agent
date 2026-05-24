/*
 * Alice Agent — Execution Commands（任务驱动）
 *
 * 对应 docs/app/AgentCommand.md 中的 ExecutionCmd 分支：
 *   AcquireGoalCmd  — /run  （自主循环，P-E-M-T-V）
 *   ExecuteRawCmd   — /exec （直接 Shell / 工具调用）
 */
package org.cland.alice.agent.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 任务驱动指令 — 消耗 Token 的实际工作。
 *
 * <p>继承自 {@link AgentCommand}，密封许可给 {@link AcquireGoalCmd} 和 {@link ExecuteRawCmd}。
 */
public sealed interface ExecutionCmd extends AgentCommand {

  /** 任务描述（自然语言或 shell 指令） */
  String task();

  // ──────────────────────────────────────────────────────────────────────────
  // /run — 目标驱动（自主 P-E-M-T-V 循环）
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 目标驱动指令 {@code /run}。
   *
   * <p>开启 Planner-Executor-Memory-Verifier 完整循环，Agent 自主规划并执行。 对应设计文档中 "<b>Acquire Goal</b>" 用例。
   *
   * @param goal 自然语言描述的目标或任务
   */
  record AcquireGoalCmd(String goal, String sessionId, String traceId, Instant timestamp)
      implements ExecutionCmd {

    public AcquireGoalCmd {
      Objects.requireNonNull(goal, "goal must not be null");
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public AcquireGoalCmd(String goal, String sessionId, String traceId) {
      this(goal, sessionId, traceId, Instant.now());
    }

    @Override
    public String task() {
      return goal;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // /exec — 原生驱动（直接 Shell / 工具）
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 原生驱动指令 {@code /exec}。
   *
   * <p>直接执行底层 Shell 命令或工具调用，不经过 Planner 规划阶段。
   *
   * @param command 要执行的 shell 命令或工具标识
   */
  record ExecuteRawCmd(String command, String sessionId, String traceId, Instant timestamp)
      implements ExecutionCmd {

    public ExecuteRawCmd {
      Objects.requireNonNull(command, "command must not be null");
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public ExecuteRawCmd(String command, String sessionId, String traceId) {
      this(command, sessionId, traceId, Instant.now());
    }

    @Override
    public String task() {
      return command;
    }
  }
}
