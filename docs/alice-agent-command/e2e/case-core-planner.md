---
title: "E2E Case — alice-core-planner endpoints"
summary: "Hole test specification for alice-core-planner module — PlannerService, StaticPlanner, ThinkingTree, TokenBudget, and StrategySelector public API boundaries."
read_when:
  - "implementing or modifying hole tests for alice-core-planner"
scope:
  - "alice-agent-command"
  - "alice-core-planner"
status: "active"
updated: "2026-06-20"
---

# E2E Case — alice-core-planner (Hole Test)

## 1. Purpose

Probe the **alice-core-planner** module's public API boundary — aggregation root, value objects, MCTS engine, budget control, SOP matching, and strategy routing.

## 2. Hole Design

```
 ┌────────────────────────────────────────────────────────────────┐
 │                    alice-core-planner                           │
 │                                                                │
 │  PLN-P01  PlannerService.plan(Map)                ──► Plan    │
 │  ├── result check → FINISH                                     │
 │  ├── StaticPlanner.plan(Map)    (SOP match)   ──► STATIC Plan │
 │  ├── SopRegistry.match(prompt)                  ◄─ Template    │
 │  │                                                                │
 │  └── StrategySelector.select(Map)                               │
 │         ├── FastPathStrategy.decide(Map)      ──► FAST_PATH    │
 │         └── SlowPathStrategy.decide(Map)      ──► SLOW_PATH    │
 │                └── ThinkingTree.mctsIterations()                │
 │                       ├── ThinkingNode (S/A/V + UCT)            │
 │                       └── TokenBudget.consume()                 │
 │                                                                │
 │  PLN-P05  TokenBudget                          ──► isExhausted │
 │  PLN-P06  ThinkingTree / ThinkingNode          ──► bestPath()  │
 │  PLN-P07  StaticPlanner / SopRegistry          ──► STATIC Plan │
 └────────────────────────────────────────────────────────────────┘
```

## 3. Hole Tests

### PLN-P01: `PlannerService.plan(Map)` — root entry

| Field | Value |
|-------|-------|
| **Target** | `PlannerService.plan(Map<String, Object>)` |
| **Input** | Map with `prompt: "Hello"` |
| **Expected** | Returns non-null `Plan` with ≥1 step |
| **Assertion** | `plan != null && plan.steps().size() >= 1` |

### PLN-P02: `FastPathStrategy.decide(Map)` — System 1

| Field | Value |
|-------|-------|
| **Target** | `FastPathStrategy.decide(Map<String, Object>)` |
| **Input** | Simple context `[prompt: "What is Java?"]` |
| **Expected** | Returns `FAST_PATH` Plan with LLM_INFERENCE + FINISH |
| **Assertion** | `plan.type() == FAST_PATH && plan.steps()[0].actionType() == "LLM_INFERENCE"` |

### PLN-P03: `SlowPathStrategy.decide(Map)` — System 2 with MCTS

| Field | Value |
|-------|-------|
| **Target** | `SlowPathStrategy.decide(Map<String, Object>)` |
| **Input** | Complex context `[prompt: "Complex multi-step analysis task"]` |
| **Expected** | Returns `SLOW_PATH` Plan with tree metadata |
| **Assertion** | `plan.type() == SLOW_PATH && (int)plan.metadata().get("treeNodes") > 0` |

### PLN-P04: `StrategySelector.select(Map)` — complexity router

| Field | Value |
|-------|-------|
| **Target** | `StrategySelector.select(Map<String, Object>)` |
| **Input** | Short prompt vs long prompt |
| **Expected** | Short → fast path; long → slow path |
| **Assertion** | `plan.type() == FAST_PATH` for short; `plan.type() == SLOW_PATH` for long |

### PLN-P05: `TokenBudget` — budget guard

| Field | Value |
|-------|-------|
| **Target** | `TokenBudget.consume()` / `isExhausted()` |
| **Input** | Budget of(3, 10) × 3 consumes |
| **Expected** | After 3 consumes → `isExhausted() == true` |
| **Assertion** | `budget.isExhausted() == true` |

### PLN-P06: `ThinkingTree` MCTS operations

| Field | Value |
|-------|-------|
| **Target** | `ThinkingTree.expand()` / `backpropagate()` / `bestPath()` |
| **Input** | Tree with root state + generated children |
| **Expected** | Expand adds nodes; backpropagate updates ancestors; bestPath returns path |
| **Assertion** | `tree.nodeCount() == 3` after expand; root reward updated after backprop |

### PLN-P07: `StaticPlanner` / `SopRegistry` — SOP matching

| Field | Value |
|-------|-------|
| **Target** | `SopRegistry.match()` / `StaticPlanner.plan()` |
| **Input** | Registered SOP with keywords `["search", "find"]`, prompt "Please search for documents" |
| **Expected** | Returns `STATIC` Plan with matched SOP steps |
| **Assertion** | `plan.type() == STATIC && plan.steps()[0].target() == "search_web"` |
