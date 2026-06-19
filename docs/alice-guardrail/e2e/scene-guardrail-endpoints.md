---
title: "Hole Scene — alice-guardrail validation endpoints"
summary: "Module-level hole tests probing GuardrailService, PolicyEngine, HallucinationDetector, PermissionSandboxValidator public API boundaries."
read_when:
  - "running or debugging hole tests for alice-guardrail"
scope:
  - "alice-guardrail"
status: "active"
updated: "2026-06-19"
---

# Hole Scene — alice-guardrail Validation Endpoints

## 1. Scene Overview

5 hole probes into the `alice-guardrail` module. Note: this module currently has **0 unit tests**, so hole tests are particularly valuable here.

**Case doc**: `docs/alice-agent-command/e2e/case-guardrail.md`

## 2. Probe Map

```
┌─────────────────────────────────────┐
│          alice-guardrail            │
│                                     │
│  GRD-P01  GuardrailService verify   │
│  GRD-P02  GuardrailService post     │
│  GRD-P03  PolicyEngine evaluate     │
│  GRD-P04  HallucinationDetector     │
│  GRD-P05  PermissionSandbox check   │
└─────────────────────────────────────┘
```

## 3. How to Run

```bash
python docs/alice-guardrail/e2e/hole_test_guardrail.py
```
