---
title: "Prompt System — Procedural Memory"
summary: "Documentation for the agent prompt system: FreeMarker templates under ~/.alice/prompts/, file-based loading, fallback chain, prompt composite logic (6 layers), and integration with the PPAO lifecycle."
read_when:
  - "understanding or modifying the agent prompt system"
  - "adding new prompt templates (core_loop, micro_loop, planner)"
  - "debugging prompt loading order or caching behavior"
  - "overriding built-in prompts with user-defined versions"
  - "tracing the full prompt assembly chain — system → planner → micro loop"
scope:
  - "alice-core-agent/.../prompt/"
  - "~/.alice/prompts/"
  - "~/.alice/rules/"
status: "active"
updated: "2026-07-14"
---

# Prompt System — Procedural Memory

## Overview

The agent's **prompts** are a form of **Procedural Memory** — they encode *how* the agent should behave, think, and respond in different contexts. Each prompt is a FreeMarker (`.ftl`) template stored under `~/.alice/prompts/`.

### Loading Priority

```
1. ~/.alice/prompts/<name>.ftl      — User-defined (overrides built-in)
2. classpath: .../prompt/<name>.ftl  — Built-in default (fallback)
```

If a user places a `.ftl` file in `~/.alice/prompts/` with the same name as a built-in template, the user version takes precedence. This allows overriding prompts without recompiling.

## Prompt Templates

| Template | Name | Variables | Used By | Purpose |
|----------|------|-----------|---------|---------|
| `core_loop.ftl` | `core_loop` | `userTask`, `lastObservation`, `lastFeedback` | `buildSystemPrompt()`, `buildCoreLoopPrompt()` | PPAO macro loop system + user prompt |
| `micro_loop.ftl` | `micro_loop` | *(none — static)* | `buildMicroLoopSystemPrompt()` | Micro-ReAct rules (static system role) |
| `planner.ftl` | `planner` | `userTask`, `lastObservation`, `lastActionResult`, `error` | `buildPlannerPrompt()` | Planner strategy prompt |
| `micro_loop_error.ftl` | `micro_loop_error` | *(none — static)* | *(reserved)* | Tool failure fallback |

## Types

### `PromptDef` (`alice-core-agent/.../prompt/PromptDef.java`)

```java
public record PromptDef(
    String name,         // e.g. "core_loop"
    String source,       // file path or "classpath:..."
    String template,     // raw FreeMarker content
    Map<String, String> metadata  // optional
) {}
```

### `FilePromptLoader` (`alice-core-agent/.../prompt/FilePromptLoader.java`)

Lazily scans `~/.alice/prompts/` for all `*.ftl` files on first access. Results are cached until `reload()` is called. Thread-safe with double-checked locking.

## Prompt Composite Logic (6 Layers)

The agent assembles prompts from multiple sources in a layered chain. Each layer has a distinct role and lifecycle.

```
                    ┌──────────────────────────┐
                    │ Layer 1: System Prompt    │
                    │ buildSystemPrompt()       │
                    │ core_loop.ftl + rules/*   │
                    │ role="system" · once/sess │
                    └──────────┬───────────────┘
                               │
                               ▼
                    ┌──────────────────────────┐
                    │ Layer 2: Planner Prompt   │
                    │ buildPlannerPrompt()      │
                    │ planner.ftl               │
                    │ separate LLM call         │
                    │ returns "ANALYZE CODE"     │
                    └──────────┬───────────────┘
                               │
          (intent NOT passed to micro loop — only user task flows)
                               │
                               ▼
                    ┌──────────────────────────┐
                    │ Layer 3: Micro Loop Sys   │
                    │ buildMicroLoopSystemPrompt│
                    │ micro_loop.ftl · cached   │
                    │ role="system" · static    │
                    └──────────┬───────────────┘
                               │
                    ┌──────────▼───────────────┐
                    │ Layer 4: Micro Loop User  │
                    │ buildMicroUserContent()   │
                    │ dynamic per iteration     │
                    │ <read_files>              │
                    │ <user_task>               │
                    │ <tool_result>             │
                    └──────────┬───────────────┘
                               │
                    ┌──────────▼───────────────┐
                    │ Layer 5: Tool Accum       │
                    │ __action_log              │
                    │ accumulated per tool call │
                    │ truncated ~2000 chars     │
                    └──────────┬───────────────┘
                               │
                    ┌──────────▼───────────────┐
                    │ Layer 6: Error Prompt     │
                    │ buildMicroLoopErrorContent│
                    │ micro_loop_error.ftl      │
                    │ on tool failure only      │
                    └──────────────────────────┘
```

