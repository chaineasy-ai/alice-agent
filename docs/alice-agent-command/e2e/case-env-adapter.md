---
title: "E2E Case — alice-env-adapter endpoints"
summary: "Hole test specification for alice-env-adapter module — EnvManager, McpClient, SnapshotManager public API boundaries."
read_when:
  - "implementing or modifying hole tests for alice-env-adapter"
scope:
  - "alice-agent-command"
  - "alice-env-adapter"
status: "active"
updated: "2026-06-19"
---

# E2E Case — alice-env-adapter (Hole Test)

## 1. Purpose

Probe the **alice-env-adapter** module's public API boundary — environment execution, MCP tool calling, and snapshot rollback.

## 2. Hole Design

```
Action ──► EnvManager.execute() ──► Observation
              ● (ENV-P01)
McpClient.callTool(name, params) ──► Result
              ● (ENV-P02)  ─── via FakeMcpTransport
McpClient.listTools() ──► List<Tool>
              ● (ENV-P03)
EnvSnapshot ──► SnapshotManager.save()/rollback() ──► EnvState
              ● (ENV-P04)
```

## 3. Hole Tests

### ENV-P01: `EnvManager.execute()` action execution

| Field | Value |
|-------|-------|
| **Input** | Mock `Action` |
| **Expected** | Returns `Observation`, not null |
| **Assertion** | `observation != null` |

### ENV-P02: `McpClient.callTool()` via Fake transport

| Field | Value |
|-------|-------|
| **Input** | `FakeMcpTransport` returning known response |
| **Expected** | Returns `Result` matching fake response |
| **Assertion** | `result != null`, result contains expected fields |

### ENV-P03: `McpClient.listTools()` returns tool list

| Field | Value |
|-------|-------|
| **Input** | Client with fake transport returning 2 tools |
| **Expected** | Returns `List<Tool>` of size 2 |
| **Assertion** | `len(tools) == 2` |

### ENV-P04: `SnapshotManager.save()` + `rollback()`

| Field | Value |
|-------|-------|
| **Input** | Save snapshot of current state, mutate, rollback |
| **Expected** | After rollback, state matches saved snapshot |
| **Assertion** | `state == snapshot.state` |
