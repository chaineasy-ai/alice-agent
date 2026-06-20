---
title: "E2E Case — alice-tool-gateway endpoints"
summary: "Hole test specification for alice-tool-gateway module — ToolRegistry, ExecutionEngine, SandboxProvider public API boundaries."
read_when:
  - "implementing or modifying hole tests for alice-tool-gateway"
scope:
  - "alice-agent-command"
  - "alice-tool-gateway"
status: "active"
updated: "2026-06-20"
---

# E2E Case — alice-tool-gateway (Hole Test)

## 1. Purpose

Probe the **alice-tool-gateway** module's public API boundary — tool registration, execution, and sandbox isolation.

Hole tests operate **via Gradle JavaExec (`runHoleTest`)** — they exercise the module's public API
directly (ToolDiscovery → ToolRegistry → ExecutionEngine), not through unit test runners.

## 2. Hole Design

```
ToolMetadata ──► ToolRegistry.lookup() ──► ToolMetadata
                    ● (TGW-P01: lookup)

ToolRegistry ──► .toolNames() / .allTools() ──► Set<String> / Collection<ToolMetadata>
                    ● (TGW-P05: list)

AgentTool beans ──► ToolDiscovery.scanAndRegister() ──► populated ToolRegistry
                    ● (TGW-P02: scan)

Action ──► ExecutionEngine.invoke() ──► ToolResult
              ● (TGW-P03: invoke)

Callable ──► SandboxProvider.executeInIsolation() ──► Result
              ● (TGW-P04: sandbox)

BuiltinTools all 9 methods ──► direct method call ──► String result
                    ● (TGW-P06: builtins)

BuiltinTools.webSearch() ──► DuckDuckGo API ──► String result
                    ● (TGW-P07: web_search)
```

## 3. Hole Tests

Each hole is verified by running:

```bash
./gradlew :alice-tool-gateway:runHoleTest --args="<key> [args...]"
```

| Hole | Key | What it tests |
|------|-----|---------------|
| TGW-P01 | `lookup` | `ToolRegistry.lookup(name)` returns non-null for each builtin tool |
| TGW-P02 | `scan` | `ToolDiscovery.scanAndRegister(builtins)` populates registry |
| TGW-P03 | `invoke` | `ExecutionEngine.invoke("list_dir", {path: ...})` returns SUCCESS |
| TGW-P04 | `sandbox` | SandboxProvider executes `run` command through ExecutionEngine |
| TGW-P05 | `list` | `toolNames()` / `allTools()` return consistent counts |
| TGW-P06 | `builtins` | All 9 BuiltinTools method invocations (read_file, write_file, grep, run, list_dir, file_exists, search_file, remove_file, web_search*) |
| TGW-P07 | `web_search` | Real DuckDuckGo API call (`web_search 'java programming' 3`) — SKIPs if no network |

### TGW-P01: `ToolRegistry.lookup()` — tool lookup

| Field | Value |
|-------|-------|
| **Key** | `lookup` |
| **Input** | Register all BuiltinTools, lookup each by name |
| **Expected** | Each `lookup(name)` returns non-null metadata |
| **Assertion** | All 9 tools found |

### TGW-P02: `ToolDiscovery.scanAndRegister()` — auto-discovery

| Field | Value |
|-------|-------|
| **Key** | `scan` |
| **Input** | `new ToolDiscovery(registry).scanAndRegister(List.of(new BuiltinTools()))` |
| **Expected** | Registry populated with 9 tools |
| **Assertion** | `toolNames().size() == 9` |

### TGW-P03: `ExecutionEngine.invoke()` — tool invocation

| Field | Value |
|-------|-------|
| **Key** | `invoke` |
| **Input** | `engine.invoke("list_dir", {path: "src/hole/java"})` |
| **Expected** | Returns `ToolResult(status=SUCCESS, rawData=...)` |
| **Assertion** | `status == SUCCESS && rawData != null` |

### TGW-P04: `SandboxProvider.executeInIsolation()` — sandbox execution

| Field | Value |
|-------|-------|
| **Key** | `sandbox` |
| **Input** | `engine.invoke("run", {command: "echo sandbox-test"})` |
| **Expected** | Command runs successfully through SandboxProvider |
| **Assertion** | `status == SUCCESS` |

### TGW-P05: `ToolRegistry.toolNames()` / `allTools()` — tool list query

| Field | Value |
|-------|-------|
| **Key** | `list` |
| **Input** | Register all BuiltinTools, query the full list |
| **Expected** | `toolNames()` and `allTools()` return same count |
| **Assertion** | `names.size() == all.size()` |

### TGW-P06: `BuiltinTools` all 9 methods — direct invocation

| Field | Value |
|-------|-------|
| **Key** | `builtins` |
| **Input** | Directly call each BuiltinTools method with valid params |
| **Expected** | Each method returns a non-null/non-empty result |
| **Assertion** | All 9 tool invocations succeed without exception |

### TGW-P07: `web_search` — real DuckDuckGo API call

| Field | Value |
|-------|-------|
| **Key** | `web_search 'query' [maxResults]` |
| **Input** | `webSearch("java programming", "3")` |
| **Expected** | Returns formatted search results from DDG API |
| **Assertion** | `result.length() > 0` |
| **Network fallback** | `HttpConnectTimeoutException` → `SKIP` (exit 0) |
