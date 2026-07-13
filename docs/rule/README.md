---
title: "Rule System — Procedural Memory"
summary: "Documentation for the agent rule system: Markdown rule files under ~/.alice/rules/, filename-based context routing (reserved words), status enable/disable, priority ordering, and integration with all prompt layers."
read_when:
  - "adding or modifying agent behavioral rules"
  - "understanding how rules are loaded, routed, and injected"
  - "configuring rule priority, enablement, and context targeting"
  - "debugging why rules aren't appearing in a specific prompt layer"
  - "disabling a rule without deleting the file"
  - "creating new rule files for a specific prompt context (system, micro_loop, planner, error)"
scope:
  - "alice-core-agent/.../prompt/"
  - "~/.alice/rules/"
status: "active"
updated: "2026-07-14"
---

# Rule System — Procedural Memory

## Overview

**Rules** are a form of **Procedural Memory** — they encode "how to do things" as human-readable Markdown instructions that are automatically injected into the agent's prompt across all layers. Each rule file's **filename** (without `.md`) acts as a **reserved word** that determines which prompt layers it applies to.

## File Location

```
~/.alice/rules/
├── system.md          # System prompt layer
├── micro_loop.md      # Micro-ReAct loop layer
├── planner.md         # Planner prompt layer
├── error.md           # Error recovery layer
├── coding.md          # All layers (non-reserved name)
└── ...                # Any name, auto-discovered
```

## Routing by Filename (Reserved Words)

The filename determines which prompt layer(s) the rule is injected into:

| Rule file | Filename reserved? | Injected into |
|-----------|-------------------|---------------|
| `system.md` | ✅ `system` | System prompt only |
| `micro_loop.md` | ✅ `micro_loop` | Micro-ReAct loop only |
| `planner.md` | ✅ `planner` | Planner prompt only |
| `error.md` | ✅ `error` | Error recovery prompt only |
| `coding.md` | ❌ | **All** prompt layers |

This is defined by `RESERVED_CONTEXTS` in `PromptManager`:

```java
private static final Set<String> RESERVED_CONTEXTS =
    Set.of("system", "planner", "micro_loop", "error");
```

## Rule File Format

Each rule file is a Markdown document with optional YAML front-matter:

```markdown
---
title: General Coding Guidelines
priority: high
status: enabled
---

## Code Style
- Follow Google Java Format for all Java files.
- Use records for immutable data carriers.

## Testing
- Write Spock (Groovy) tests for all new features.
- Tests must follow Red → Green → Refactor.
```

### Front-Matter Fields

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `title` | ❌ | filename | Human-readable title |
| `priority` | ❌ | `medium` | Sort order (`high` > `medium` > `low`) |
| `status` | ❌ | `enabled` | Enablement: `enabled` or `disabled` |

A rule with `status: disabled` is parsed and stored in the cache, but **skipped during prompt assembly**. This allows toggling rules on/off without deleting the file.

## Type: `RuleDef`

```java
public record RuleDef(
    String name,              // filename without .md — the reserved word
    String source,            // full path
    String title,             // from front-matter or filename
    String priority,          // "high" | "medium" | "low"
    List<String> appliesTo,   // (reserved for future use)
    String status,            // "enabled" | "disabled"
    String content            // Markdown body (front-matter stripped)
) {}
```

## Reserved Default Rules (Built-in Fallback)

When no file-based rules match a context, `PromptManager` falls back to **hardcoded reserved defaults**:

| Context | Reserved defaults provide |
|---------|--------------------------|
| `system` | 8 rules: read before modify, complete writes, no repeats, batch reads, track reads, etc. |
| `micro_loop` | 8 rules: no re-reads, goal is to write, batch all reads, never re-read, etc. |
| `planner` | 4 rules: intent classification instructions |
| `error` | 2 rules: diagnose before retrying, no identical retries |

These are defined in `PromptManager.RESERVED_RULES` and ensure the agent always has basic behavioral rules even if all rule files are deleted.