---

### Layer 1 — System Prompt (once per session)

**Source**: `core_loop.ftl` + `~/.alice/rules/*.md`
**Method**: `buildSystemPrompt()`

1. Load `core_loop.ftl` (user override or classpath fallback)
2. Extract `<system>...</system>` block via `extractSystemBlock()`
3. Append rules from `~/.alice/rules/*.md` wrapped in `<rules>` tags, sorted by priority
4. Cache and reuse for the entire session

**Caching**: `systemPromptCache` (static field) — cleared by `reloadFromDisk()`.

**Rendered example**:
```
You are Alice, an AI coding assistant operating inside pi, a coding agent harness.
...

<rules>
  <!-- General Coding Guidelines (high) -->
  ## Code Style
  - Follow Google Java Format for all Java files.
  ...
</rules>
```

---

### Layer 2 — Planner Prompt (per macro iteration)

**Source**: `planner.ftl`
**Method**: `buildPlannerPrompt()`

A **separate lightweight LLM call** to classify user intent. Returns plain text:

```
ANALYZE CODE
```
or:
```
ANSWER
```

**Template structure** (no outer `<planner>` wrapper):
```xml
<rules>
  <rule>Available intents: ANALYZE, SEARCH, CODE, GENERATE, ANSWER, FINISH</rule>
  ...
</rules>
<user_task>${userTask}</user_task>
<task>Respond with one or more words...</task>
```

Parsed by `FastPathStrategy.classifyIntent()`:
- Split by whitespace, uppercase, match against `Plan.Intent` enum
- First word → `primaryIntent`, all words → `intentChain`

> **⚠ Important**: The planner intent is **not passed into the micro loop**. The micro loop only receives the original raw user prompt as `<user_task>`. The LLM inside the micro loop has no awareness of whether the planner classified the task as ANALYZE, CODE, or anything else.

---

### Layer 3 — Micro Loop System Prompt (cached, static)

**Source**: `micro_loop.ftl`
**Method**: `buildMicroLoopSystemPrompt()`

Static system prompt, rendered once and cached. No FreeMarker variables.

**Template structure** (no outer `<micro_loop>` wrapper):
```xml
<rules>
  <rule>You have already read the files below. DO NOT re-read them.</rule>
  <rule>Your goal is to WRITE changes, not to keep reading.</rule>
  <rule>Based on the tool result and the user task, determine the next action.</rule>
  <rule>If the task requires code changes: read the relevant files ONCE, then write the fix.</rule>
  <rule>You can make multiple tool calls in a single response when they are independent.</rule>
  <rule>Use Function Calling (the structured tool_calls API) to invoke tools.</rule>
  <rule>BATCH all reads into ONE response. Never split reads across turns.</rule>
  <rule>NEVER re-read a file already in the conversation history.</rule>
</rules>
```

Sent as `role="system"` in every micro loop LLM call.

---

### Layer 4 — Micro Loop User Content (per iteration, dynamic)

**Source**: `buildMicroUserContent()` — pure string concatenation, no FreeMarker template

Three sections assembled each iteration:

```
<read_files>
main.py
setup.py
...</read_files>

<user_task>
init current proj to python
</user_task>

<tool_result>
Tool list_dir returned:
[empty directory]

Tool run returned:
Python 3.13.12
...</tool_result>
```

- `<read_files>`: populated from `__read_files` set in `AgentContext`
- `<user_task>`: original user prompt from context
- `<tool_result>`: accumulated `__action_log` string

---

### Layer 5 — Tool Result Accumulation (`__action_log`)

Accumulated by `accumulateActionLog()` / `handleSuccess()` methods.

