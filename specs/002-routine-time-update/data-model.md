# Data Model: Routine-Time Command Model Update

> **Date**: 2026-06-14
> **Status**: Final
> **Scope**: `alice-agent-command`, `alice-facade-cmd`, `alice-facade-tui`

## Entity Definitions

### RoutineTimeCmd (sealed interface)

Extends `AgentCommand` as the 5th sealed branch. Represents time-based autonomous task triggers.

```
sealed interface RoutineTimeCmd extends AgentCommand
    permits RegisterRoutineCmd, TimeTriggeredCmd
```

**Common accessor**: `String task()` — description of the routine task or goal

### RegisterRoutineCmd (record)

User-facing command created by parsing `/routine <cron-expression>`.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `cronExpression` | `String` | Yes | Cron expression for scheduling (e.g., "0 */2 * * * ?") — blank if not provided |
| `sessionId` | `String` | Yes | Session identifier (inherited from AgentCommand) |
| `traceId` | `String` | Yes | Trace identifier for request tracking |
| `timestamp` | `Instant` | Yes | Command creation timestamp |

**Validation rules**:
- `cronExpression` must not be null (may be blank — defer validation to downstream CronScheduler)
- `sessionId`, `traceId`, `timestamp` validated by `Objects.requireNonNull()` in compact constructor

**State**: Immutable value object (Java record) — no mutable state or lifecycle

### TimeTriggeredCmd (record)

System-triggered command created by the CronScheduler kernel component. NOT user-parseable from CLI/TUI.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `routineGoal` | `String` | Yes | Goal description for the routine execution (e.g., "server-health-check") |
| `sessionId` | `String` | Yes | Session identifier (inherited from AgentCommand) |
| `traceId` | `String` | Yes | Trace identifier for request tracking |
| `timestamp` | `Instant` | Yes | Command creation timestamp |

**Validation rules**:
- `routineGoal` must not be null
- `sessionId`, `traceId`, `timestamp` validated by `Objects.requireNonNull()` in compact constructor

**State**: Immutable value object (Java record) — no mutable state or lifecycle

### RunConfig (existing class — modified)

Existing CLI configuration record with two new optional fields:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `routineCron` | `String` | `null` | Cron expression when using `alice routine` subcommand |
| `listRoutines` | `boolean` | `false` | Flag when using `alice routine --list` |

### SlashCommand (existing record — modified)

Existing TUI slash command record adds a new parse entry for `/routine`:

| Property | Value |
|----------|-------|
| Command | `/routine` |
| Type | `Type.CONFIG` |
| Description | "注册定时任务：注册 Cron 表达式到调度器" |

## Relationships

- `RoutineTimeCmd` **extends** `AgentCommand` (sealed interface inheritance)
- `RegisterRoutineCmd` **implements** `RoutineTimeCmd` (user-facing registration)
- `TimeTriggeredCmd` **implements** `RoutineTimeCmd` (kernel-triggered execution)
- `RunConfig.routineCron` is **consumed by** the agent core to create `RegisterRoutineCmd`
- `SlashCommand` (for `/routine`) **converts to** `RegisterRoutineCmd` via `AgentCommand.parse()`

## Type Hierarchy Diagram

```
AgentCommand (sealed)
├── ExecutionCmd (sealed)
│   ├── AcquireGoalCmd (record)
│   └── ExecuteRawCmd (record)
├── CapabilityCmd (sealed)
│   ├── RegisterSkillCmd (record)
│   ├── UpdateRulesCmd (record)
│   └── ReloadKernelCmd (record)
├── AlignmentCmd (sealed)
│   └── SwitchModelCmd (record)
├── ControlCmd (sealed)
│   ├── ResetSessionCmd (record)
│   ├── FeedbackCmd (record)
│   ├── InterruptCmd (record)
│   ├── ClearContextCmd (record)
│   ├── ViewContextCmd (record)
│   └── CompactContextCmd (record)
└── RoutineTimeCmd (sealed) ◄── NEW
    ├── RegisterRoutineCmd (record) ◄── NEW
    └── TimeTriggeredCmd (record) ◄── NEW
```
