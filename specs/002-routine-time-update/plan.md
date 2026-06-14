# Implementation Plan: Routine-Time Command Model Update

**Branch**: `002-routine-time-update` | **Date**: 2026-06-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-routine-time-update/spec.md`

## Summary

Add a fifth sealed branch `RoutineTimeCmd` to the `AgentCommand` hierarchy, representing time-based autonomous task triggers (cron, periodic, scheduled). The change spans three modules:

- **`alice-agent-command`**: New `RoutineTimeCmd.java` with `RegisterRoutineCmd` and `TimeTriggeredCmd` records; update `AgentCommand.permits` clause; update `AgentCommand.parse()` switch expression for `/routine`
- **`alice-facade-cmd`**: New `alice routine` picocli subcommand in `CommandParser`; extend `RunConfig` with `routineCron`/`listRoutines` fields
- **`alice-facade-tui`**: Add `/routine` to `SlashCommand` parsing and `CommandHandler.handleConfig()`; update help text

All changes are additive — no existing command types, tests, or consumers are modified. The `TimeTriggeredCmd` is kernel-triggered only (constructed programmatically, never parsed from user input).

## Technical Context

**Language/Version**: Java 25 (toolchain `JavaLanguageVersion.of(25)`, release `25`)

**Primary Dependencies**: No new dependencies. Existing dependency tree:
- `alice-agent-command` depends on `org.slf4j` only
- `alice-facade-cmd` depends on `alice-agent-command`, `picocli`, `JLine 3`, `Jackson`, `Vert.x`, `Guava`
- `alice-facade-tui` depends on `alice-agent-command`, `JLine 3`, `Jackson`, `Guava`, `Vert.x`

**Storage**: N/A — commands are in-memory value objects (records), not persisted

**Testing**: Spock 2.4 (Groovy 4.0.30) with JUnit Platform Launcher. Tests in each module's `src/test/groovy/` directory.

**Target Platform**: JVM (part of existing multi-module Gradle project)

**Project Type**: Library component — sealed command hierarchy extends existing JPMS-modularized codebase

**Performance Goals**: No measurable performance impact — changes are compile-time type hierarchy extension and command parsing only

**Constraints**:
- The `AgentCommand` sealed interface `permits` clause must be updated atomically — all files compile together or not at all
- `TimeTriggeredCmd` is NOT user-parseable; it must NOT appear in `AgentCommand.parse()` switch
- JPMS `module-info.java` in `alice-agent-command` already exports `org.cland.alice.agent.command` — no changes needed
- Gradle `build.gradle` dependencies are already correct — no changes needed

**Scale/Scope**: Affects 3 modules, 4 Java files modified (AgentCommand, new RoutineTimeCmd, CommandParser, RunConfig, SlashCommand, CommandHandler)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Constitution Principle I — Module-Separate Design**: The new `RoutineTimeCmd` is placed in `alice-agent-command` module alongside the existing four sealed branches. No circular dependencies are introduced. `module-info.java` already exports the package — no JPMS API leak. ✅ Compliant.

**Constitution Principle II — Java 25 + Spock Testing**: The new sealed interface and records use Java 25 language features (`sealed`, `record`, pattern matching `switch`). Spock tests will be added in each modified module for the new command type parsing/dispatch behavior. ✅ Compliant.

**Constitution Principle III — CI-Code Quality Gates**: `spotlessCheck` and `check` must pass. All three modules already pass — additive changes should not regress. ✅ Compliant.

**Constitution Principle IV — Documentation Discipline**: This plan, research, data-model, and quickstart artifacts are generated. CHANGELOG.md and TODO files will be updated in post-implementation. ✅ Compliant.

**Constitution Principle V — Observability & Secure Execution**: No new logging, security, or execution concerns — this is a type hierarchy extension for command parsing. Existing SLF4J logging in CommandHandler is sufficient. ✅ Compliant.

**Gate Result**: **PASS** — No violations. Complexity Tracking is not needed.

## Project Structure

### Documentation (this feature)

```text
specs/002-routine-time-update/
├── plan.md              # This file
├── research.md          # Phase 0 output — all contexts known, minimal research
├── data-model.md        # Phase 1 output — sealed interface + records definition
├── quickstart.md        # Phase 1 output — validation guide
├── contracts/           # Phase 1 output — contract specs for new sealed branch
│   ├── RoutineTimeCmd-contract.md
│   ├── CommandParser-contract.md
│   └── SlashCommand-contract.md
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
# Multi-module Gradle project (existing structure, additive changes only)

alice-agent-command/
└── src/main/java/org/cland/alice/agent/command/
    ├── AgentCommand.java          # MODIFY: add RoutineTimeCmd to permits
    ├── RoutineTimeCmd.java        # NEW: sealed interface + 2 records
    ├── ExecutionCmd.java          # UNCHANGED
    ├── CapabilityCmd.java         # UNCHANGED
    ├── AlignmentCmd.java          # UNCHANGED
    └── ControlCmd.java            # UNCHANGED

alice-facade-cmd/
└── src/main/java/org/cland/alice/facade/cmd/
    ├── config/
    │   ├── CommandParser.java     # MODIFY: add routine subcommand + /routine parse
    │   └── RunConfig.java         # MODIFY: add routineCron, listRoutines fields
    └── ... (unchanged)

alice-facade-tui/
└── src/main/java/org/cland/alice/facade/tui/
    ├── command/
    │   ├── SlashCommand.java      # MODIFY: add /routine recognition + help text
    │   └── CommandHandler.java    # MODIFY: add handleConfig case for /routine
    └── ... (unchanged)
```

**Structure Decision**: Additive-only changes to 3 existing modules. No new modules, no new directories. Files are modified in-place following the existing naming conventions.

## Complexity Tracking

> **Not needed** — Constitution Check passed with no violations.
