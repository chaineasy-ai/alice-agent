# Tasks: Routine-Time Command Model Update

**Input**: Design documents from `/specs/002-routine-time-update/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Spock test tasks are included as per the spec's success criteria (SC-001 through SC-004 each require automated tests).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P] [Story] Description with file path`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **alice-agent-command**: `alice-agent-command/src/main/java/org/cland/alice/agent/command/`
- **alice-facade-cmd**: `alice-facade-cmd/src/main/java/org/cland/alice/facade/cmd/config/`
- **alice-facade-tui**: `alice-facade-tui/src/main/java/org/cland/alice/facade/tui/command/`
- **Tests**: Corresponding `src/test/groovy/` directories in each module

---

## Phase 1: Setup

**Purpose**: Project initialization — no setup tasks needed beyond reading existing source code. All three modules exist and build. No new directories, no new modules.

- [x] T001 Read and understand existing sealed command hierarchy in `alice-agent-command/src/main/java/org/cland/alice/agent/command/` — inspect `AgentCommand.java`, `ExecutionCmd.java`, `CapabilityCmd.java`, `AlignmentCmd.java`, `ControlCmd.java` for patterns
- [x] T002 Read and understand existing `alice-facade-cmd/src/main/java/org/cland/alice/facade/cmd/config/CommandParser.java` — inspect picocli subcommand registration pattern (`RunCommand`, `ChatCommand`, `ToolsCommand`, `ConfigCommand`)
- [x] T003 Read and understand existing `alice-facade-tui/src/main/java/org/cland/alice/facade/tui/command/SlashCommand.java` and `CommandHandler.java` — inspect slash command parsing and handler dispatch patterns

---

## Phase 2: User Story 1 — Add RoutineTimeCmd to sealed hierarchy (Priority: P1) 🎯 MVP

**Goal**: A `RoutineTimeCmd` sealed branch with `RegisterRoutineCmd` and `TimeTriggeredCmd` records, integrated into `AgentCommand.permits` and `AgentCommand.parse()`.

**Independent Test**: A Spock test instantiates `RegisterRoutineCmd` and `TimeTriggeredCmd` through the sealed interface, verifies pattern matching switch exhaustiveness, and confirms `AgentCommand.parse()` returns the correct type for `/routine`.

### Tests for User Story 1

> **NOTE**: Write these tests FIRST (TDD), ensure they FAIL before implementation, then make them pass.

- [x] T004 [P] [US1] Create `RoutineTimeCmdSpec.groovy` in `alice-agent-command/src/test/groovy/org/cland/alice/agent/command/RoutineTimeCmdSpec.groovy` — test `RegisterRoutineCmd` and `TimeTriggeredCmd` instantiation, field access, null-safety, and `task()` accessor
- [x] T005 [P] [US1] Create `RoutineTimeCmdParseSpec.groovy` in `alice-agent-command/src/test/groovy/org/cland/alice/agent/command/RoutineTimeCmdParseSpec.groovy` — test `AgentCommand.parse("/routine ...")` returns `RegisterRoutineCmd` with correct fields, `/routine` no-args returns blank cron, non-slash input still returns `AcquireGoalCmd` (no regression)
- [x] T006 [P] [US1] Update `AgentCommandSealedHierarchySpec.groovy` in `alice-agent-command/src/test/groovy/org/cland/alice/agent/command/AgentCommandSealedHierarchySpec.groovy` — add test cases verifying exhaustiveness of pattern matching switch over all 5 sealed branches including `RoutineTimeCmd`

### Implementation for User Story 1

- [x] T007 [P] [US1] Create `RoutineTimeCmd.java` in `alice-agent-command/src/main/java/org/cland/alice/agent/command/RoutineTimeCmd.java` — sealed interface extending `AgentCommand` with `String task()` accessor, containing `RegisterRoutineCmd(String cronExpression, String sessionId, String traceId, Instant timestamp)` and `TimeTriggeredCmd(String routineGoal, String sessionId, String traceId, Instant timestamp)` records with compact constructor null-safety validation
- [x] T008 [US1] Update `AgentCommand.java` in `alice-agent-command/src/main/java/org/cland/alice/agent/command/AgentCommand.java` — add `RoutineTimeCmd` to the `permits` clause (line 25), update Javadoc to mention 5th category (Routine-Time), add `/routine` case to the `parse()` switch expression returning `new RoutineTimeCmd.RegisterRoutineCmd(args, sessionId, traceId)`
- [x] T009 [US1] Run `./gradlew :alice-agent-command:build` — verify compilation succeeds and all existing + new tests pass

