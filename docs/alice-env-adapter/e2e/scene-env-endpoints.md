---
title: "Hole Scene — alice-env-adapter environment endpoints"
summary: "Module-level hole tests probing EnvManager, McpClient, SnapshotManager public API boundaries."
read_when:
  - "running or debugging hole tests for alice-env-adapter"
scope:
  - "alice-env-adapter"
status: "active"
updated: "2026-06-19"
---

# Hole Scene — alice-env-adapter Environment Endpoints

## 1. Scene Overview

4 hole probes into the `alice-env-adapter` module.

**Case doc**: `docs/alice-agent-command/e2e/case-env-adapter.md`

## 2. Probe Map

```
┌─────────────────────────────────────┐
│          alice-env-adapter          │
│                                     │
│  ENV-P01  EnvManager.execute()      │
│  ENV-P02  McpClient.callTool()      │
│  ENV-P03  McpClient.listTools()     │
│  ENV-P04  SnapshotManager rollback  │
└─────────────────────────────────────┘
```

## 3. How to Run

```bash
python docs/alice-env-adapter/e2e/hole_test_env.py
```
