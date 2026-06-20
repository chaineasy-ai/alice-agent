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
State ──► EnvState (DISCONNECTED → READY → FINISHED)
           ● (ENV-P01)
EnvSnapshot.builder() ──► EnvSnapshot
           ● (ENV-P02)
SnapshotManager.save()/rollback() ──► EnvState
           ● (ENV-P03)
McpClient.callTool(name, params) ──► Result
           ● (ENV-P04)  ─── via McpTool model
McpTransport.connect()/disconnect() ──► void
           ● (ENV-P05)
```

## 3. Hole Tests

### ENV-P01: `EnvState` state machine

| Field | Value |
|-------|-------|
| **Target** | `EnvState` enum — canExecute(), isTerminal(), isTransitional() |
| **Input** | Check READY → canExecute=true, FINISHED → isTerminal=true |
| **Expected** | State machine flags correctly classify each state |
| **Assertion** | `canExecute()` and `isTerminal()` return expected values |

### ENV-P02: `EnvSnapshot` builder

| Field | Value |
|-------|-------|
| **Target** | `EnvSnapshot.builder()` — resources, state, effects |
| **Input** | Build EnvSnapshot with resources and irreversible effects |
| **Expected** | Snapshot fields match builder input |
| **Assertion** | `snapshot.resources().containsKey("file:/tmp")` |

### ENV-P03: `SnapshotManager.save()` + `rollback()`

| Field | Value |
|-------|-------|
| **Target** | `SnapshotManager` — save, rollback, commit |
| **Input** | Save snapshot of current state, mutate, rollback |
| **Expected** | After rollback, state matches saved snapshot |
| **Assertion** | `state == snapshot.state` |

### ENV-P04: `McpClient` / `McpTool` model

| Field | Value |
|-------|-------|
| **Target** | `McpTool` — create, invoke, error path |
| **Input** | Create McpTool with name & handler, invoke with valid/invalid params |
| **Expected** | Valid invoke returns result; invalid returns error |
| **Assertion** | `result != null`, error case caught |

### ENV-P05: `McpTransport` interface contract

| Field | Value |
|-------|-------|
| **Target** | `McpTransport` — connect(), disconnect(), sendMessage() |
| **Input** | Verify interface methods exist and accept expected types |
| **Expected** | Interface compiles and methods return CompletableFuture |
| **Assertion** | Methods present with correct signatures |
