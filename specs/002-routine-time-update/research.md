# Research: Routine-Time Command Model Update

> **Date**: 2026-06-14
> **Status**: Complete — no unknowns, all technology choices are established project patterns

## Summary

This feature extends an existing sealed interface hierarchy and modifies two facade modules to recognize a new command category. All technology decisions are already established in the codebase — no research required for new dependencies, frameworks, or patterns. This document confirms existing decisions and documents why each choice applies.

## Technology Decisions

### Sealed Interface Hierarchy Extension

**Decision**: Extend the existing `AgentCommand` sealed interface with a new `RoutineTimeCmd` branch, following the same record-and-sealed-interface pattern as `ExecutionCmd`, `CapabilityCmd`, `AlignmentCmd`, and `ControlCmd`.

**Rationale**: The existing four branches already establish the pattern:
- `sealed interface XxxCmd extends AgentCommand` with `String` accessor method
- Inner `record` types implementing the sealed interface
- Records include `sessionId`, `traceId`, `timestamp` fields per `AgentCommand` contract
- New file placed alongside existing commands in `org.cland.alice.agent.command`

**Alternatives considered**: Adding `/routine` as a subclass of `ControlCmd` was considered but rejected — the routine-time drive category is semantically distinct from control, has different accessor needs (`cronExpression` vs `reason`), and treating it as a first-class branch enables pattern-matching exhaustiveness at the consumer level.

**References**: 
- `ExecutionCmd.java` — pattern for sealed interface with inner records
- `ControlCmd.java` — pattern for sealed interface with multiple record types

### CLI Subcommand Pattern (picocli)

**Decision**: Add an `alice routine` picocli subcommand following the same pattern as the existing `run`, `chat`, `tools`, `config` subcommands in `CommandParser.java`.

**Rationale**: 
- The `CommandParser` already uses picocli's `addSubcommand()` registration pattern
- All existing subcommands use `@Command(name=..., description=...)` annotation + `implements Callable<Integer>`
- Parameters use `@Parameters` and `@Option` annotations
- Each subcommand has a `toRunConfig()` method that converts parsed args to `RunConfig`

**Alternatives considered**: Adding `--routine` as an option on the `run` subcommand was rejected — routines are a distinct use case from one-shot task execution and deserve their own subcommand.

**References**: `CommandParser.RunCommand`, `CommandParser.ChatCommand`, `CommandParser.ToolsCommand`, `CommandParser.ConfigCommand` — established patterns

### TUI Slash Command Pattern

**Decision**: Add `/routine` to the `SlashCommand.parse()` switch expression as `Type.CONFIG`, and handle it in `CommandHandler.handleConfig()`.

**Rationale**:
- `SlashCommand.parse()` already uses a comprehensive switch expression for all 11 existing slash commands
- `/model` is already `Type.CONFIG` — `/routine` naturally follows the same category
- `CommandHandler.handleConfig()` already handles `/model` — the `/routine` case follows the same dispatch pattern: convert `SlashCommand` → `AgentCommand` → dispatch via `onAgentCommand` callback
- Both `/model` and `/routine` modify runtime agent configuration (model selection / routine registration)

**Alternatives considered**: `Type.INTERNAL` was rejected because routine registration modifies kernel behavior (schedules cron jobs), not just UI/state. `Type.SYSTEM` was rejected — it's not a shell command.

**References**: `SlashCommand.java` line 60-74 (model handling), `CommandHandler.java` lines 253-275 (handleConfig method)

### JPMS Module System

**Decision**: No `module-info.java` changes needed.

**Rationale**:
- `alice-agent-command` already exports `org.cland.alice.agent.command` (line 9 of `module-info.java`)
- `alice-facade-cmd` already `requires alice.agent.command.main` (line 6 of `module-info.java`)
- `alice-facade-tui` already `requires alice.agent.command.main` (line 8 of `module-info.java`)
- The new `RoutineTimeCmd.java` lives in the same exported package — no new exports or requires needed

**References**: `alice-agent-command/src/main/java/module-info.java`, `alice-facade-cmd/src/main/java/module-info.java`, `alice-facade-tui/src/main/java/module-info.java`

### Testing Framework

**Decision**: Spock 2.4 (Groovy 4.0.30) with JUnit Platform Launcher, following existing test conventions.

**Rationale**: All 11 modules already use Spock for testing. The existing test conventions include:
- Test files in `src/test/groovy/org/cland/...`
- Specification classes extending `spock.lang.Specification`
- Gradle test task configured for JUnit Platform (Spock 2.x)
- No new test dependencies needed

**References**: `alice-memory-vault` test directory (70+ Spock tests), `build.gradle` JUnit Platform Launcher dependency

## Conclusion

All technology decisions are pre-established by the project's existing architecture. No new dependencies, frameworks, or patterns need to be introduced. The implementation plan is fully deterministic with zero unknowns.
