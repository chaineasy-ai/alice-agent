---
title: "Hole Scene — alice-core-planner planner endpoints"
summary: "Module-level hole tests probing PlannerService, StrategySelector, ThinkingTree, TokenBudget, StaticPlanner, and SopRegistry public API boundaries."
read_when:
  - "running or debugging hole tests for alice-core-planner"
scope:
  - "alice-core-planner"
status: "active"
updated: "2026-06-20"
---

# Hole Scene — alice-core-planner Planner Endpoints

## 1. Scene Overview

7 hole probes into the `alice-core-planner` module, covering all major inbound entities.

**Case doc**: `docs/alice-agent-command/e2e/case-core-planner.md`

## 2. Probe Map

```
┌───────────────────────────────────────────────────────┐
│                 alice-core-planner                     │
│                                                       │
│  PLN-P01  PlannerService.plan(Map)  ──► Plan         │
│  PLN-P02  FastPathStrategy.decide() ──► FAST_PATH    │
│  PLN-P03  SlowPathStrategy.decide() ──► SLOW_PATH    │
│  PLN-P04  StrategySelector.select() ──► fast/slow    │
│  PLN-P05  TokenBudget.consume()     ──► isExhausted  │
│  PLN-P06  ThinkingTree MCTS ops     ──► bestPath()   │
│  PLN-P07  StaticPlanner/SopRegistry ──► STATIC Plan  │
└───────────────────────────────────────────────────────┘
```

## 3. Hole Status

| Hole | Target | Source Test | Status |
|------|--------|-------------|--------|
| PLN-P01 | `PlannerService.plan()` | `PlannerServiceSpec` "handle simple prompt" | 🟩 GREEN |
| PLN-P02 | `FastPathStrategy.decide()` | `PlannerServiceSpec` "generate fast path plan" | 🟩 GREEN |
| PLN-P03 | `SlowPathStrategy.decide()` | `PlannerServiceSpec` "generate MCTS plan" | 🟩 GREEN |
| PLN-P04 | `StrategySelector.select()` | `PlannerServiceSpec` "route simple/complex tasks" | 🟩 GREEN |
| PLN-P05 | `TokenBudget` | `PlannerServiceSpec` "enforce limits / track consumption" | 🟩 GREEN |
| PLN-P06 | `ThinkingTree` / `ThinkingNode` | `PlannerServiceSpec` "expand / backpropagate / bestPath" | 🟩 GREEN |
| PLN-P07 | `StaticPlanner` / `SopRegistry` | `PlannerServiceSpec` "register and match templates / generate plan from SOP" | 🟩 GREEN |

## 4. How to Run

```bash
python docs/alice-core-planner/e2e/hole_test_planner.py
```

All holes are verified via the single `PlannerServiceSpec` test class.
