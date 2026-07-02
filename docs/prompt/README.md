---
title: "Prompt System — Procedural Memory"
summary: "Documentation for the agent prompt system: FreeMarker templates under ~/.alice/prompts/, file-based loading, fallback chain, and integration with the PPAO lifecycle."
read_when:
  - "understanding or modifying the agent prompt system"
  - "adding new prompt templates (core_loop, micro_loop, planner)"
  - "debugging prompt loading order or caching behavior"
  - "overriding built-in prompts with user-defined versions"
scope:
  - "alice-core-agent/.../prompt/"
  - "~/.alice/prompts/"
status: "active"
updated: "2026-07-03"
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

## Integration with `PromptManager`

```java
// PromptManager.java — pseudo-code flow
public static String buildSystemPrompt() {
    // 1. Check cache
    if (cached) return cache;

    // 2. Try user-defined: ~/.alice/prompts/core_loop.ftl
    PromptDef user = FilePromptLoader.getPrompt("core_loop");
    if (user != null) return extractSystemBlock(user.template());

    // 3. Fallback: classpath core_loop.ftl
    return extractSystemBlock(render(CORE_LOOP, data));
}
```

### System Prompt Injection into Rules

`buildSystemPrompt()` automatically appends rules from `~/.alice/rules/*.md` (see [Rule System](../rules/README.md)):

```
<system>...built-in agent identity...</system>

<rules>
  <!-- General Coding Guidelines (high) -->
  ... user-defined rules from ~/.alice/rules/ ...
</rules>
```

## File Structure

```
~/.alice/
├── prompts/
│   ├── core_loop.ftl       # PPAO macro loop
│   └── micro_loop.ftl      # Micro-ReAct loop rules
└── rules/
    └── *.md                # See docs/rule/
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
