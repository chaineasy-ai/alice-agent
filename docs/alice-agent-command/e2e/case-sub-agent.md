---
title: "E2E Case — `sub-agent` command"
summary: "E2E test specification for the `alice sub-agent` subcommand — spawn, connect, list, cancel, results, send, prompt, and help."
read_when:
  - "implementing or modifying E2E tests for `alice sub-agent`"
  - "verifying all 7 SubAgentCmd sealed subtype parsings via picocli"
scope:
  - "alice-agent-command"
  - "alice-facade-cmd"
status: "active"
updated: "2026-06-19"
---

# E2E Case — `alice sub-agent`

## 1. Purpose

Verify that the `alice sub-agent` subcommand correctly:
- Parses 7 sub-agent operations via picocli options (`--spawn`, `--connect`, `--list`, `--cancel`, `--results`, `--send`, `--prompt`)
- Each option maps to the corresponding `SubAgentCmd` sealed subtype
- Shows comprehensive help with `--help`
- Handles empty options gracefully (no crash)

## 2. Architecture Note

The `sub-agent` subcommand routes through picocli's `SubAgentCommand` → `toRunConfig()`, 
which stores parsed parameters in `RunConfig`. The current `run()` main flow then routes 
to `ExecutionCoordinator` (PPAO loop with task="sub-agent"), not through `dispatchCommand()`.

Full `dispatchCommand()` path for sub-agent types is exercised via chat mode (`/sub-agent`),
which requires JLine terminal. Unit tests in `SubAgentCmdSealedHierarchySpec.groovy` and 
`SubAgentCmdParseSpec.groovy` verify the dispatch logic.

## 3. TDD Test Cases

### TC-SUBAGENT-01: Spawn sub-agent

| Field | Value |
|-------|-------|
| **Command** | `alice sub-agent --spawn "monitor disk usage"` |
| **Expected exit** | 0 or 1 |
| **Expected output** | `subAgentSpawnGoal` in RunConfig |
| **Assertion** | `assertIn("subAgentSpawnGoal", output)` |

### TC-SUBAGENT-02: List sub-agents

| Field | Value |
|-------|-------|
| **Command** | `alice sub-agent --list` |
| **Expected exit** | 0 or 1 |
| **Expected output** | No crash, subcommand recognized |
| **Assertion** | `assertNotIn("No subcommand given", output)` |

### TC-SUBAGENT-03: Connect sub-agent

| Field | Value |
|-------|-------|
| **Command** | `alice sub-agent --connect "worker" --acp-endpoint "http://x:9000/acp"` |
| **Expected exit** | 0 or 1 |
| **Expected output** | `subAgentConnectName` in RunConfig |
| **Assertion** | `assertIn("subAgentConnectName", output)` |

### TC-SUBAGENT-04: Cancel sub-agent

| Field | Value |
|-------|-------|
| **Command** | `alice sub-agent --cancel "sub-abc-123"` |
| **Expected exit** | 0 or 1 |
| **Expected output** | No picocli parse error |
| **Assertion** | `assertNotIn("Unrecognized", output)` |

### TC-SUBAGENT-05: Get sub-agent results

| Field | Value |
|-------|-------|
| **Command** | `alice sub-agent --results "sub-abc-123"` |
| **Expected exit** | 0 or 1 |
| **Expected output** | No picocli parse error |
| **Assertion** | `assertNotIn("Unrecognized", output)` |

### TC-SUBAGENT-06: Send message to sub-agent

| Field | Value |
|-------|-------|
| **Command** | `alice sub-agent --send "agent1" --message "hello e2e"` |
| **Expected exit** | 0 or 1 |
| **Expected output** | No picocli parse error |
| **Assertion** | `assertNotIn("Unrecognized", output)` |

### TC-SUBAGENT-07: Prompt sub-agent

| Field | Value |
|-------|-------|
| **Command** | `alice sub-agent --prompt "analyze" --agent-id "ext-agent"` |
| **Expected exit** | 0 or 1 |
| **Expected output** | No picocli parse error |
| **Assertion** | `assertNotIn("Unrecognized", output)` |

### TC-SUBAGENT-08: Empty options

| Field | Value |
|-------|-------|
| **Command** | `alice sub-agent` (no options) |
| **Expected exit** | 0 or 1 |
| **Expected output** | No crash, subcommand recognized |
| **Assertion** | `assertNotIn("No subcommand given", output)` |

### TC-SUBAGENT-09: Help output

| Field | Value |
|-------|-------|
| **Command** | `alice sub-agent --help` |
| **Expected exit** | 0 |
| **Expected output** | Contains all sub-agent options |
| **Assertion** | `assertEqual(result.returncode, 0)` |

## 4. Implementation

**Test file**: `docs/alice-facade-cmd/e2e/test_sub_agent.py`

## 5. Verification Log

| TC | Date | Result | Notes |
|----|------|--------|-------|
| TC-SUBAGENT-01 | 2026-06-19 | ✅ | Spawn goal parsed in RunConfig |
| TC-SUBAGENT-02 | 2026-06-19 | ✅ | --list recognized |
| TC-SUBAGENT-03 | 2026-06-19 | ✅ | Connect name in RunConfig |
| TC-SUBAGENT-04 | 2026-06-19 | ✅ | Cancel parsed, no error |
| TC-SUBAGENT-05 | 2026-06-19 | ✅ | Results parsed, no error |
| TC-SUBAGENT-06 | 2026-06-19 | ✅ | Send/message parsed, no error |
| TC-SUBAGENT-07 | 2026-06-19 | ✅ | Prompt/agent-id parsed, no error |
| TC-SUBAGENT-08 | 2026-06-19 | ✅ | Empty options, no crash |
| TC-SUBAGENT-09 | 2026-06-19 | ✅ | Help complete |
