---
title: "Rule System — Procedural Memory"
summary: "Documentation for the agent rule system: Markdown rule files under ~/.alice/rules/, YAML front-matter, priority ordering, and integration with the agent system prompt."
read_when:
  - "adding or modifying agent behavioral rules"
  - "understanding how rules are loaded and injected"
  - "configuring rule priority and applicability"
  - "debugging why rules aren't appearing in the system prompt"
scope:
  - "alice-core-agent/.../prompt/"
  - "~/.alice/rules/"
status: "active"
updated: "2026-07-03"
---

# Rule System — Procedural Memory

## Overview

**Rules** are a form of **Procedural Memory** — they encode "how to do things" as human-readable Markdown instructions that are automatically injected into the agent's system prompt. Unlike `PromptDef` (a full FreeMarker template), rules are simple text that gets concatenated into a `<rules>` section appended to the system prompt.

## File Location

All rule files are stored under `~/.alice/rules/` as `*.md` files:

```
~/.alice/rules/
├── coding.md       # Coding standards & conventions
└── ...             # Any name, auto-discovered
```

## Rule File Format

Each rule file is a Markdown document with optional YAML front-matter:

```markdown
---
title: General Coding Guidelines
priority: high
applies_to: java,python
---

## Java
- Follow Google Java Format for all Java files.
- Use records for immutable data carriers.

## Python
- Follow PEP 8.
```

### Front-Matter Fields

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `title` | ❌ | filename | Human-readable title |
| `priority` | ❌ | `medium` | Sort order (`high` > `medium` > `low`) |
| `applies_to` | ❌ | empty | Comma-separated context tags (reserved for future use) |

## Type: `RuleDef`

```java
public record RuleDef(
    String name,              // filename without .md
    String source,            // full path
    String title,             // from front-matter or filename
    String priority,          // "high" | "medium" | "low"
    List<String> appliesTo,   // context tags
    String content            // Markdown body (front-matter stripped)
) {}
```

## Loading and Caching

`FilePromptLoader` lazily scans `~/.alice/rules/` for all `*.md` files on first access.
Results are cached until `reload()` is called.

```java
// Triggered automatically during PromptManager.buildSystemPrompt()
FilePromptLoader loader = new FilePromptLoader();
List<RuleDef> rules = loader.getAllRules();  // sorted by priority
```

## Injection into System Prompt

`PromptManager.buildSystemPrompt()` automatically appends all loaded rules:

```
<system>...agent identity...</system>

<rules>
  <!-- General Coding Guidelines (high) -->
  ## Java
  - Follow Google Java Format for all Java files.
  
  <!-- Another Rule (medium) -->
  ...
</rules>
```

### Priority Ordering

Rules are sorted by priority before injection:
1. `high` — always injected first
2. `medium` — default
3. `low` — injected last

Within the same priority level, rules maintain filesystem scan order (alphabetical by filename).

## Integration with `PromptManager`

```java
// PromptManager.java
public static String buildRules() {
    List<RuleDef> rules = getFileLoader().getAllRules();
    if (rules.isEmpty()) return "";

    StringBuilder sb = new StringBuilder();
    sb.append("<rules>\n");
    for (RuleDef rule : rules) {
        sb.append("  <!-- ").append(rule.title())
            .append(" (").append(rule.priority()).append(") -->\n");
        sb.append(rule.content()).append("\n\n");
    }
    sb.append("</rules>");
    return sb.toString();
}
```

The result is appended to `systemPromptCache` in `buildSystemPrompt()`, so it becomes part of the WAL `role: "system"` message and the LLM context.

## Reload

```java
PromptManager.reloadFromDisk();  // re-scans ~/.alice/rules/ + ~/.alice/prompts/
```

## Example: Adding a Security Rule

Create `~/.alice/rules/security.md`:

```markdown
---
title: Security Guidelines
priority: high
---

## Secrets
- Never display API keys, tokens, or passwords in responses.
- Mask secrets in logs and tool call parameters.

## File Operations
- Do not read or write files outside the project directory.
- Use `run` with caution — prefer built-in tools.
```

After the next agent restart (or `PromptManager.reloadFromDisk()`), these rules are automatically injected into the system prompt.
