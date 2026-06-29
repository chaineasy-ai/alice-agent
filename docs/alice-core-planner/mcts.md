---
title: "MCTS Planner — Monte Carlo Tree Search for Multi-Step Decision"
summary: "Design specification for the MCTS-based intelligent planning agent — UCB1 tree search, 4-step iteration loop, per-iteration logging, optimal next-action selection, and search tree summary output"
read_when:
  - "implementing or modifying the MCTS algorithm in SlowPathStrategy"
  - "debugging ThinkingTree, ThinkingNode, UCB1 selection, or reward backpropagation"
  - "understanding the 4-step iteration (Selection/Expansion/Simulation/Backpropagation) contract"
  - "reviewing per-iteration logging or search tree summary output format"
scope:
  - "alice-core-planner"
status: "active"
updated: "2026-06-30"
---

# MCTS Planner — Monte Carlo Tree Search

## Overview

The MCTS (Monte Carlo Tree Search) planner is the core of the **SlowPath** (System 2) strategy. It
decomposes complex user requests into sub-objectives through iterative tree search, selecting the
optimal next action based on UCB1 confidence bounds.

```
 Role: MCTS-based Intelligent Planning Agent
 Core Algorithm: Monte Carlo Tree Search + UCB1 Confidence Bound
         Used for: Multi-step tool decisions, long-task decomposition
```

## State Definition

| Component | Description |
|-----------|-------------|
| **Root node** | Current task objective |
| **Child node** | Single executable tool / sub-step |
| **Terminal node** | Task completed / failed |
| **`visit_count`** | Number of times the node has been visited |
| **`total_reward`** | Cumulative reward sum across all visits |
| **`avg_reward`** | Average score = `total_reward / visit_count` |
| **`parent`** | Parent node reference (for backpropagation) |
| **`childs`** | List of sub-step nodes |

## Four-Step Iteration Loop

Each MCTS iteration follows these four phases:

```
                    ┌─────────────┐
                    │  Selection   │  ← traverse from root, pick max UCB
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │  Expansion   │  ← generate legal untried actions as children
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │  Simulation  │  ← fast-rollout full execution chain, score 0~100
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │ Backprop     │  ← propagate score up the chain
                    └─────────────┘
```

### 1. Selection (选择)

Traverse from root downward. For each node, compute **UCB1** (Upper Confidence Bound):

```
UCB = avg_reward + C * sqrt( ln(parent_visit) / child_visit )
```

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `C` | `√2` | Exploration-exploitation balance |
| `avg_reward` | `total_reward / visit_count` | Exploitation term |
| `sqrt(ln(parent_visit)/child_visit)` | — | Exploration bonus for under-visited nodes |

**Rules:**
- Prefer the node with the **highest UCB** value
- Continue traversing until reaching a node with **unexpanded branches**
- Unvisited children get `UCB = Double.MAX_VALUE` (priority for exploration)

### 2. Expansion (扩展)

At the selected leaf node, generate all **legal, untried** tool actions / sub-tasks as new child
nodes. The candidate set varies by context:

| Candidate | Trigger | Description |
|-----------|---------|-------------|
| `LLM_INFERENCE` | Always | Use reasoning model to generate response |
| `TOOL_CALL` | `availableTools` present | Execute each available tool |
| `OBSERVE` | Always | Observe environment changes |
| `REVISION` | `lastFeedback` present | Revise based on feedback |

### 3. Simulation (模拟)

From the new child node, perform a **fast rollout** simulating the full execution chain to estimate
the final task score.

- **Score range**: 0 ~ 100 (higher is better)
- **Constraint**: All tool calls must follow usage rules — illegal invocations are prohibited

Current heuristic simulator:
```java
double baseReward = 1.0;
if (currentPrompt.length() > 0) {
    baseReward += Math.min(currentPrompt.length() / 100.0, 2.0);
}
// Result: ~1.0–3.0 (to be expanded to 0~100 scale)
```

> **Note**: The current heuristic is a placeholder. Future versions should replace it with
> real model evaluation or a dedicated validator.

### 4. Backpropagation (反向传播)

