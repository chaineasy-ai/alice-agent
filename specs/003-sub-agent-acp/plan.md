# Implementation Plan: /sub-agent — Multi-Agent via ACP Protocol

**Branch**: `003-sub-agent-acp` | **Date**: 2026-06-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/003-sub-agent-acp/spec.md`

## Summary

Add a `/sub-agent` command to Alice Agent enabling two multi-agent scenarios: (1) spawning isolated sub-agent sessions that run independently within the same JVM, and (2) connecting to external ACP-compliant agents via the ACP protocol. A new sealed branch `SubAgentCmd` extends `AgentCommand` with sub-types for spawn, connect, list, cancel, results, send, and prompt. The feature reuses existing `AgentExecutor`, `WalSession`, and `alice-memory-vault` infrastructure for spawned agents, and leverages the ACP Java SDK for external agent integration.

## Technical Context

**Language/Version**: Java 25, JPMS modules

**Primary Dependencies**: ACP Java SDK (existing in `docs/acp/README.md`), Jackson (existing in project for JSON)

**Storage**: WAL (existing `FileWalStore`/`InMemoryWalStore` infrastructure); sub-agent sessions get isolated session IDs

**Testing**: Spock 2.4 (Groovy 4.0.30) — unit tests per command handler, integration tests for sub-agent lifecycle

**Target Platform**: JVM — same process; ACP client communicates via HTTP to external agents

**Project Type**: CLI agent framework (multi-module Gradle project)

**Performance Goals**: Sub-agent spawn < 1s; ACP prompt round-trip < 5s (network-dependent); list query < 1s

**Constraints**: Max 5 concurrent sub-agents by default; no separate process spawning; ACP client must handle connection failures gracefully

**Scale/Scope**: Single parent session managing up to 5 child sessions/connections simultaneously

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Status | Rationale |
|------|--------|-----------|
| **I. Module-Separate Design** | ✅ PASS | SubAgentCmd extends existing AgentCommand sealed interface in `alice-agent-command`. SubAgentRegistry and SubAgentRecord are internal to `alice-core-agent`. ACP client code goes in a new `alice-acp-client` module or reused from existing ACP SDK docs. |
| **II. Java 25 + Spock Testing** | ✅ PASS | All new code uses Java 25 features (records for SubAgentRecord, sealed SubAgentCmd hierarchy, pattern matching for dispatch). Tests in Spock/Groovy. |
| **III. CI-Code Quality Gates** | ✅ PASS | No exceptions needed. Existing spotlessCheck and check gates apply. |
| **IV. Documentation Discipline** | ✅ PASS | spec.md, plan.md, research.md, data-model.md, contracts/, quickstart.md all generated under specs/003-sub-agent-acp/. CHANGELOG will be updated on completion. |
| **V. Observability & Secure Execution** | ✅ PASS | Sub-agent lifecycle events logged via SLF4J. ACP client connections use existing env-adapter patterns. Guardrail validation applies to sub-agent spawned actions. |

**No violations — Complexity Tracking section is NOT required.**

## Project Structure

### Documentation (this feature)

```text
specs/003-sub-agent-acp/
├── plan.md              # This file (implementation plan)
├── spec.md              # Feature specification
├── research.md          # Phase 0: technical research
├── data-model.md        # Phase 1: entity/data design
├── quickstart.md        # Phase 1: validation guide
├── contracts/           # Phase 1: SubAgentCmd contracts
├── checklists/
│   └── requirements.md  # Quality checklist
└── tasks.md             # Phase 2: task breakdown
```

### Source Code (repository root)

```text
alice-agent-command/src/main/java/org/cland/alice/agent/command/
├── SubAgentCmd.java           # New sealed branch: SubAgentCmd
├── SpawnSubAgentCmd.java      #   /sub-agent spawn
├── ConnectSubAgentCmd.java    #   /sub-agent connect
├── ListSubAgentsCmd.java      #   /sub-agent list
├── CancelSubAgentCmd.java     #   /sub-agent cancel
├── GetSubAgentResultsCmd.java #   /sub-agent results
├── SendToSubAgentCmd.java     #   /sub-agent send
└── PromptSubAgentCmd.java     #   /sub-agent prompt

alice-core-agent/src/main/java/org/cland/alice/agent/core/subagent/
├── SubAgentRecord.java        # Entity: sub-agent record (Java record)
├── SubAgentRegistry.java      # Registry: parent-session-scoped lifecycle manager
├── SubAgentManager.java       # Orchestrator: spawn/connect/cancel lifecycle
└── SubAgentResult.java        # Entity: result payload

alice-core-agent/src/main/java/org/cland/alice/agent/core/acp/
├── AcpClient.java             # ACP protocol client wrapper
└── AcpConnection.java         # Connection state for an external ACP agent

alice-facade-cmd/src/main/java/org/cland/alice/facade/cmd/
├── config/CommandParser.java  # (modified) add /sub-agent parse rules
└── AliceCliLauncher.java      # (modified) add SubAgentCmd dispatch case

alice-facade-tui/src/main/java/org/cland/alice/facade/tui/
├── command/CommandHandler.java   # (modified) add SubAgentCmd handler
└── AliceTuiLauncher.java         # (modified) add SubAgentCmd dispatch case

tests/
├── alice-agent-command/.../SubAgentCmdParseSpec.groovy
├── alice-agent-command/.../SubAgentCmdSealedHierarchySpec.groovy
├── alice-core-agent/.../SubAgentManagerSpec.groovy
├── alice-core-agent/.../SubAgentRegistrySpec.groovy
├── alice-core-agent/.../AcpClientSpec.groovy
├── alice-facade-cmd/.../CommandParserSpec.groovy   # (modified) add sub-agent tests
├── alice-facade-cmd/.../AliceCliLauncherSpec.groovy # (modified) add sub-agent dispatch tests
└── alice-facade-tui/.../TuiSpec.groovy              # (modified) add sub-agent dispatch tests
```

**Structure Decision**: Multi-module Gradle project. New sealed command types go in `alice-agent-command`. Core sub-agent orchestration logic goes in `alice-core-agent` (reusing existing `AgentExecutor`/`Agent` infrastructure). ACP client code goes in `alice-core-agent` as internal package (no new module needed, matching existing envelope/adapter patterns). Facade modules get dispatch updates only.

## Complexity Tracking

*Not required — all Constitution Check gates pass.*
