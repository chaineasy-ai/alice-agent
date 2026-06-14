# Feature Specification: Routine-Time Command Model Update

**Feature Branch**: `002-routine-time-update`

**Created**: 2026-06-14

**Status**: Draft

**Input**: User description: "Update alice-agent-command model — add RoutineTimeCmd as 5th sealed branch; update alice-facade-cmd CLI pattern to support --routine; update alice-facade-tui to support /routine slash command."

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Developer: Add RoutineTimeCmd to sealed hierarchy (Priority: P1)

**As a** developer maintaining the AgentCommand sealed interface,
**I want** a `RoutineTimeCmd` branch with `RegisterRoutineCmd` and inner `TimeTriggeredCmd` records,
**So that** time-based autonomous tasks (cron, periodic, scheduled) are represented as a first-class command category rather than being shoehorned into ExecutionCmd or ControlCmd.

**Why this priority**: P1 — The sealed interface hierarchy is the foundation. All downstream consumers (facade-cmd, facade-tui, core-agent) depend on it. Without this change, no other modules can parse or dispatch routine commands.

**Independent Test**: A Spock test instantiates `RegisterRoutineCmd` and `TimeTriggeredCmd` through the sealed interface, verifies `pattern matching switch` exhaustiveness, and confirms `AgentCommand.parse()` returns the correct type for `/routine "0 */2 * * * ?"`.

**Acceptance Scenarios**:

1. **Given** the sealed `AgentCommand` interface, **When** constructing `new RoutineTimeCmd.RegisterRoutineCmd(cronExpr, sessionId, traceId)`, **Then** the record stores the cron expression without validation or transformation.
2. **Given** the `AgentCommand.parse()` method, **When** called with input `/routine "0 */2 * * * ?"`, **Then** it returns a `RoutineTimeCmd.RegisterRoutineCmd` instance with the correct cron expression.
3. **Given** the `AgentCommand.parse()` method, **When** called with input `/routine` (no args), **Then** it returns a `RoutineTimeCmd.RegisterRoutineCmd` with a blank cron expression (deferring validation to downstream consumers).
4. **Given** a `TimeTriggeredCmd` is constructed programmatically (kernel-triggered, not parsed from user input), **When** its `routineGoal` field is read, **Then** it returns the expected goal string.

---

### User Story 2 — User: CLI support for --routine (Priority: P2)

**As a** CLI user,
**I want** to register a routine task via `alice routine "0 */2 * * * ?"` or inspect routines via the picocli `CommandParser`,
**So that** I can manage scheduled tasks from the command line without entering TUI mode.

**Why this priority**: P2 — CLI is a secondary interface; the core data model (US1) must be in place first. The `CommandParser` already has a `parseToAgentCommand()` method for natural language — adding `--routine` (or a `routine` subcommand) extends the existing picocli pattern.

**Independent Test**: `CommandParser.parse(new String[]{"routine", "0 */2 * * * ?"})` returns a `RunConfig` with `routineTask` set, and `CommandParser.parseToAgentCommand("/routine 0 */2 * * * ?")` returns a `RegisterRoutineCmd`.

**Acceptance Scenarios**:

1. **Given** the `CommandParser` with `alice routine` subcommand, **When** parsing `alice routine "0 */2 * * * ?"`, **Then** a `RunConfig` is produced with a `routineCron` field populated.
2. **Given** the `CommandParser` with `/routine` parsing support, **When** calling `parseToAgentCommand("/routine 0 */2 * * * ?")`, **Then** a `RoutineTimeCmd.RegisterRoutineCmd` is returned.
3. **Given** the picocli `@Command` subcommand `RoutineCommand`, **When** `--list` flag is provided, **Then** `RunConfig` carries a `listRoutines` flag (deferred to agent core for actual list retrieval).

---

### User Story 3 — User: TUI support for /routine (Priority: P3)

**As a** TUI user,
**I want** to type `/routine "0 */2 * * * ?"` in the chat interface,
**So that** I can register and manage scheduled tasks while interacting with the agent.

**Why this priority**: P3 — TUI is the primary user-facing interface but depends on both the sealed interface (P1) and CLI parsing (P2) being complete. The `SlashCommand` parser and `CommandHandler` must recognize `/routine` and delegate to `AgentCommand.parse()`.

**Independent Test**: Typing `/routine "0 */2 * * * ?"` in the TUI input triggers `SlashCommand.parse()` returning a new `Type.CONFIG` (or `Type.INTERNAL`) command, which `CommandHandler.execute()` converts to a `RegisterRoutineCmd` and dispatches via `onAgentCommand` callback.

**Acceptance Scenarios**:

1. **Given** the TUI input field, **When** user types `/routine "0 */2 * * * ?"`, **Then** `SlashCommand.parse()` recognizes it and returns a non-null `SlashCommand` with the correct type.
2. **Given** the `CommandHandler`, **When** executing a `/routine` `SlashCommand`, **Then** it converts to `RegisterRoutineCmd` and dispatches via `onAgentCommand` callback.
3. **Given** the `/routine` command, **When** user types `/routine` without arguments, **Then** the TUI displays a usage message: "用法: /routine <cron表达式>".

---

### Edge Cases

