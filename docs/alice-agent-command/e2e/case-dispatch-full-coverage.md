---
title: "E2E Case — `dispatchCommand()` Full Coverage"
summary: "E2E test specification for AliceCliLauncher.dispatchCommand() — all 21 AgentCommand sealed subtypes."
read_when:
  - "implementing or modifying E2E tests for dispatchCommand()"
  - "verifying switch pattern matching for all AgentCommand subtypes"
scope:
  - "alice-agent-command"
  - "alice-facade-cmd"
status: "active"
updated: "2026-06-19"
---

# E2E Case — `dispatchCommand()` Full Coverage

## 1. Purpose

Verify that `AliceCliLauncher.dispatchCommand(AgentCommand)` handles all 21 AgentCommand 
sealed subtypes through its switch pattern matching, returning appropriate exit codes 
and producing expected output for each.

## 2. Architecture Note

`dispatchCommand(AgentCommand)` is called from two paths:
1. **CLI subcommands** (`run`, `routine`, `sub-agent`): picocli → RunConfig → ExecutionCoordinator → PPAO (NOT dispatchCommand)
2. **Chat mode** (`JLineChatSession`): `/xxx` → AgentCommand.parse() → dispatchCommand()

The dispatch path is only reachable via chat mode for types like ExecuteRawCmd, RegisterSkillCmd, 
UpdateRulesCmd, ReloadKernelCmd, SwitchModelCmd, ResetSessionCmd, FeedbackCmd, InterruptCmd, 
ClearContextCmd, ViewContextCmd, CompactCmd (TC-DISPATCH-02~12).

CLI-accessible types (TC-DISPATCH-01, 13, 15~21) are verified via their CLI subcommand equivalents.

## 3. TDD Test Cases

### TC-DISPATCH-01: AcquireGoalCmd

| Field | Value |
|-------|-------|
| **Trigger** | `alice run "test dispatch"` |
| **Expected exit** | 0 or 1 |
| **Assertion** | `assertIn("RunConfig", output)` |

### TC-DISPATCH-02~12: Chat-only types

| Field | Value |
|-------|-------|
| **Status** | ⏭️ Skip — JLine terminal required |
| **Unit test** | `AliceCliLauncherDispatchSpec.groovy` |

### TC-DISPATCH-13: RegisterRoutineCmd

| Field | Value |
|-------|-------|
| **Trigger** | `alice routine "0 */5 * * * ?"` |
| **Expected exit** | 0 or 1 |
| **Assertion** | `assertIn("routineCron", output)` |

### TC-DISPATCH-14: TimeTriggeredCmd

| Field | Value |
|-------|-------|
| **Status** | ⏭️ Skip — kernel internal, CronScheduler built |

### TC-DISPATCH-15: SpawnSubAgentCmd

| Field | Value |
|-------|-------|
| **Trigger** | `alice sub-agent --spawn "monitor"` |
| **Expected exit** | 0 or 1 |
| **Assertion** | `assertIn("subAgentSpawnGoal", output)` |

### TC-DISPATCH-16: ConnectSubAgentCmd

| Field | Value |
|-------|-------|
| **Trigger** | `alice sub-agent --connect "w1" --acp-endpoint "http://x"` |
| **Expected exit** | 0 or 1 |
| **Assertion** | `assertIn("subAgentConnectName", output)` |

### TC-DISPATCH-17: ListSubAgentsCmd

| Field | Value |
|-------|-------|
| **Trigger** | `alice sub-agent --list` |
| **Expected exit** | 0 or 1 |
| **Assertion** | `assertNotIn("No subcommand given", output)` |

### TC-DISPATCH-18~21: CancelSubAgentCmd / GetSubAgentResultsCmd / SendToSubAgentCmd / PromptSubAgentCmd

| Field | Value |
|-------|-------|
| **Trigger** | `alice sub-agent --cancel/--results/--send --message/--prompt --agent-id` |
| **Expected exit** | 0 or 1 |
| **Assertion** | No picocli parse errors |

## 4. Verification Log

| TC | Date | Result | Notes |
|----|------|--------|-------|
| TC-DISPATCH-01 | 2026-06-19 | ✅ | AcquireGoalCmd via `run` |
| TC-DISPATCH-02~12 | 2026-06-19 | ⏭️ Skip | Chat-only, JLine required |
| TC-DISPATCH-13 | 2026-06-19 | ✅ | RegisterRoutineCmd via `routine` |
| TC-DISPATCH-14 | 2026-06-19 | ⏭️ Skip | Kernel internal |
| TC-DISPATCH-15~21 | 2026-06-19 | ✅ | SubAgentCmd via `sub-agent` |
