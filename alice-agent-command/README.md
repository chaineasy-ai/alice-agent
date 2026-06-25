# alice-agent-command

**Sealed command interface** for Alice Agent — the universal message protocol between all facades (CLI/TUI) and the agent kernel.

## Module Overview

Defines the complete `AgentCommand` sealed interface hierarchy that every component depends on. Every user action, system event, and sub-agent communication is serialised as an `AgentCommand` subtype.

- **Module path**: `org.cland.alice.agent.command`
- **JPMS module**: `org.cland.alice.agent.command`
- **Base package**: `org.cland.alice.agent.command`
- **Source**: `src/main/java/ ... /command/`

## Command Hierarchy

```
AgentCommand (sealed interface)
├── ExecutionCmd     — Task-driven work (/run, /exec)
├── CapabilityCmd    — Capability loading (/skill, /rules, /reload)
├── AlignmentCmd     — Runtime configuration (/model)
├── ControlCmd       — Lifecycle & HITL (/new, /clear, /context, /compact, /feedback, /resume)
└── RoutineTimeCmd   — Scheduled/Time-triggered (/routine, TimeTriggered)
```

👉 **Full design with class diagrams, use-case mapping, and sequence diagrams**:  
[`docs/alice-agent-command/DESIGN.md`](../docs/alice-agent-command/DESIGN.md)

## Available Commands

| Class | TUI `/` | CLI `--` | Description |
|-------|---------|----------|-------------|
| `AcquireGoalCmd` | `/run` | `--run` | Autonomous goal-driven loop |
| `ExecuteRawCmd` | `/exec` | `--exec` | Direct shell/tool execution |
| `RegisterSkillCmd` | `/skill` | `--skill` | Load MCP/tool sets |
| `UpdateRulesCmd` | `/rules` | `--rules` | Load preset prompts/rules |
| `ReloadKernelCmd` | `/reload` | `--reload` | Force-refresh all resources |
| `SwitchModelCmd` | `/model` | `--model` | Switch LLM engine |
| `ResetSessionCmd` | `/new` | `--new` | Reset session |
| `FeedbackCmd` | `/feedback` | `--feedback` | Human-in-the-loop response |
| `InterruptCmd` | Ctrl+C | — | Force abort |
| `ClearContextCmd` | `/clear` | `--clear` | Clear context |
| `ViewContextCmd` | `/context` | `--context` | View context |
| `CompactContextCmd` | `/compact` | `--compact` | Compact context |
| `ResumeSessionCmd` | `/resume` | `--resume` | Resume historical session |
| `RegisterRoutineCmd` | `/routine` | `--routine` | Register cron/periodic task |
| `TriggerRoutineCmd` | — | — | System time-triggered dispatch |
| `SpawnSubAgentCmd` | — | — | Spawn sub-agent |
| `ConnectSubAgentCmd` | — | — | Connect sub-agent channel |
| `PromptSubAgentCmd` | — | — | Prompt sub-agent |
| `SendToSubAgentCmd` | — | — | Send message to sub-agent |
| `CancelSubAgentCmd` | — | — | Cancel sub-agent |
| `ListSubAgentsCmd` | — | — | List active sub-agents |
| `GetSubAgentResultsCmd` | — | — | Retrieve sub-agent results |

## Documentation Index

| Document | Description |
|----------|-------------|
| [`DESIGN.md`](../docs/alice-agent-command/DESIGN.md) | Full design doc: class diagram, use-case mapping, sequence diagrams for all command categories |
| **E2E Test Specs** | |
| [`case-bootstrap.md`](../docs/alice-agent-command/e2e/case-bootstrap.md) | Bootstrap lifecycle E2E |
| [`case-chat.md`](../docs/alice-agent-command/e2e/case-chat.md) | Chat interaction flow E2E |
| [`case-config.md`](../docs/alice-agent-command/e2e/case-config.md) | Config command E2E |
| [`case-core-agent.md`](../docs/alice-agent-command/e2e/case-core-agent.md) | Core agent lifecycle E2E |
| [`case-core-planner.md`](../docs/alice-agent-command/e2e/case-core-planner.md) | Planner E2E |
| [`case-dispatch-full-coverage.md`](../docs/alice-agent-command/e2e/case-dispatch-full-coverage.md) | Full dispatch coverage E2E |
| [`case-env-adapter.md`](../docs/alice-agent-command/e2e/case-env-adapter.md) | Environment adapter E2E |
| [`case-guardrail.md`](../docs/alice-agent-command/e2e/case-guardrail.md) | Guardrail/policy E2E |
| [`case-hole-test.md`](../docs/alice-agent-command/e2e/case-hole-test.md) | Hole test specification |
| [`case-memory-vault.md`](../docs/alice-agent-command/e2e/case-memory-vault.md) | Memory vault E2E |
| [`case-model.md`](../docs/alice-agent-command/e2e/case-model.md) | Model supplier E2E |
| [`case-resume.md`](../docs/alice-agent-command/e2e/case-resume.md) | Resume session E2E |
| [`case-routine.md`](../docs/alice-agent-command/e2e/case-routine.md) | Routine/time-triggered E2E |
| [`case-run.md`](../docs/alice-agent-command/e2e/case-run.md) | Run command E2E |
| [`case-sub-agent.md`](../docs/alice-agent-command/e2e/case-sub-agent.md) | Sub-agent command E2E |
| [`case-tool-gateway.md`](../docs/alice-agent-command/e2e/case-tool-gateway.md) | Tool gateway E2E |
| [`case-tools.md`](../docs/alice-agent-command/e2e/case-tools.md) | Tools E2E |
| [`case-tui-slash-commands.md`](../docs/alice-agent-command/e2e/case-tui-slash-commands.md) | TUI slash commands E2E |
| [`case-web.md`](../docs/alice-agent-command/e2e/case-web.md) | Web interface E2E |
| [`scene-command-endpoints.md`](../docs/alice-agent-command/e2e/scene-command-endpoints.md) | Command endpoint scene specs |

## Source Layout

```
alice-agent-command/
├── src/
│   ├── main/java/module-info.java
│   └── main/java/org/cland/alice/agent/command/
│       ├── AgentCommand.java         — Sealed root interface
│       ├── ExecutionCmd.java         — Task-driven work
│       ├── CapabilityCmd.java        — Capability loading
│       ├── AlignmentCmd.java         — Runtime config
│       ├── ControlCmd.java           — Lifecycle & HITL
│       ├── RoutineTimeCmd.java       — Scheduled/time-triggered
│       └── SubAgentCmd.java          — Sub-agent management
│   └── hole/java/.../command/
│       └── CommandHoleTest.java      — Hole test
├── build.gradle
└── README.md                         ← you are here
```

## Key Design Principles

1. **Sealed interface** — All commands are sealed under `AgentCommand`; adding a new command requires explicitly opening the seal.
2. **Driven dispatch** — Commands are categorised by driver type (execution, capability, alignment, control, routine-time).
3. **Sub-agent commands** — `SubAgentCmd` provides a dedicated sealed branch for multi-agent communication.
4. **Decoupled from facades** — The command module has no dependency on CLI (picocli/JLine) or TUI (Vert.x); facades map user input to commands via `CommandParser`.

## Related

- [`AGENTS.md`](../AGENTS.md) — Project contributor guide
- [`project.tree`](../project.tree) — Full project structure