**Checkpoint**: At this point, User Story 1 should be fully functional. Developers can instantiate both command types, `AgentCommand.parse("/routine ...")` works correctly, and pattern-matching switch is exhaustive across all 5 branches.

---

## Phase 3: User Story 2 — CLI support for alice routine subcommand (Priority: P2)

**Goal**: CLI users can register routines via `alice routine <cron>` and use `--list`/`--remove` flags.

**Independent Test**: `CommandParser.parse(new String[]{"routine", "0 */2 * * * ?"})` returns a `RunConfig` with `routineCron` set; `CommandParser.parseToAgentCommand("/routine 0 */2 * * * ?")` returns a `RegisterRoutineCmd`.

### Tests for User Story 2

> **NOTE**: Write these tests FIRST (TDD), ensure they FAIL before implementation, then make them pass.

- [x] T010 [P] [US2] Update `CommandParserSpec.groovy` in `alice-facade-cmd/src/test/groovy/org/cland/alice/facade/cmd/config/CommandParserSpec.groovy` — add test cases for `alice routine <cron>` subcommand parsing, `--list` flag, `--remove` flag, and `/routine` via `parseToAgentCommand()`
- [x] T011 [P] [US2] Update `RunConfigSpec.groovy` in `alice-facade-cmd/src/test/groovy/org/cland/alice/facade/cmd/config/RunConfigSpec.groovy` — add test cases for `routineCron` and `listRoutines` fields in the Builder

### Implementation for User Story 2

- [x] T012 [P] [US2] Update `RunConfig.java` in `alice-facade-cmd/src/main/java/org/cland/alice/facade/cmd/config/RunConfig.java` — add `String routineCron` and `boolean listRoutines` fields with getters; add `routineCron(String)` and `listRoutines(boolean)` builder methods; update `toString()` to include new fields
- [x] T013 [US2] Update `CommandParser.java` in `alice-facade-cmd/src/main/java/org/cland/alice/facade/cmd/config/CommandParser.java` — add inner `RoutineCommand` picocli subclass with `@Parameters` cron expression, `@Option --list`/`-l`, `@Option --remove`/`-r`; register via `cmdLine.addSubcommand("routine", new RoutineCommand())`; implement `toRunConfig()` and `toAgentCommand()` methods
- [x] T014 [US2] Run `./gradlew :alice-facade-cmd:build` — verify compilation succeeds and all existing + new tests pass

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently. CLI users can use `alice routine` subcommand.

---

## Phase 4: User Story 3 — TUI support for /routine slash command (Priority: P3)

**Goal**: TUI users can type `/routine <cron>` to register scheduled tasks.

**Independent Test**: Typing `/routine 0 */2 * * * ?` in TUI triggers `SlashCommand.parse()` returning `Type.CONFIG` command, which `CommandHandler` converts to `RegisterRoutineCmd` and dispatches.

### Tests for User Story 3

> **NOTE**: Write these tests FIRST (TDD), ensure they FAIL before implementation, then make them pass.

- [x] T015 [P] [US3] Update `TuiSpec.groovy` (or create dedicated `SlashCommandRoutineSpec.groovy`) in `alice-facade-tui/src/test/groovy/org/cland/alice/facade/tui/command/` — add test cases for `/routine` slash command parsing, `CommandHandler` dispatch via `onAgentCommand`, no-args usage message, and help text inclusion

### Implementation for User Story 3

