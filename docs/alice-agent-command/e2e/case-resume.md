---
title: "E2E Case — `resume` command"
summary: "E2E test specification for the `alice resume` subcommand — session resume flow, session-id handling, snapshot support, and help."
read_when:
  - "implementing or modifying E2E tests for `alice resume`"
  - "verifying ResumeSessionCmd dispatch and session restoration"
scope:
  - "alice-agent-command"
  - "alice-facade-cmd"
status: "active"
updated: "2026-06-25"
---

# E2E Case — `alice resume`

## 1. Purpose

Verify that the `alice resume` subcommand correctly:
- Resumes a session by `--session-id`
- Resumes a session with a specific `--snapshot`
- Lists available sessions when no `--session-id` is given
- Shows help with `--help`
- Handles unknown session-id gracefully

## 2. TDD Test Cases

### TC-RESUME-01: Resume by session-id

| Field | Value |
|-------|-------|
| **Command** | `alice resume --session-id "abc-123"` |
| **Expected exit** | 0 |
| **Expected output** | Contains `session restored` or session summary |
| **Assertion** | `assertIn("session restored", output)` |

### TC-RESUME-02: Resume with snapshot

| Field | Value |
|-------|-------|
| **Command** | `alice resume --session-id "abc-123" --snapshot "snap-001"` |
| **Expected exit** | 0 |
| **Expected output** | Contains snapshot reference or restore confirmation |
| **Assertion** | `assertIn("snap-001", output) or assertIn("session restored", output)` |

### TC-RESUME-03: List sessions when no session-id

| Field | Value |
|-------|-------|
| **Command** | `alice resume --list` |
| **Expected exit** | 0 |
| **Expected output** | Lists available sessions (IDs + summary + timestamp) |
| **Assertion** | `assertIn("session", output.lower())` |

### TC-RESUME-04: Unknown session-id

| Field | Value |
|-------|-------|
| **Command** | `alice resume --session-id "nonexistent-999"` |
| **Expected exit** | 1 |
| **Expected output** | Error indicating session not found |
| **Assertion** | `assertIn("not found", output.lower())` |

### TC-RESUME-05: Help output

| Field | Value |
|-------|-------|
| **Command** | `alice resume --help` |
| **Expected exit** | 0 |
| **Expected output** | Contains resume subcommand options |
| **Assertion** | `assertEqual(result.returncode, 0)` |

## 3. Sequence Diagram Reference

See [`DESIGN.md §7`](../DESIGN.md#7-时序图会话恢复流-resume) for the full resume flow sequence diagram, including:

1. User input with optional `--session-id` / `--snapshot`
2. Session listing fallback when no id provided
3. `MemoryVault.loadSession()` — persistent storage recovery
4. `MemoryRouter.reconstructShortTerm()` — sliding window rebuild
5. `Planner.refreshSystemKnowledge()` — rule/skill/context reload
6. Markdown summary confirmation

## 4. Implementation

**Test file**: `docs/alice-facade-cmd/e2e/test_resume.py` (implementation)

**Helper**: `run_cli(["resume", ...])`

## 5. Verification Log

| TC | Date | Result | Notes |
|----|------|--------|-------|
| TC-RESUME-01 | — | ⏳ | Pending implementation |
| TC-RESUME-02 | — | ⏳ | Pending implementation |
| TC-RESUME-03 | — | ⏳ | Pending implementation |
| TC-RESUME-04 | — | ⏳ | Pending implementation |
| TC-RESUME-05 | — | ⏳ | Pending implementation |
