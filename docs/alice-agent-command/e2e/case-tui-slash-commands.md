---
title: "E2E Case — TUI Slash Commands"
summary: "E2E test specification for TUI slash command parsing — 20 slash commands + 2 edge cases, mapped to AgentCommand sealed subtypes."
read_when:
  - "implementing or modifying E2E tests for TUI slash commands"
  - "verifying AgentCommand.parse() mapping for /run, /exec, /skill, /rules, /reload, /model, /new, /feedback, /exit, /clear, /context, /compact, /routine, /sub-agent"
scope:
  - "alice-agent-command"
  - "alice-facade-tui"
status: "active"
updated: "2026-06-19"
---

# E2E Case — TUI Slash Commands

## 1. Purpose

Verify that `AgentCommand.parse()` correctly maps 20 TUI slash commands + 2 edge cases 
(unknown command, empty input) to their corresponding `AgentCommand` sealed subtypes.

## 2. Technical Constraint

All slash commands are parsed in chat mode via `JLineChatSession.run()`, which uses 
`TerminalBuilder.builder().system(true).build()`. This requires an interactive terminal 
and cannot be driven via stdin pipe in a Gradle subprocess.

The `AgentCommand.parse()` method is package-private (`static` without `public`), so it 
cannot be called directly from external test harnesses. Its mapping logic is thoroughly 
verified by unit tests in `AgentCommandParseSpec.groovy`.

## 3. TDD Test Cases

### TC-SLASH-01: Natural language → AcquireGoalCmd

| Field | Value |
|-------|-------|
| **Input** | `hello ai` |
| **Expected type** | `AcquireGoalCmd` (goal=`hello ai`) |
| **Status** | ⏭️ Skip — JLine required |
| **Unit test** | `AgentCommandParseSpec.groovy` |

### TC-SLASH-02~03: `/run` → AcquireGoalCmd

| Input | Expected | Status | Unit test |
|-------|----------|--------|-----------|
| `/run write a poem` | `AcquireGoalCmd` | ⏭️ Skip | `AgentCommandParseSpec.groovy` |
| `/run` (empty) | `AcquireGoalCmd` (empty) | ⏭️ Skip | `AgentCommandParseSpec.groovy` |

### TC-SLASH-04~05: `/exec` → ExecuteRawCmd

| Input | Expected | Status | Unit test |
|-------|----------|--------|-----------|
| `/exec nvidia-smi` | `ExecuteRawCmd` | ⏭️ Skip | `AgentCommandParseSpec.groovy` |
| `/exec` (empty) | `ExecuteRawCmd` (default) | ⏭️ Skip | `AgentCommandParseSpec.groovy` |

### TC-SLASH-06~09: `/skill`, `/rules`, `/reload` → CapabilityCmd subtypes

| Input | Expected | Status | Unit test |
|-------|----------|--------|-----------|
| `/skill mcp-tools.json` | `RegisterSkillCmd` | ⏭️ Skip | `CapabilityCmdSpec.groovy` |
| `/rules rules/my.prompt` | `UpdateRulesCmd` | ⏭️ Skip | `CapabilityCmdSpec.groovy` |
| `/reload` | `ReloadKernelCmd` | ⏭️ Skip | `CapabilityCmdSpec.groovy` |
| `/reload all` | `ReloadKernelCmd` | ⏭️ Skip | `CapabilityCmdSpec.groovy` |

### TC-SLASH-10~13: `/model`, `/new`, `/feedback` → AlignmentCmd / ControlCmd

| Input | Expected | Status | Unit test |
|-------|----------|--------|-----------|
| `/model claude-3.5` | `SwitchModelCmd` | ⏭️ Skip | `AlignmentCmdSpec.groovy` |
| `/model` (empty) | `SwitchModelCmd` (default) | ⏭️ Skip | `AlignmentCmdSpec.groovy` |
| `/new` | `ResetSessionCmd` | ⏭️ Skip | `ControlCmdSpec.groovy` |
| `/feedback 回答太长了` | `FeedbackCmd` | ⏭️ Skip | `ControlCmdSpec.groovy` |

### TC-SLASH-14~18: `/exit`, `/clear`, `/context`, `/compact` → ControlCmd

| Input | Expected | Status | Unit test |
|-------|----------|--------|-----------|
| `/exit` | `InterruptCmd` | ⏭️ Skip | `ControlCmdSpec.groovy` |
| `/clear` | `ClearContextCmd` | ⏭️ Skip | `ControlCmdSpec.groovy` |
| `/clear all` | `ClearContextCmd` (ignore) | ⏭️ Skip | `ControlCmdSpec.groovy` |
| `/context` | `ViewContextCmd` | ⏭️ Skip | `ControlCmdSpec.groovy` |
| `/compact` | `CompactContextCmd` | ⏭️ Skip | `ControlCmdSpec.groovy` |

### TC-SLASH-19~20: `/routine` → RegisterRoutineCmd

| Input | Expected | Status | Unit test |
|-------|----------|--------|-----------|
| `/routine 0 */2 * * * ?` | `RegisterRoutineCmd` | ⏭️ Skip | `RoutineTimeCmdSpec.groovy` |
| `/routine` (empty) | `RegisterRoutineCmd` | ⏭️ Skip | `RoutineTimeCmdSpec.groovy` |

### TC-SLASH-21~28: `/sub-agent` → 7 SubAgentCmd subtypes

| Input | Expected | Status | Unit test |
|-------|----------|--------|-----------|
| `/sub-agent spawn --goal "analyze code"` | `SpawnSubAgentCmd` | ⏭️ Skip | `SubAgentCmdParseSpec.groovy` |
| `/sub-agent spawn --goal "monitor" --model gpt-4o` | `SpawnSubAgentCmd` | ⏭️ Skip | `SubAgentCmdParseSpec.groovy` |
| `/sub-agent connect --name "worker" --acp-endpoint http://x:9000` | `ConnectSubAgentCmd` | ⏭️ Skip | `SubAgentCmdParseSpec.groovy` |
| `/sub-agent list` | `ListSubAgentsCmd` | ⏭️ Skip | `SubAgentCmdParseSpec.groovy` |
| `/sub-agent cancel abc-123` | `CancelSubAgentCmd` | ⏭️ Skip | `SubAgentCmdParseSpec.groovy` |
| `/sub-agent results abc-123` | `GetSubAgentResultsCmd` | ⏭️ Skip | `SubAgentCmdParseSpec.groovy` |
| `/sub-agent send agent1 "report"` | `SendToSubAgentCmd` | ⏭️ Skip | `SubAgentCmdParseSpec.groovy` |
| `/sub-agent prompt ext-agent "analyze"` | `PromptSubAgentCmd` | ⏭️ Skip | `SubAgentCmdParseSpec.groovy` |

### TC-SLASH-29~30: Edge cases

| Input | Expected | Status | Unit test |
|-------|----------|--------|-----------|
| `/unknown` | `null` (graceful) | ⏭️ Skip | `AgentCommandParseSpec.groovy` |
| Empty line | `null` (ignored) | ⏭️ Skip | `AgentCommandParseSpec.groovy` |

## 4. Implementation

**Test file**: `docs/alice-facade-tui/e2e/test_slash_commands.py`

## 5. Verification Log

| TC | Date | Result | Notes |
|----|------|--------|-------|
| TC-SLASH-01~30 | 2026-06-19 | ⏭️ Skip | All JLine terminal dependent. Verified by unit tests. |
