# Prompt Structure

```
~/.alice/prompts/
├── core_loop.ftl         # PPAO macro loop system + user prompt
├── micro_loop.ftl        # Micro-ReAct loop rules (static)
├── micro_loop_error.ftl  # Tool failure fallback (reserved)
└── planner.ftl           # Planner strategy prompt
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
Static — no FreeMarker variables.

## System Block Extraction

`buildSystemPrompt()` extracts the `<system>...</system>` block from `core_loop.ftl`.
If no `<system>` wrapper is found, the entire rendered template is used as the system prompt.

## User-Defined Overrides

Any `.ftl` file placed in `~/.alice/prompts/` with a matching name overrides the built-in template:

```bash
# Override core_loop.ftl
cp ~/.alice/prompts/core_loop.ftl ~/.alice/prompts/core_loop.ftl.bak
vim ~/.alice/prompts/core_loop.ftl
```

After editing, call `PromptManager.reloadFromDisk()` or restart the agent.
