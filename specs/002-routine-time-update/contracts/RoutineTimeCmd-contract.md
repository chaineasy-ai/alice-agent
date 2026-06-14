# Contract: RoutineTimeCmd Sealed Interface

> **Module**: `alice-agent-command`
> **File**: `RoutineTimeCmd.java`
> **Package**: `org.cland.alice.agent.command`

## Interface Contract

```java
public sealed interface RoutineTimeCmd extends AgentCommand permits
    RegisterRoutineCmd, TimeTriggeredCmd {

  /** 常规调度任务描述或目标 */
  String task();
}
```

## Record Contracts

### RegisterRoutineCmd

```java
record RegisterRoutineCmd(
    String cronExpression,   // Cron 表达式（可能为空字符串）
    String sessionId,        // 会话 ID
    String traceId,          // 链路追踪 ID
    Instant timestamp        // 指令时间戳
) implements RoutineTimeCmd {
  // task() returns cronExpression
}
```

| Contract | Expectation |
|----------|-------------|
| Identity 1 | `RegisterRoutineCmd("0 */2 * * * ?", "s1", "t1", ts).cronExpression()` → `"0 */2 * * * ?"` |
| Identity 2 | `RegisterRoutineCmd("0 */2 * * * ?", "s1", "t1", ts).task()` → `"0 */2 * * * ?"` |
| Identity 3 | `RegisterRoutineCmd("0 */2 * * * ?", "s1", "t1", ts).sessionId()` → `"s1"` |
| Identity 4 | `RegisterRoutineCmd("0 */2 * * * ?", "s1", "t1", ts).traceId()` → `"t1"` |
| Null safety | `RegisterRoutineCmd(null, ...)` → `NullPointerException` |
| Null safety | `RegisterRoutineCmd("cron", null, "t1", ts)` → `NullPointerException` |
| Null safety | `RegisterRoutineCmd("cron", "s1", null, ts)` → `NullPointerException` |
| Null safety | `RegisterRoutineCmd("cron", "s1", "t1", null)` → `NullPointerException` |
| Blank allowed | `RegisterRoutineCmd("", "s1", "t1", ts).cronExpression()` → `""` |

### TimeTriggeredCmd

```java
record TimeTriggeredCmd(
    String routineGoal,      // 例行任务目标（如 "server-health-check"）
    String sessionId,        // 会话 ID
    String traceId,          // 链路追踪 ID
    Instant timestamp        // 指令时间戳
) implements RoutineTimeCmd {
  // task() returns routineGoal
}
```

| Contract | Expectation |
|----------|-------------|
| Identity 1 | `TimeTriggeredCmd("health-check", "s1", "t1", ts).routineGoal()` → `"health-check"` |
| Identity 2 | `TimeTriggeredCmd("health-check", "s1", "t1", ts).task()` → `"health-check"` |
| Null safety | `TimeTriggeredCmd(null, ...)` → `NullPointerException` |
| Null safety | `TimeTriggeredCmd("g", null, "t1", ts)` → `NullPointerException` |

## Integration Contract: AgentCommand.permits

**File**: `AgentCommand.java`

The `permits` clause MUST include `RoutineTimeCmd`:

```java
public sealed interface AgentCommand permits
    ExecutionCmd, CapabilityCmd, AlignmentCmd, ControlCmd, RoutineTimeCmd {
```

## Integration Contract: AgentCommand.parse()

The `parse()` switch expression MUST include a `/routine` case:

```java
case "/routine" ->
    new RoutineTimeCmd.RegisterRoutineCmd(args, sessionId, traceId);
```

| Input | Expected output |
|-------|-----------------|
| `/routine "0 */2 * * * ?"` | `RegisterRoutineCmd("0 */2 * * * ?", ...)` |
| `/routine` | `RegisterRoutineCmd("", ...)` — blank cron |
| `"natural language"` (no slash) | `AcquireGoalCmd` (unchanged) |

## Type Safety Contract

The following pattern-matching switch MUST compile without error:

```java
switch (cmd) {
  case RoutineTimeCmd.RegisterRoutineCmd r -> handleRegister(r);
  case RoutineTimeCmd.TimeTriggeredCmd t  -> handleTrigger(t);
  case ExecutionCmd e                     -> handleExec(e);
  case CapabilityCmd c                    -> handleCap(c);
  case AlignmentCmd a                     -> handleAlign(a);
  case ControlCmd ctrl                    -> handleCtrl(ctrl);
}
// If RoutineTimeCmd is excluded, the compiler must warn about non-exhaustive switch.
```
