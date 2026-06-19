---
title: "Hole Scene — alice-model provider endpoints"
summary: "Module-level hole tests probing ModelProvider, Call lifecycle, ModelSupplier, config loading, and multi-provider routing."
read_when:
  - "running or debugging hole tests for alice-model"
scope:
  - "alice-model"
status: "active"
updated: "2026-06-19"
---

# Hole Scene — alice-model Provider Endpoints

## 1. Scene Overview

5 hole probes into the `alice-model` module.

**Case doc**: `docs/alice-agent-command/e2e/case-model.md`

## 2. Probe Map

```
┌─────────────────────────────────────┐
│            alice-model              │
│                                     │
│  MDL-P01  ModelProvider dispatch    │
│  MDL-P02  Call.execute() lifecycle  │
│  MDL-P03  ModelSupplier parse       │
│  MDL-P04  ConfigLoader load         │
│  MDL-P05  Multi-supplier routing    │
└─────────────────────────────────────┘
```

## 3. How to Run

```bash
python docs/alice-model/e2e/hole_test_model.py
```
