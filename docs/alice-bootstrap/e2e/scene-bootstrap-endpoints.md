---
title: "Hole Scene — alice-bootstrap launcher endpoints"
summary: "Module-level hole tests probing FacadeSelector, AppBootstrapper, IFacadeLauncher interface contract."
read_when:
  - "running or debugging hole tests for alice-bootstrap"
scope:
  - "alice-bootstrap"
status: "active"
updated: "2026-06-19"
---

# Hole Scene — alice-bootstrap Launcher Endpoints

## 1. Scene Overview

3 hole probes into the `alice-bootstrap` module.

**Case doc**: `docs/alice-agent-command/e2e/case-bootstrap.md`

## 2. Probe Map

```
┌─────────────────────────────────────┐
│          alice-bootstrap            │
│                                     │
│  BTS-P01  FacadeSelector.select()   │
│  BTS-P02  AppBootstrapper.bootstrap │
│  BTS-P03  IFacadeLauncher contract  │
└─────────────────────────────────────┘
```

## 3. How to Run

```bash
python docs/alice-bootstrap/e2e/hole_test_bootstrap.py
```
