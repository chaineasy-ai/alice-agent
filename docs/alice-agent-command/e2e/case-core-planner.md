---
title: "E2E Case — alice-core-planner endpoints"
summary: "Hole test specification for alice-core-planner module — PlannerService, DecisionStrategy, WorldModel public API boundaries."
read_when:
  - "implementing or modifying hole tests for alice-core-planner"
scope:
  - "alice-agent-command"
  - "alice-core-planner"
status: "active"
updated: "2026-06-19"
---

# E2E Case — alice-core-planner (Hole Test)

## 1. Purpose

Probe the **alice-core-planner** module's public API boundary — planning service entry, strategy selection, and world model prediction.

## 2. Hole Design

```
AgentContext ──► PlannerService.plan() ──► Plan
                    ● (PLN-P01)
                    ├── FastPathStrategy.decide() ● (PLN-P02)
                    └── SlowPathStrategy.decide() ● (PLN-P03)
State+Action ──► WorldModel.predict() ──► Observation
                    ● (PLN-P04)
```

## 3. Hole Tests

### PLN-P01: `PlannerService.plan()` entry point

| Field | Value |
|-------|-------|
| **Target** | `PlannerService.plan(AgentContext)` |
| **Input** | Mock `AgentContext` with valid session |
| **Expected** | Returns non-null `Plan` |
| **Assertion** | `plan != null` |

### PLN-P02: `FastPathStrategy.decide()` quick decision

| Field | Value |
|-------|-------|
| **Target** | `FastPathStrategy.decide(AgentContext)` |
| **Input** | Simple context with known goal |
| **Expected** | Returns `Plan` with 1-2 steps |
| **Assertion** | `plan.steps.size() >= 1` |

### PLN-P03: `SlowPathStrategy.decide()` complex decision

| Field | Value |
|-------|-------|
| **Target** | `SlowPathStrategy.decide(AgentContext)` |
| **Input** | Complex/multi-step context |
| **Expected** | Returns `Plan` with multi-step reasoning |
| **Assertion** | `plan.steps.size() > 1` or fallback to fast path |

### PLN-P04: `WorldModel.predict()` prediction

| Field | Value |
|-------|-------|
| **Target** | `WorldModel.predict(State, Action)` |
| **Input** | Known state + action pair |
| **Expected** | Returns non-null `Observation` |
| **Assertion** | `observation != null` |
