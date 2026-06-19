---
title: "E2E Case — alice-tool-gateway endpoints"
summary: "Hole test specification for alice-tool-gateway module — ToolRegistry, ExecutionEngine, SandboxProvider public API boundaries."
read_when:
  - "implementing or modifying hole tests for alice-tool-gateway"
scope:
  - "alice-agent-command"
  - "alice-tool-gateway"
status: "active"
updated: "2026-06-19"
---

# E2E Case — alice-tool-gateway (Hole Test)

## 1. Purpose

Probe the **alice-tool-gateway** module's public API boundary — tool registration, execution, and sandbox isolation.

## 2. Hole Design

```
ToolMetadata ──► ToolRegistry.register() / lookup() ──► ToolMetadata
                    ● (TGW-P01)
@Tool beans ──► ToolDiscovery.scanAndRegister()
                    ● (TGW-P02)
Action ──► ExecutionEngine.invoke() ──► Observation
              ● (TGW-P03)
Callable ──► SandboxProvider.executeInIsolation() ──► Result
              ● (TGW-P04)
```

## 3. Hole Tests

### TGW-P01: `ToolRegistry.register()` + `lookup()`

| Field | Value |
|-------|-------|
| **Input** | Register tool with name `"test_tool"`, lookup by same name |
| **Expected** | `lookup("test_tool")` returns exact same metadata |
| **Assertion** | `metadata.name == "test_tool"` |

### TGW-P02: `ToolDiscovery.scanAndRegister()` auto-discovery

| Field | Value |
|-------|-------|
| **Input** | Annotated tool class in scan path |
| **Expected** | Tool is discovered and registered in ToolRegistry |
| **Assertion** | `ToolRegistry.lookup(discoveredName) != null` |

### TGW-P03: `ExecutionEngine.invoke()` tool invocation

| Field | Value |
|-------|-------|
| **Input** | `Action` with tool name + params matching registered tool |
| **Expected** | Returns `Observation` with result content |
| **Assertion** | `observation != null && observation.contains(result)` |

### TGW-P04: `SandboxProvider.executeInIsolation()`

| Field | Value |
|-------|-------|
| **Input** | Simple task: `() -> 42` |
| **Expected** | Returns `Result` with value 42 |
| **Assertion** | `result == 42` |
