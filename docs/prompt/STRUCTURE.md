# Prompt Structure

## File Layout

```
~/.alice/prompts/
├── core_loop.ftl         # Layer 1: PPAO macro loop system + user prompt
└── micro_loop.ftl        # Layer 3: Micro-ReAct loop rules (static)

classpath: .../prompt/
├── core_loop.ftl         # Built-in PPAO macro loop
├── micro_loop.ftl        # Built-in Micro-ReAct rules
├── micro_loop_error.ftl  # Layer 6: Tool failure fallback
└── planner.ftl           # Layer 2: Planner strategy prompt
```

## Template Variables

### `core_loop.ftl`
| Variable | Required | Description |
|----------|----------|-------------|
| `${userTask}` | ✅ | Raw user input |
| `${lastObservation}` | ❌ | Previous macro-loop observation |
| `${lastFeedback}` | ❌ | Previous revision feedback |

### `planner.ftl`
| Variable | Required | Description |
|----------|----------|-------------|
| `${userTask}` | ✅ | Raw user input |
| `${lastObservation}` | ❌ | Previous execution result |
| `${lastActionResult}` | ❌ | Previous action outcome |
| `${error}` | ❌ | Error message from failed step |

### `micro_loop.ftl` / `micro_loop_error.ftl`
Static — no FreeMaker variables.

## Tag Conventions

| Tag | Present in | Parsed by code? |
|-----|-----------|-----------------|
| `<system>...</system>` | `core_loop.ftl` only | ✅ `extractSystemBlock()` |
| `<rules>...</rules>` | All templates | ❌ (LLM-only) |
| `<user_task>...</user_task>` | `planner.ftl`, micro user content | ❌ (LLM-only) |
| `<tool_result>...</tool_result>` | micro user content (dynamic) | ❌ (LLM-only) |
| `<read_files>...</read_files>` | `micro_loop.ftl`, micro user content | ❌ (LLM-only) |

No outer `<planner>` or `<micro_loop>` wrappers — only `<system>` is programmatically extracted.

## System Block Extraction

`buildSystemPrompt()` extracts the `<system>...</system>` block from `core_loop.ftl`.
If no `<system>` wrapper is found, the entire rendered template is used as the system prompt.

After extraction, rules from `~/.alice/rules/*.md` are appended as a `<rules>` block.

## User-Defined Overrides

Any `.ftl` file placed in `~/.alice/prompts/` with a matching name overrides the built-in template:

```bash
vim ~/.alice/prompts/micro_loop.ftl
```

After editing, call `PromptManager.reloadFromDisk()` or restart the agent.
