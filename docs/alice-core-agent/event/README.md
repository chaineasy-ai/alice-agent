---
title: "Agent Event System — PPAO Event Flow"
summary: "AgentExecutor event system: fireOnObserve, fireOnAction, fireOnThought, __system_event context key, __action_log accumulation, and TUI ObserveBlock integration."
read_when:
  - "adding new events to PPAO loop"
  - "debugging TUI ObserveBlock display"
  - "understanding how tool results flow to LLM"
scope:
  - "alice-core-agent"
  - "alice-facade-tui"
status: "active"
updated: "2026-06-27"
---

# Agent Event System

## Overview

The AgentExecutor emits structured events during the PPAO loop via the **Observer pattern** (`AgentEventListener`). These events serve two purposes:

1. **TUI display** — `fireOnObserve` / `fireOnAction` / `fireOnThought` drive the TUI's ObserveBlock, ActionBlock, ThoughtBlock
2. **LLM context** — `__action_log`, `__system_event`, `lastObservation` store execution data for subsequent LLM inference

```
┌──────────────────────────────────────────────────────────┐
│                   AgentExecutor                          │
│                                                          │
│  PPAO Loop                                               │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐ │
│  │ Perceive │→│  Plan    │→│  Act     │→│ Observe │→│ ...
│  └──────────┘  └──────────┘  └──────────┘  └─────────┘ │
│                                      │          │       │
│  Events:                    fireOnAction    fireOnObserve│
│                             fireOnThought  __system_event│
│                                            __action_log  │
└──────────────────────────────────────────────────────────┘
```

---

## 1. Event Types

### 1.1 `fireOnAction(String target, Map<String, Object> params)`

Fired when a tool call is dispatched.

| Param | Description |
|-------|-------------|
| `target` | Tool name (e.g., `read_file`, `list_dir`) |
| `params` | Tool parameters |

**Trigger**: `AgentExecutor.dispatchToolCall()` line 873

### 1.2 `fireOnThought(String reasoning)`

Fired when LLM returns reasoning content.

| Param | Description |
|-------|-------------|
| `reasoning` | LLM's reasoning_text (extracted from raw metadata) |

**Trigger**: `AgentExecutor.dispatchLlmInference()` line 780

### 1.3 `fireOnObserve(String rawData, String summary, long elapsedMs)`

Fired to deliver observation data to the TUI ObserveBlock.

| Param | Description |
|-------|-------------|
| `rawData` | Observation data (tool result or system tip) |
| `summary` | Short summary string |
| `elapsedMs` | Execution time (0 for aggregated events) |

**Trigger points**:

| Location | Condition | `rawData` |
|----------|-----------|-----------|
| `dispatchToolCall` line 934 | Per-tool execution | Full tool return data |
| Macro `observe()` | `__action_log` exists | `[System] N tool calls executed` |

---

## 2. LLM Context Keys

The AgentContext stores execution data that persists across PPAO iterations. These keys are visible to subsequent LLM calls via the context map.

### 2.1 `__action_log`

Accumulated tool execution results across the Micro-ReAct loop.

```
Tool list_dir returned:
alice-memory-vault/
todos/
tests/
...

Tool read_file returned:
# Alice Agent
...
```

**Format**: Each tool result separated by `\n\n`.

**Consumed by**:
- Macro `observe()` — combines into `lastObservation`
- `dispatchToolCall()` — appends per-tool result
- Circuit breaker — preserves before termination

### 2.2 `__system_event`

System-level event message for LLM awareness.

| Scenario | Value |
|----------|-------|
| Circuit breaker | `[System] Circuit breaker: max depth (10) reached after 6 tool calls` |
| Macro Observe | `[System] 6 tool calls executed during this iteration` |

**Format**: `[System] <description>`

### 2.3 `lastObservation`

The combined Observation object stored after tool execution.

- **Per-tool**: `Observation.success("Tool X returned: ...")` (set in Micro-ReAct Reason)
- **Aggregated**: `Observation.success(actionLog)` (set in macro Observe)

### 2.4 `lastActionResult`

Short string summary of the last action result.

```
"Tool results: 1234 chars"
"Micro-ReAct completed with 10 steps"
```

---

## 3. Event Flow Diagram

### Normal Flow (N tool calls)

```
Depth 0: LLM → tool_call[list_dir]
  ├── fireOnAction("list_dir", {path:"."})
  ├── dispatchToolCall → fireOnObserve(rawData=list_dir result)
  └── ctx.__action_log += "Tool list_dir returned:..."

Depth 1: LLM → tool_call[read_file, read_file, ...]
  ├── fireOnAction("read_file", {path:"README.md"})
  ├── dispatchToolCall → fireOnObserve(rawData=README content)
  ├── ctx.__action_log += "Tool read_file returned:..."
  ├── fireOnAction("read_file", {path:"build.gradle"})
  ├── dispatchToolCall → fireOnObserve(rawData=build.gradle content)
  └── ctx.__action_log += "Tool read_file returned:..."

...

Macro Observe:
  ├── ctx.__system_event = "[System] 6 tool calls executed during this iteration"
  ├── ctx.lastObservation = Observation.success(actionLog)
  └── fireOnObserve("[System] 6 tool calls executed", "...", 0)
```

### Circuit Breaker Flow

```
Depth N: circuit breaker at maxDepth (10)
  ├── ctx.__system_event = "[System] Circuit breaker: max depth (10) reached..."
  ├── ctx.lastObservation = Observation.success(actionLog)
  └── ctx.result = actionLog  // 所有已执行工具的结果
```

---

## 4. TUI ObserveBlock Mapping

| Event | TUI Block | Display |
|-------|-----------|---------|
| `fireOnAction` | ActionBlock | Tool name + params |
| `fireOnThought` | ThoughtBlock | LLM reasoning |
| `fireOnObserve` (per-tool) | ObserveBlock | Tool return data |
| `fireOnObserve` (aggregated) | ObserveBlock | `[System] N tool calls executed` |

---

## 5. Adding New Events

To add a new event:

1. Call `fireOnObserve(rawData, summary, elapsedMs)` in the appropriate PPAO phase
2. Store context data using `ctx.put("key", value)` for LLM visibility
3. Use `[System]` prefix for system-generated messages vs raw tool data
