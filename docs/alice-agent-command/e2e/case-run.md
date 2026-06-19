---
title: "E2E Case — `run` command"
summary: "E2E test specification for the `alice run` subcommand — AcquireGoalCmd dispatch, model override, flags, and error handling."
read_when:
  - "implementing or modifying E2E tests for `alice run`"
  - "verifying the `run` subcommand picocli parsing and dispatch"
scope:
  - "alice-agent-command"
  - "alice-facade-cmd"
status: "active"
updated: "2026-06-19"
---

# E2E Case — `alice run`

## 1. Purpose

Verify that the `alice run <task>` subcommand correctly:
- Parses task text as an `AcquireGoalCmd`
- Accepts `--model`, `--verbose`, `--json` flags
- Rejects missing task parameter
- Shows help with `--help`
- Routes through `AliceCliLauncher.run()` → `ExecutionCoordinator` → PPAO loop

## 2. TDD Test Cases

### TC-RUN-01: Basic task execution

| Field | Value |
|-------|-------|
| **Command** | `alice run "say hello"` |
| **Expected exit** | 0 or 1 (PPAO may fail without LLM key) |
| **Expected output** | `RunConfig` logged with task text |
| **Assertion** | `assertIn("RunConfig", output)` |

### TC-RUN-02: Model override via `--model`

| Field | Value |
|-------|-------|
| **Command** | `alice run "task" --model gpt-4o` |
| **Expected exit** | 0 or 1 |
| **Expected output** | `gpt-4o` in RunConfig log |
| **Assertion** | `assertIn("gpt-4o", output)` |

### TC-RUN-03: Boolean flags `--verbose --json`

| Field | Value |
|-------|-------|
| **Command** | `alice run "status" --verbose --json` |
| **Expected exit** | 0 or 1 |
| **Expected output** | `verbose` in output |
| **Assertion** | `assertIn("verbose", output.lower())` |

### TC-RUN-04: Missing task parameter

| Field | Value |
|-------|-------|
| **Command** | `alice run` (no args) |
| **Expected exit** | 1 or 2 |
| **Expected output** | Error about missing parameter |
| **Assertion** | `assertIn(result.returncode, [1, 2])` |

### TC-RUN-05: Help output

| Field | Value |
|-------|-------|
| **Command** | `alice run --help` |
| **Expected exit** | 0 |
| **Expected output** | Usage info with --model, --verbose, --json |
| **Assertion** | `assertIn("--model", output)` |

## 3. Implementation

**Test file**: `e2e/test_run.py` (or `e2e/scene_cli_subcommands.py`)

**Helper**: `run_cli(args, module=':alice-facade-cmd:run')`

**Pattern**:
```python
def test_run_01_basic(self):
    result = run_cli(["run", "say hello"])
    output = result.stdout + result.stderr
    self.assertIn(result.returncode, [0, 1])
    self.assertIn("RunConfig", output)
```

## 4. Verification Log

| TC | Date | Result | Notes |
|----|------|--------|-------|
| TC-RUN-01 | 2026-06-19 | ✅ | exit=0, RunConfig found |
| TC-RUN-02 | 2026-06-19 | ✅ | gpt-4o in RunConfig |
| TC-RUN-03 | 2026-06-19 | ✅ | verbose flag logged |
| TC-RUN-04 | 2026-06-19 | ✅ | Missing param error |
| TC-RUN-05 | 2026-06-19 | ✅ | Help with --model |
