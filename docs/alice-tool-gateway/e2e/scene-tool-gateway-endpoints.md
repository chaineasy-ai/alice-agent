---
title: "Hole Scene — alice-tool-gateway endpoints"
summary: "Module-level hole tests probing ToolRegistry, ExecutionEngine, SandboxProvider public API boundaries."
read_when:
  - "running or debugging hole tests for alice-tool-gateway"
scope:
  - "alice-tool-gateway"
status: "active"
updated: "2026-06-19"
---

# Hole Scene — alice-tool-gateway Endpoints

## 1. Scene Overview

4 hole probes into the `alice-tool-gateway` module.

**Case doc**: `docs/alice-agent-command/e2e/case-tool-gateway.md`

## 2. Probe Map

```
┌─────────────────────────────────────┐
│         alice-tool-gateway          │
│                                     │
│  TGW-P01  ToolRegistry register     │
│  TGW-P02  ToolDiscovery scan        │
│  TGW-P03  ExecutionEngine invoke    │
│  TGW-P04  SandboxProvider isolate   │
└─────────────────────────────────────┘
```

## 3. How to Run

```bash
python docs/alice-tool-gateway/e2e/hole_test_tool_gateway.py
```