Format:
```
Tool read_file returned:
<full-file-content>

Tool write_file succeeded.

Tool list_dir returned:
main.py
.gitignore
...
```

Rules:
- `write_file`: summary only (`"Tool write_file succeeded."`)
- All other tools: full output
- Truncated to ~2000 chars (keeps tail) to avoid overflowing context

---

### Layer 6 — Error Prompt (on tool failure)

**Source**: `micro_loop_error.ftl`
**Method**: `buildMicroLoopErrorContent()`

**Template structure** (no outer `<micro_loop>` wrapper):
```xml
<rules>
  <rule>The tool call below failed. Diagnose the error before retrying.</rule>
  <rule>Do not retry the same call with identical parameters.</rule>
</rules>
<tool_error>
  <tool>${toolName}</tool>
  <message>${errorMessage}</message>
</tool_error>
<instruction>The tool above failed. Fix the issue or use an alternative approach.</instruction>
```

---

### Output Structure Conventions

All templates use XML-style semantic tags with **no outer wrapper tags**:

| Tag | Layers | Purpose |
|-----|--------|---------|
| `<rules>` | All | Behavioral rules container |
| `<rule>` | All | Individual rule item |
| `<user_task>` | 2, 4 | Original user request |
| `<tool_result>` | 4 | Accumulated tool outputs |
| `<read_files>` | 3, 4 | Already-read file paths |
| `<tool_error>` | 6 | Failed tool details |
| `<instruction>` | 6 | Next-step instruction |
| `<system>` | 1 (core_loop only) | System identity (parsed by code) |

> **Removed**: Outer `<planner>` and `<micro_loop>` wrappers — the inner tags provide sufficient structure. Only `<system>` is parsed programmatically by `extractSystemBlock()`.

### Full Call Chain (per micro loop LLM invocation)

```
role="system"  →  buildMicroLoopSystemPrompt()   [cached, ~891 chars]
role="user"    →  buildMicroUserContent()         [dynamic per iteration]
                   ├── <read_files>...</read_files>
                   ├── <user_task>...</user_task>
                   └── <tool_result>...</tool_result>

tools          →  toolRegistry.allTools()         [Function Calling schema]
```

## Integration with `PromptManager`

```java
// System prompt — once per session
public static String buildSystemPrompt() {
    if (cached) return cache;
    PromptDef user = FilePromptLoader.getPrompt("core_loop");
    String sys = user != null
        ? extractSystemBlock(user.template())
        : extractSystemBlock(render(CORE_LOOP, data));
    return sys + "\n\n" + buildRules();  // append ~/.alice/rules/*.md
}

// Micro user content — per iteration, no FreeMarker
public static String buildMicroUserContent(
        String toolResult, String userTask, Set<String> alreadyReadFiles) {
    // Assembles: <read_files> + <user_task> + <tool_result>
    // Pure string concatenation — no template rendering
}
```

## File Structure

```
~/.alice/
├── prompts/
│   ├── core_loop.ftl          # Layer 1 — PPAO macro loop
│   └── micro_loop.ftl         # Layer 3 — Micro-ReAct rules
└── rules/
    └── *.md                   # Injected into Layer 1 (see docs/rule/)

classpath: .../prompt/
├── core_loop.ftl              # Built-in PPAO macro loop
├── micro_loop.ftl             # Built-in Micro-ReAct rules
├── micro_loop_error.ftl       # Layer 6 — error fallback
└── planner.ftl                # Layer 2 — planner prompt
```

## Reload

To reload prompts from disk at runtime (e.g., after editing a file):

```java
PromptManager.reloadFromDisk();
```

This clears all caches and re-scans both `~/.alice/prompts/` and `~/.alice/rules/`.

## Design Rationale

Prompts live **outside** the `ProceduralVault` (which stores SOPs with `procedure`/`pattern`/`toolName`) because:
- **Prompts** are FreeMarker templates requiring a rendering engine
- **Rules** are simple Markdown injected directly into system prompt
- Both are a form of **Procedural Memory** — they encode "how to do things"

The file-based approach (`~/.alice/prompts/` + `~/.alice/rules/`) allows users to customize agent behavior without code changes, while classpath fallbacks ensure the agent always has a working default.