Propagate the simulation score up the ancestor chain:

```
for each ancestor from child → root:
    visit_count += 1
    total_reward += score
    avg_reward = total_reward / visit_count
```

## Output Specification

### Per-Iteration Logging

After each iteration, print:

```
Iteration {N}/{MAX} | selected: ROOT → {node1} → {node2} → ... → {leaf}
  ROOT: visits={N}, avg_reward={R}
  ├─ {action}: visits={N}, avg_reward={R}, UCB={U}
  ├─ {action}: visits={N}, avg_reward={R}, UCB={U} ← HIGH UCB (low visits)
  └─ {action}: visits=0, avg_reward=0, UCB=MAX ← unexplored
```

**Markers:**
- 🟢 **High-reward mature branch**: `visits` ≥ threshold, `avg_reward` above average
- 🟡 **Low-visit exploration branch**: `visits` small but UCB high due to exploration bonus

### Final Output

After **10 iterations**, stop searching and produce:

1. **Next execution action**: The root's child with the **highest `avg_reward`** → output as a single
   `Plan.Step` (the immediate next action, not the entire tree path)
2. **MCTS search tree summary**: Tree statistics included in `Plan.metadata`:
   - `totalNodes` — number of nodes explored
   - `treeDepth` — maximum tree depth
   - `iterations` — total iterations run
   - `rootChildren` — number of root-level candidates
   - `bestAction` — selected action type and target
   - `bestAvgReward` — average reward of the best child

## Algorithm Flow (Full)

```
Input:  Agent task + Available tools list

1. Create root node with task state
2. For iteration = 1..10:
   a. SELECTION: traverse from root using UCB1 → find leaf with unexpanded branches
   b. EXPANSION: generate legal child actions at leaf
   c. SIMULATION: fast-rollout from a new child, compute score (0~100)
   d. BACKPROP: propagate score to all ancestors
   e. Log: iteration round, selected path, UCB values, visits, avg_reward
3. Select root child with highest avg_reward as NEXT action
4. Output: Plan[1 action step + FINISH] + MCTS tree summary in metadata
```

## Current Implementation Status

| Component | Status | File |
|-----------|--------|------|
| State/Node definition | ✅ Implemented | `ThinkingNode.java` |
| 4-step iteration | ✅ Implemented | `ThinkingTree.java:mctsIteration()` |
| UCB1 selection | ✅ Implemented | `ThinkingNode.java:uct()` |
| Expansion candidates | ⚠️ Needs tuning | `SlowPathStrategy.java:runMcts()` |
| Simulator (heuristic) | ⚠️ Scale mismatch (1~3 vs 0~100) | `SlowPathStrategy.java:runMcts()` |
| Per-iteration logging | ❌ Not implemented | `SlowPathStrategy.java` |
| Best-child-by-avg_reward | ❌ Currently uses `bestPath()` | `SlowPathStrategy.java:decide()` |
| Iteration count | ⚠️ Default 20, spec says 10 | `SlowPathStrategy.java` |
| Tree summary in output | ❌ Not implemented | `SlowPathStrategy.java` |

## Visual Example

For input `"分析当前项目"` (analyze current project), MCTS explores:

```
                      ROOT (分析当前项目)
                     /                 \
            LLM_INFERENCE              OBSERVE
            (gpt-4o, visits=6,        (ENV, visits=4,
             avg_r=1.8, UCB=2.1)       avg_r=1.2, UCB=1.9)
                  │
            LLM_INFERENCE (2nd pass)
            (visits=3, avg_r=2.1)

Selected next action: LLM_INFERENCE (avg_reward=1.8)
```

## Traceability

| Source File | Component |
|-------------|-----------|
| `SlowPathStrategy.java` | Top-level strategy: decide(), runMcts(), per-iteration logging |
| `ThinkingTree.java` | Tree operations: mctsIteration(), expand(), select(), backpropagate() |
| `ThinkingNode.java` | Node data: state, action, reward, visits, UCB/UCT computation |
| `StrategySelector.java` | Complexity assessment → route to SlowPath |
| `PlannerService.java` | Integration entry point |
