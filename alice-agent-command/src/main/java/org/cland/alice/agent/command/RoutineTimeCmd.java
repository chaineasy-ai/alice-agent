/*
 * Alice Agent — Routine-Time Commands（定时调度）
 *
 * 第五个密封分支，代表基于时间的自主任务触发器（Cron、定时、周期性任务）。
 *
 * 包含两个具体记录类型：
 *   RegisterRoutineCmd — 用户面向的 /routine 指令，由 AgentCommand.parse() 创建
 *   TimeTriggeredCmd   — 系统/内核触发的定时任务执行，由 CronScheduler 程序化构建
 */
package org.cland.alice.agent.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 定时调度指令 — 基于 Cron 表达式的自主任务触发器。
 *
 * <p>继承自 {@link AgentCommand}，密封许可给 {@link RegisterRoutineCmd} 和 {@link TimeTriggeredCmd}。
 *
 * <p><b>设计约束</b>：
 *
 * <ul>
 *   <li>{@link RegisterRoutineCmd} 由 {@code AgentCommand.parse("/routine ...")} 创建
 *   <li>{@link TimeTriggeredCmd} 仅由内核 {@code CronScheduler} 程序化构建，不出现在 parse() 中
 * </ul>
 */
public sealed interface RoutineTimeCmd extends AgentCommand {

  /** 常规调度任务描述或目标（Cron 表达式或例行任务名） */
  String task();

  // ──────────────────────────────────────────────────────────────────────────
  // /routine — 用户注册定时任务
  // ──────────────────────────────────────────────────────────────────────────

  /** 注册定时任务指令 {@code /routine}。 */
  record RegisterRoutineCmd(
      String cronExpression, String sessionId, String traceId, Instant timestamp)
      implements RoutineTimeCmd {

    public RegisterRoutineCmd {
      Objects.requireNonNull(cronExpression, "cronExpression must not be null");
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
      Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public RegisterRoutineCmd(String cronExpression, String sessionId, String traceId) {
      this(cronExpression, sessionId, traceId, Instant.now());
    }

    @Override
    public String task() {
      return cronExpression;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // TimeTriggeredCmd — 内核 CronScheduler 触发
  // ──────────────────────────────────────────────────────────────────────────

  /** 定时触发指令（仅内核构建，不出现在 parse() 中）。 */
  record TimeTriggeredCmd(String routineGoal, String sessionId, String traceId, Instant timestamp)
      implements RoutineTimeCmd {

    public TimeTriggeredCmd {
      Objects.requireNonNull(routineGoal, "routineGoal must not be null");
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
      Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public TimeTriggeredCmd(String routineGoal, String sessionId, String traceId) {
      this(routineGoal, sessionId, traceId, Instant.now());
    }

    @Override
    public String task() {
      return routineGoal;
    }
  }
}
