---
title: "Hole Scene — alice-tool-gateway endpoints"
summary: "Module-level hole tests probing ToolRegistry, ExecutionEngine, SandboxProvider, and BuiltinTools public API boundaries."
read_when:
  - "running or debugging hole tests for alice-tool-gateway"
scope:
  - "alice-tool-gateway"
status: "active"
updated: "2026-06-20"
---

# Hole Scene — alice-tool-gateway Endpoints

## 1. Scene Overview

9 hole probes into the `alice-tool-gateway` module, each calling the module boundary
directly via `BuiltinToolsHoleTest` (Gradle `runHoleTest` task — no unit test runners).

**Case doc**: `docs/alice-agent-command/e2e/case-tool-gateway.md`

## 2. Probe Map

```
┌───────────────────────────────────────────┐
│           alice-tool-gateway              │
│                                           │
│  TGW-P01  ToolRegistry.lookup()           │
│  TGW-P02  ToolDiscovery scan              │
│  TGW-P03  ExecutionEngine invoke          │
│  TGW-P04  SandboxProvider isolate         │
│  TGW-P05  ToolRegistry list query         │
│  TGW-P06  BuiltinTools 9 methods          │
│  TGW-P07  web_search (network)            │
│  TGW-P08  McpTool model (create+invoke)   │
│  TGW-P09  McpToolAdapter + Registry + EE  │
└───────────────────────────────────────────┘
```

| Hole | Status | Key | Notes |
|------|--------|-----|-------|
| TGW-P01 | 🟩 GREEN | `lookup` | ToolRegistry.lookup() — all 9 tools found |
| TGW-P02 | 🟩 GREEN | `scan` | ToolDiscovery.scanAndRegister() — registry populated |
| TGW-P03 | 🟩 GREEN | `invoke` | ExecutionEngine.invoke('list_dir') returns SUCCESS |
| TGW-P04 | 🟩 GREEN | `sandbox` | SandboxProvider executes 'run' command |
| TGW-P05 | 🟩 GREEN | `list` | toolNames() / allTools() consistent |
| TGW-P06 | 🟩 GREEN | `builtins` | All 9 BuiltinTools methods (no network) |
| TGW-P07 | 🟩 SKIP ⏭️ | `web_search` | DuckDuckGo API — SKIP if no network |
| TGW-P08 | 🟩 GREEN | `mcp_tool` | McpTool model create/invoke/error |
| TGW-P09 | 🟩 GREEN | `mcp_registry` | McpToolAdapter → Registry → ExecutionEngine |

## 3. How to Run

```bash
# Run all probes
python docs/alice-tool-gateway/e2e/hole_test_tool_gateway.py

# Run a single probe directly
./gradlew :alice-tool-gateway:runHoleTest --args="lookup"
./gradlew :alice-tool-gateway:runHoleTest --args="builtins"
./gradlew :alice-tool-gateway:runHoleTest --args="web_search 'java' 3"
```

## 4. Architecture

```
Python hole_test_tool_gateway.py
  │
  ├── TGW-P01~P06 ── Gradle runHoleTest ── BuiltinToolsHoleTest.main()
  │                                            │
  │                                            ├── lookup   → new ToolRegistry() + lookup()
  │                                            ├── scan     → ToolDiscovery.scanAndRegister()
  │                                            ├── invoke   → ExecutionEngine.invoke(list_dir)
  │                                            ├── sandbox  → ExecutionEngine.invoke(run)
  │                                            ├── list     → toolNames() / allTools()
  │                                            └── builtins → new BuiltinTools() 9 methods
  │
  └── TGW-P07 ── Gradle runHoleTest ── BuiltinToolsHoleTest.testWebSearch()
                                       → BuiltinTools.webSearch(query, maxResults)
                                       → HttpConnectTimeoutException → SKIP

  TGW-P08 ── Gradle runHoleTest ── BuiltinToolsHoleTest.testMcpToolModel()
                                    → McpTool.builder().invoke()

  TGW-P09 ── Gradle runHoleTest ── BuiltinToolsHoleTest.testMcpToolInRegistry()
                                    → McpToolAdapter → ToolRegistry → ExecutionEngine
```
