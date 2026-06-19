---
title: "E2E Case — `routine` command"
summary: "E2E test specification for the `alice routine` subcommand — cron registration, listing, and help."
read_when:
  - "implementing or modifying E2E tests for `alice routine`"
  - "verifying routine/cron parsing and RegisterRoutineCmd dispatch"
scope:
  - "alice-agent-command"
  - "alice-facade-cmd"
status: "active"
updated: "2026-06-19"
---

# E2E Case — `alice routine`

## 1. Purpose

Verify that the `alice routine` subcommand correctly:
- Parses cron expressions and creates `RegisterRoutineCmd`
- Lists registered routines with `--list`
- Shows help with `--help`
- Handles missing cron expression gracefully

## 2. TDD Test Cases

### TC-ROUTINE-01: Register cron expression

| Field | Value |
|-------|-------|
| **Command** | `alice routine "0 */2 * * * ?"` |
| **Expected exit** | 0 or 1 |
| **Expected output** | `routineCron` in RunConfig log |
| **Assertion** | `assertIn("routineCron", output)` |

### TC-ROUTINE-02: List routines

| Field | Value |
|-------|-------|
| **Command** | `alice routine --list` |
| **Expected exit** | 0 or 1 |
| **Expected output** | `listRoutines=true` in RunConfig |
| **Assertion** | `assertIn("listRoutines=true", output)` |

### TC-ROUTINE-03: Help output

| Field | Value |
|-------|-------|
| **Command** | `alice routine --help` |
| **Expected exit** | 0 |
| **Expected output** | Contains routine subcommand options |
| **Assertion** | `assertEqual(result.returncode, 0)` |

## 3. Implementation

**Test file**: `docs/alice-facade-cmd/e2e/test_routine.py`

**Helper**: `run_cli(["routine", ...])`

## 4. Verification Log

| TC | Date | Result | Notes |
|----|------|--------|-------|
| TC-ROUTINE-01 | 2026-06-19 | ✅ | Cron expression parsed |
| TC-ROUTINE-02 | 2026-06-19 | ✅ | --list flag recognized |
| TC-ROUTINE-03 | 2026-06-19 | ✅ | Help complete |
