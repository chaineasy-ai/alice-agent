---
title: "Hole Scene — alice-core-planner planner endpoints"
summary: "Module-level hole tests probing PlannerService, FastPath/SlowPath strategies, and WorldModel public API boundaries."
read_when:
  - "running or debugging hole tests for alice-core-planner"
scope:
  - "alice-core-planner"
status: "active"
updated: "2026-06-19"
---

# Hole Scene — alice-core-planner Planner Endpoints

## 1. Scene Overview

4 hole probes into the `alice-core-planner` module.

**Case doc**: `docs/alice-agent-command/e2e/case-core-planner.md`

## 2. Probe Map

```
┌─────────────────────────────────────┐
│          alice-core-planner         │
│                                     │
│  PLN-P01  PlannerService.plan()     │
│  PLN-P02  FastPathStrategy.decide() │
│  PLN-P03  SlowPathStrategy.decide() │
│  PLN-P04  WorldModel.predict()      │
└─────────────────────────────────────┘
```

## 3. How to Run

```bash
python docs/alice-core-planner/e2e/hole_test_planner.py
```