### Fallback chain

```
1. ~/.alice/rules/<reserved_name>.md (if exists + enabled)
2. PromptManager.RESERVED_RULES (built-in hardcoded defaults)
```

## Loading and Caching

`FilePromptLoader` lazily scans `~/.alice/rules/` for all `*.md` files on first access. Results are cached until `reload()` is called.

```java
FilePromptLoader loader = new FilePromptLoader();
List<RuleDef> rules = loader.getAllRules();  // sorted by priority
```

### Priority Ordering

Rules are sorted by priority before injection:
1. `high` — always injected first
2. `medium` — default
3. `low` — injected last

Within the same priority level, rules maintain filesystem scan order (alphabetical by filename).

## Injection Across Prompt Layers

Each prompt-building method calls `buildRules(context)` with its own context key:

| Method | Context | Injects rules from |
|--------|---------|-------------------|
| `buildSystemPrompt()` | `"system"` | `system.md` + non-reserved files |
| `buildMicroLoopSystemPrompt()` | `"micro_loop"` | `micro_loop.md` + non-reserved files |
| `buildPlannerPrompt()` | `"planner"` | `planner.md` + non-reserved files |
| `buildMicroLoopErrorContent()` | `"error"` | `error.md` + non-reserved files |

### Routing logic

```java
// PromptManager.buildRules(context) — simplified
for (RuleDef rule : allRules) {
    if ("disabled".equals(rule.status())) continue;

    boolean isReserved = RESERVED_CONTEXTS.contains(rule.name());
    if (context != null && isReserved && !context.equals(rule.name())) {
        continue;  // reserved name + wrong context → skip
    }

    sb.append("  <!-- ").append(rule.title())
        .append(" (").append(rule.priority()).append(") -->\n");
    sb.append(rule.content()).append("\n\n");
}
```

### Rendered output example (system prompt)

```
<system>You are Alice...</system>

<rules>
  <!-- System Prompt Rules (high) -->
  - Always use `read_file` to examine a file before modifying it.
  - ...

  <!-- General Coding Guidelines (high) -->
  ## Code Style
  - Follow Google Java Format for all Java files.
  ...
</rules>
```

### Rendered output example (micro loop system prompt)

```
<rules>
  <!-- Micro-ReAct Loop Rules (high) -->
  - You have already read the files below. DO NOT re-read them.
  - ...

  <!-- General Coding Guidelines (high) -->
  ## Code Style
  - Follow Google Java Format for all Java files.
  ...
</rules>
```

Note that `coding.md` (non-reserved) appears in **both** layers.

## Reload

```java
PromptManager.reloadFromDisk();  // re-scans ~/.alice/rules/ + ~/.alice/prompts/
```

This clears all caches and re-scans both directories. The new rules (and status changes) take effect on the next prompt construction.

## Examples

### Add a system-level rule

Create `~/.alice/rules/system.md`:

```markdown
---
title: System Prompt Rules
priority: high
status: enabled
---

- Always use `read_file` to examine a file before modifying it.
- `write_file` content must contain the COMPLETE file.
- NEVER repeat a tool call that already succeeded.
```

After `PromptManager.reloadFromDisk()`, this replaces the built-in reserved defaults for the system prompt.

### Add a general rule (all layers)

Create `~/.alice/rules/security.md` (non-reserved name → all layers):

```markdown
---
title: Security Guidelines
priority: high
status: enabled
---

## Secrets
- Never display API keys, tokens, or passwords in responses.
- Mask secrets in logs and tool call parameters.

## File Operations
- Do not read or write files outside the project directory.
```

### Disable a rule without deleting

Change `status: enabled` → `status: disabled` in the front-matter:

```markdown
---
title: Security Guidelines
priority: high
status: disabled
---
```

Then call `PromptManager.reloadFromDisk()`. The rule is parsed but skipped. Re-enable by changing back to `status: enabled`.

### Example: Git Convention

See [`example/git-convention.md`](./example/git-convention.md) for a complete rule file example.