- [x] T016 [US3] Update `SlashCommand.java` in `alice-facade-tui/src/main/java/org/cland/alice/facade/tui/command/SlashCommand.java` — add `/routine` case to the `parse()` switch expression returning `new SlashCommand(cmd, args, Type.CONFIG, "注册定时任务：注册 Cron 表达式到调度器")`; update `helpText()` to include `/routine <cron>  注册定时任务：注册 Cron 表达式到调度器`
- [x] T017 [US3] Update `CommandHandler.java` in `alice-facade-tui/src/main/java/org/cland/alice/facade/tui/command/CommandHandler.java` — add `/routine` handling in `handleConfig()`: if no args display usage message, if args present convert to `AgentCommand` via `cmd.toAgentCommand()` and dispatch via `onAgentCommand`, show confirmation
- [x] T018 [US3] Run `./gradlew :alice-facade-tui:build` — verify compilation succeeds and all existing + new tests pass

**Checkpoint**: All three user stories should now be independently functional. The complete feature is delivered.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Final verification, documentation, and changelog updates.

- [x] T019 [P] Run `./gradlew build` (full project) — ensure all 11+ modules compile without warnings and all existing tests pass
- [x] T020 [P] Run `./gradlew spotlessCheck` — ensure Google Java Format compliance across all modified files
- [x] T021 Update `CHANGELOG.md` — add entry under `20260614` for Routine-Time Command Model Update (alice-agent-command, alice-facade-cmd, alice-facade-tui)
- [x] T022 Update `todos/TODO-alice-agent-command.md` — mark RoutineTimeCmd tasks as completed
- [x] T023 Run quickstart.md validation — verify Scenarios 1-5 produce expected outcomes

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — read existing code to understand patterns
- **User Story 1 (Phase 2)**: No dependencies on other stories — can start immediately after setup
- **User Story 2 (Phase 3)**: DEPENDS on US1 — `RegisterRoutineCmd` must exist for `CommandParser` to dispatch
- **User Story 3 (Phase 4)**: DEPENDS on US1 — `RegisterRoutineCmd` must exist for `CommandHandler` to dispatch
- **Polish (Phase 5)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: **MVP** — No dependencies on other stories. Can be implemented and tested in isolation.
- **User Story 2 (P2)**: Depends on US1. Cannot be implemented/tested until `RoutineTimeCmd.java` exists.
- **User Story 3 (P3)**: Depends on US1. Cannot be implemented/tested until `RoutineTimeCmd.java` exists.
  - US2 and US3 are independent of each other — they can proceed in parallel once US1 is complete.

### Within Each User Story

- Tests MUST be written and FAIL before implementation (TDD)
- Models/records before services/parsers
- Core types before integration
- Story complete before moving to next priority

### Parallel Opportunities

- All setup tasks (T001-T003) can run in parallel
- All test tasks within a story marked [P] can run in parallel
- US2 and US3 can run in parallel once US1 is complete
- Polish tasks T019-T020 can run in parallel

---

## Parallel Example: User Story 1

```bash
# Launch all tests for User Story 1 together (TDD — write first, expect failure):
Task: "Write RoutineTimeCmdSpec.groovy test"
Task: "Write RoutineTimeCmdParseSpec.groovy test"
Task: "Update AgentCommandSealedHierarchySpec.groovy test"

# Launch all implementation tasks for User Story 1 together:
Task: "Create RoutineTimeCmd.java"
Task: "Update AgentCommand.java (permits + parse)"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (read existing code)
2. Complete Phase 2: User Story 1 (RoutineTimeCmd sealed hierarchy)
3. **STOP and VALIDATE**: Run `./gradlew :alice-agent-command:test`
4. MVP is ready — the sealed interface supports `/routine` parsing

### Incremental Delivery

1. US1 → Sealed hierarchy + parsing → **MVP!** (developers can use new command type)
2. US2 → CLI support → users can use `alice routine` from terminal
3. US3 → TUI support → users can use `/routine` in interactive mode
4. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Developer A implements US1 (the sealed hierarchy foundation)
2. Once US1 is done:
   - Developer B implements US2 (CLI support)
   - Developer C implements US3 (TUI support)
3. US2 and US3 proceed in parallel, merge independently

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests fail before implementing (TDD approach)
- All changes are additive — no existing code is modified in breaking ways
- `module-info.java` files need NO changes — packages already exported
- Gradle `build.gradle` files need NO changes — dependencies already exist
