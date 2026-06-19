---
title: "E2E Case — `chat` command"
summary: "E2E test specification for the `alice chat` subcommand — interactive session lifecycle, natural language input, slash command dispatch."
read_when:
  - "implementing or modifying E2E tests for `alice chat`"
  - "verifying JLine chat session startup, exit, and input dispatch"
scope:
  - "alice-agent-command"
  - "alice-facade-cmd"
status: "active"
updated: "2026-06-19"
---

# E2E Case — `alice chat`

## 1. Purpose

Verify that the `alice chat` subcommand correctly:
- Starts an interactive JLine chat session
- Accepts piped `/exit` to terminate
- Routes natural language input through `AgentCommand.parse()` → `dispatchCommand()`
- Handles `/exit` as `InterruptCmd` with cause `user-exit`

## 2. Technical Constraint

`JLineChatSession` uses `TerminalBuilder.builder().system(true).build()` which requires 
an interactive terminal. When run via Gradle subprocess or stdin pipe, the terminal 
cannot be initialized, making E2E capture impossible through `capture_output=True`.

**Consequence**: These tests must use `skipTest()` with documentation cross-references to 
the unit tests that verify this logic.

## 3. TDD Test Cases

### TC-CHAT-01: Chat session startup and exit

| Field | Value |
|-------|-------|
| **Command** | `echo "/exit" | alice chat` |
| **Expected exit** | 0 |
| **Expected output** | Chat welcome banner or exit confirmation |
| **Status** | ⏭️ Skip — JLine terminal dependency |
| **Unit test** | `JLineChatSessionSpec.groovy` |

### TC-CHAT-02: Natural language → AcquireGoalCmd

| Field | Value |
|-------|-------|
| **Command** | `echo -e "say hi\n/exit" | alice chat` |
| **Expected exit** | 0 |
| **Expected output** | Natural language dispatched |
| **Status** | ⏭️ Skip — JLine terminal dependency |
| **Unit test** | `AgentCommandParseSpec.groovy` |

## 4. Implementation

**Test file**: `e2e/test_chat.py` (or `e2e/scene_cli_subcommands.py`)

**Pattern**:
```python
def test_chat_01_exit(self):
    self.skipTest("JLine terminal required — see JLineChatSessionSpec.groovy")
```

## 5. Verification Log

| TC | Date | Result | Notes |
|----|------|--------|-------|
| TC-CHAT-01 | 2026-06-19 | ⏭️ Skip | JLine terminal not available via subprocess |
| TC-CHAT-02 | 2026-06-19 | ⏭️ Skip | JLine terminal not available via subprocess |
