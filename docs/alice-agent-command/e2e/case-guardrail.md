---
title: "E2E Case — alice-guardrail endpoints"
summary: "Hole test specification for alice-guardrail module — GuardrailService, PolicyEngine, HallucinationDetector, PermissionSandboxValidator public API boundaries."
read_when:
  - "implementing or modifying hole tests for alice-guardrail"
scope:
  - "alice-agent-command"
  - "alice-guardrail"
status: "active"
updated: "2026-06-19"
---

# E2E Case — alice-guardrail (Hole Test)

## 1. Purpose

Probe the **alice-guardrail** module's public API boundary — plan verification, result validation, policy evaluation, and safety filters.

## 2. Hole Design

```
Plan ──► GuardrailService.verifyPlan() ──► AuditResult
           ● (GRD-P01)      ─── legal/illegal plans
Observation ──► GuardrailService.verifyResult() ──► AuditResult
                  ● (GRD-P02)
Target ──► PolicyEngine.evaluate() ──► AuditResult
            ● (GRD-P03)
Observation ──► HallucinationDetector.check() ──► AuditResult
                  ● (GRD-P04)
Action ──► PermissionSandboxValidator.check() ──► AuditResult
            ● (GRD-P05)
```

## 3. Hole Tests

### GRD-P01: `GuardrailService.verifyPlan()` pre-validation

| Field | Value |
|-------|-------|
| **Input** | Legal plan + illegal plan (e.g. plan with dangerous action) |
| **Expected** | Legal → `passed=true`, Illegal → `passed=false` |
| **Assertion** | `audit1.passed == true && audit2.passed == false` |

### GRD-P02: `GuardrailService.verifyResult()` post-validation

| Field | Value |
|-------|-------|
| **Input** | Valid Observation + Observation with hallucination markers |
| **Expected** | Valid → passed, Hallucinated → failed |
| **Assertion** | `audit.passed` correlates with input validity |

### GRD-P03: `PolicyEngine.evaluate()` policy matching

| Field | Value |
|-------|-------|
| **Input** | Target matching defined policy rules |
| **Expected** | Returns `AuditResult` with correct risk level |
| **Assertion** | `result.risk == EXPECTED_RISK` |

### GRD-P04: `HallucinationDetector` detection

| Field | Value |
|-------|-------|
| **Input** | Normal Observation + contradictory Observation |
| **Expected** | Contradictory → detected as hallucination |
| **Assertion** | `detector.check(contradictory).passed == false` |

### GRD-P05: `PermissionSandboxValidator` access control

| Field | Value |
|-------|-------|
| **Input** | Bounded action (read /tmp) + out-of-bounds action (read /etc/shadow) |
| **Expected** | Bounded → passed, OOB → rejected |
| **Assertion** | `validator.check(oob).passed == false` |
