---
title: "E2E Case — `config` command"
summary: "E2E test specification for the `alice config` subcommand — config overview, get, and set operations."
read_when:
  - "implementing or modifying E2E tests for `alice config`"
  - "verifying config file read/write, key-value operations"
scope:
  - "alice-agent-command"
  - "alice-facade-cmd"
status: "active"
updated: "2026-06-19"
---

# E2E Case — `alice config`

## 1. Purpose

Verify that the `alice config` subcommand correctly:
- Shows config overview when called without action
- Reads config keys with `config get <key>`
- Writes config values with `config set <key> <value>`
- Handles missing keys and invalid actions gracefully

## 2. TDD Test Cases

### TC-CONFIG-01: Config overview

| Field | Value |
|-------|-------|
| **Command** | `alice config` |
| **Expected exit** | 0 |
| **Expected output** | Config contents or empty-state message |
| **Assertion** | `assertEqual(result.returncode, 0)` |

### TC-CONFIG-02: Get config key

| Field | Value |
|-------|-------|
| **Command** | `alice config get default.model` |
| **Expected exit** | 0 |
| **Expected output** | The value of the key (or "not set") |
| **Assertion** | `assertEqual(result.returncode, 0)` |

### TC-CONFIG-03: Set config value

| Field | Value |
|-------|-------|
| **Command** | `alice config set openai.api_key sk-test-e2e` |
| **Expected exit** | 0 |
| **Expected output** | Indication that value was set |
| **Assertion** | `assertEqual(result.returncode, 0)` |

## 3. Implementation

**Test file**: `docs/alice-facade-cmd/e2e/test_config.py`

**Helper**: `run_cli(["config", ...])`

## 4. Verification Log

| TC | Date | Result | Notes |
|----|------|--------|-------|
| TC-CONFIG-01 | 2026-06-19 | ✅ | Config overview shown |
| TC-CONFIG-02 | 2026-06-19 | ✅ | Key lookup succeeds |
| TC-CONFIG-03 | 2026-06-19 | ✅ | Config value persisted |