- What happens when the cron expression contains special characters that conflict with shell parsing? → CLI layer must handle quoting; TUI passes raw string.
- How does `/routine` interact with AgentCommand.parse() if the `parse()` method already handles it? → `SlashCommand.toAgentCommand()` delegates to `AgentCommand.parse()`, ensuring single authoritative parser.
- What about `/routine list` or `/routine remove`? → Future extension; v1 spec only covers registration (`/routine <cron>`).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001** (`alice-agent-command`): The `AgentCommand` sealed interface MUST be updated to permit a `RoutineTimeCmd` category, adding it to the `permits` clause alongside the existing four categories.
- **FR-002** (`alice-agent-command`): A new sealed interface `RoutineTimeCmd` MUST be created extending `AgentCommand`, with `String task()` as common accessor.
- **FR-003** (`alice-agent-command`): `RoutineTimeCmd` MUST contain two records:
  - `RegisterRoutineCmd(String cronExpression, String sessionId, String traceId, Instant timestamp)` — user-facing `/routine` command
  - `TimeTriggeredCmd(String routineGoal, String sessionId, String traceId, Instant timestamp)` — system-triggered (not user-facing)
- **FR-004** (`alice-agent-command`): `AgentCommand.parse()` MUST be updated to handle `/routine <cron>` in the switch expression, returning `RegisterRoutineCmd`.
- **FR-005** (`alice-agent-command`): `RoutineTimeCmd` MUST reside in `alice-agent-command/src/main/java/org/cland/alice/agent/command/RoutineTimeCmd.java`
- **FR-006** (`alice-agent-command`): `module-info.java` in `alice-agent-command` MUST export the new `org.cland.alice.agent.command` package (already exported; no change needed).
- **FR-007** (`alice-facade-cmd`): The `CommandParser` MUST add a `alice routine` picocli subcommand with:
  - `@Parameters(index = "0", description = "Cron expression or task definition")` parameter
  - `@Option(names = {"--list", "-l"}, description = "List registered routines")` flag
  - `@Option(names = {"--remove", "-r"}, description = "Remove a routine by ID")` parameter
  - `toRunConfig()` returning a `RunConfig` with `routineTask` and `listRoutines` fields
- **FR-008** (`alice-facade-cmd`): `RunConfig` MUST gain two new optional fields: `String routineCron` and `boolean listRoutines`.
- **FR-009** (`alice-facade-cmd`): `CommandParser.parseToAgentCommand()` MUST handle `/routine` by delegating to the updated `AgentCommand.parse()` (no custom logic needed).
- **FR-010** (`alice-facade-tui`): `SlashCommand.parse()` MUST recognize `/routine` and return a new `SlashCommand` with type `Type.CONFIG`.
- **FR-011** (`alice-facade-tui`): `CommandHandler` MUST handle the `/routine` command in `handleConfig()`:
  - If no args: display usage "用法: /routine <cron表达式>"
  - If args present: convert to `AgentCommand` via `cmd.toAgentCommand()`, dispatch via `onAgentCommand`, and display confirmation
- **FR-012** (`alice-facade-tui`): `SlashCommand.helpText()` MUST include the `/routine` command entry.

### Key Entities *(include if feature involves data)*

- **RoutineTimeCmd (sealed interface)**: Fifth branch of the AgentCommand hierarchy, representing time-based autonomous task triggers. Contains two concrete record types: `RegisterRoutineCmd` (user-intended registration) and `TimeTriggeredCmd` (kernel-intended execution).
- **RunConfig.routineCron**: Optional string field in the CLI config object, populated when the `alice routine` subcommand is used. Passed to the agent core for routine registration.
- **SlashCommand (for /routine)**: New recognized entry in the TUI slash command registry, mapped to `Type.CONFIG`. Converted to `RegisterRoutineCmd` via `AgentCommand.parse()`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Developers can instantiate both `RegisterRoutineCmd` and `TimeTriggeredCmd` through the `RoutineTimeCmd` sealed interface and verify exhaustiveness via pattern matching switch — verified by automated compilation and behavioural tests.
- **SC-002**: `AgentCommand.parse("/routine \"0 */2 * * * ?\"")` returns a non-null `RegisterRoutineCmd` instance with the correct cron expression — verified by a Spock test.
- **SC-003**: CLI users can run `alice routine "0 */2 * * * ?"` and obtain a `RunConfig` with `routineCron` set — verified by a Spock integration test.
- **SC-004**: TUI users typing `/routine 0 */2 * * * ?` see a confirmation message and the command is dispatched correctly — verified by automated behaviour tests.
- **SC-005**: All 11 existing modules still compile without warnings (no breaking changes to existing command hierarchy consumers) — verified by `./gradlew build`.
- **SC-006**: `spotlessCheck` passes across all three modified modules — verified by `./gradlew spotlessCheck`.

## Assumptions

- The `AgentCommand.parse()` method is the single authoritative parser for all user-facing string-to-command conversion — `CommandParser.parseToAgentCommand()` and `SlashCommand.toAgentCommand()` both delegate to it.
- The `TimeTriggeredCmd` will never be created from user input; it is only constructed programmatically by the kernel's `CronScheduler` component (which is out of scope for this spec).
- CLI quoting rules (shell escaping for cron expressions with special chars like `*`) are handled by the user's shell, not by the `CommandParser`.
- The existing `module-info.java` files already export the required packages; no new exports are needed.
- `alice-facade-cmd` and `alice-facade-tui` already depend on `alice-agent-command` via `requires alice.agent.command.main` — no Gradle dependency changes needed.
