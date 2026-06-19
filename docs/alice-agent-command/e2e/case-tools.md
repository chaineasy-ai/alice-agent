---
title: "E2E Case — `tools` command"
summary: "E2E test specification for the `alice tools` subcommand — tool listing, detail view, and help."
read_when:
  - "implementing or modifying E2E tests for `alice tools`"
  - "verifying tool registry listing and detail output"
scope:
  - "alice-agent-command"
  - "alice-facade-cmd"
status: "active"
updated: "2026-06-19"
---

# E2E Case — `alice tools`

## 1. Purpose

Verify that the `alice tools` subcommand correctly:
- Lists all registered tools from `ToolRegistry`
- Shows tool details with `--detail` flag
- Displays help with `--help`
- Handles empty tool registry gracefully

## 2. TDD Test Cases

### TC-TOOLS-01: Basic tool listing

| Field | Value |
|-------|-------|
| **Command** | `alice tools` |
| **Expected exit** | 0 |
| **Expected output** | `No tools registered.` or tool names |
| **Assertion** | `assertEqual(result.returncode, 0)` |

### TC-TOOLS-02: Tool detail view

| Field | Value |
|-------|-------|
| **Command** | `alice tools --detail` |
| **Expected exit** | 0 |
| **Expected output** | Tool details or empty-state message |
| **Assertion** | `assertEqual(result.returncode, 0)` |

### TC-TOOLS-03: Help output

| Field | Value |
|-------|-------|
| **Command** | `alice tools --help` |
| **Expected exit** | 0 |
| **Expected output** | Contains `--detail` in help text |
| **Assertion** | `assertIn("--detail", output)` |

## 3. Implementation

**Test file**: `docs/alice-facade-cmd/e2e/test_tools.py`

**Helper**: `run_cli(["tools", ...])`

## 4. Verification Log

| TC | Date | Result | Notes |
|----|------|--------|-------|
| TC-TOOLS-01 | 2026-06-19 | ✅ | exit=0, tool listing shown |
| TC-TOOLS-02 | 2026-06-19 | ✅ | exit=0, --detail flag accepted |
| TC-TOOLS-03 | 2026-06-19 | ✅ | Help contains --detail |
