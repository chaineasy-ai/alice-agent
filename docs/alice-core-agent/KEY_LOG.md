---
title: "AgentExecutor Key Log Reference"
summary: "Index of key INFO/WARN log markers emitted by AgentExecutor during PPAO cycle, for debugging and tracing agent execution flow"
read_when:
  - "debugging PPAO cycle (macro or micro)"
  - "tracing tool call or LLM inference dispatch"
  - "analyzing agent loop termination or circuit breaker"
status: "active"
---

# AgentExecutor Key Log Reference

All logs are emitted by `AgentExecutor.java` via SLF4J (`logger`). Root level is **INFO** in production (`logback.xml`).

## PPAO Macro Cycle (top-level phases)

| Log Marker | Phase | Level | What it shows |
|------------|-------|-------|---------------|
| `[PPAO] START Agent {id} maxIterations={n}` | Start | INFO | Agent PPAO loop begins |
| `[PPAO] Perceive: input={...}` | Perceive | INFO | Raw user input (truncated to 80 chars) |
| `[PPAO] Plan: iteration={n}` | Plan | INFO | Current macro iteration number |
| `[PPAO] Verify(Pre): checking action` | Verify/Pre | INFO | Pre-execution guardrail check |
| `[PPAO] Act: entering Micro-ReAct loop, initial action={action}` | Act | INFO | Macro Act phase starts |
| `[PPAO] Observe: collecting macro observation` | Observe | INFO | Collecting macro-level observation |
| `[PPAO] Verify(Post): auditing result` | Verify/Post | INFO | Post-execution guardrail audit |
| `[PPAO] Reflect: strategic review` | Reflect | INFO | Macro strategic review |
| `[PPAO] Agent {} early finish at iteration {}/{}` | Finish (early) | INFO | Loop ends early (e.g. [FINISH] marker) |
| `[PPAO] Agent {} normal finish at iteration {}/{}` | Finish (normal) | INFO | Loop ends normally after iteration limit |
| `[PPAO] Agent {} early finish at iteration {}/{}` | Finish (early) | INFO | Loop ends early (e.g. [FINISH] marker from micro loop)
| `[PPAO] Agent {} result: {}` | Finish (result) | INFO | Final result produced by agent |

## Micro-ReAct Loop (tactical execution)

| Log Marker | Phase | Level | What it shows |
|------------|-------|-------|---------------|
| `[Micro-ReAct] step depth={n}/{max} action={action}` | Step | DEBUG | Each micro iteration step |
| `[Micro-ReAct] circuit breaker triggered at depth={n}` | Circuit break | WARN | Max micro iterations exceeded, forced finish |
| `[Micro-ReAct/LLM] Calling model={id} promptLength={n}` | LLM request | INFO | LLM inference dispatched |
| `[Micro-ReAct/LLM] Response model={id} responseLength={n}` | LLM response | INFO | LLM response received |
| `[Micro-ReAct/LLM] error` | LLM error | ERROR | LLM call exception |
| `[Micro-ReAct/Reason] exit: stepResult type={} continueAction={}` | Reason | WARN | After dispatch, what the continue action is |
| `[Micro-ReAct/Reason] dispatching Continue's nextAction: type={} target={}` | Reason | WARN | Dispatch-instructed next action (e.g. tool→LLM) |
| `[Micro-ReAct/Reason] parsed from output: type={} target={}` | Reason | WARN | LLM output parsed to a tool call |
| `[Micro-ReAct/Reason] no next action, finishing micro loop` | Reason | WARN | No tool call found, exiting micro loop |
| `[Micro-ReAct] FINISH received, exiting micro loop` | Reason | DEBUG | Explicit FINISH action |

## Tool Dispatch

| Log Marker | Phase | Level | What it shows |
|------------|-------|-------|---------------|
| `[Dispatch/TOOL_CALL] target={} params={}` | Dispatch | INFO | Tool execution dispatched via ExecutionEngine |
| `[Micro-ReAct/Tool] no ExecutionEngine available` | Dispatch | WARN | ExecutionEngine not configured, revision issued |

## Tool Call Parser

| Log Marker | Phase | Level | What it shows |
|------------|-------|-------|---------------|
| `[ToolCallParser] output first300={}` | Parse | INFO | First 300 chars of LLM output to parse |
| `[ToolCallParser] FOUND markers in output, length={}` | Parse | INFO | Output contains [TOOL_CALL: or [FINISH] |
| `[ToolCallParser] NO markers in output, length={} first200={}` | Parse | INFO | Output has no tool call markers |
| `[ToolCallParser] MATCHED #{} tool={} params={}` | Parse | INFO | Regex matched tool call |
| `[ToolCallParser] NO MATCH on output first200={}` | Parse | INFO | No tool call regex match found |
| `[ToolCallParser] no output to parse (result is null/blank)` | Parse | INFO | Skip parsing (empty output) |
| `[ToolCallParser] regex compile failed: {msg}` | Parse | ERROR | Regex compilation error |
| `[Verify/Pre] blocked action={}` | Guardrail | WARN | Pre-verify blocked an action |
| `[Verify/Post] audit failed, forcing revision` | Guardrail | WARN | Post-verify audit failed |
