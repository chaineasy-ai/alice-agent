# Quickstart: Routine-Time Command Model Update

> **Date**: 2026-06-14
> **Purpose**: Validation scenarios to verify the feature works end-to-end

## Prerequisites

- Java 25+ JDK
- Gradle 9.5 (wrapper provided)
- All existing modules building (`./gradlew build` passes)

## Setup

No setup required — this is an additive code change to existing modules. After implementation:

```bash
cd /path/to/alice-agent
./gradlew :alice-agent-command:build :alice-facade-cmd:build :alice-facade-tui:build
```

## Validation Scenarios

### Scenario 1: RoutineTimeCmd Instantiation (Compilation Test)

**Objective**: Verify the new sealed interface and records compile correctly and can be pattern-matched exhaustively.

**Prerequisites**: `RoutineTimeCmd.java` created, `AgentCommand.java` updated.

**Steps**:
1. Write a Spock test (in `alice-agent-command/src/test/groovy/`) that:
   - Creates a `RegisterRoutineCmd("0 */2 * * * ?", "s1", "t1", Instant.now())`
   - Creates a `TimeTriggeredCmd("health-check", "s2", "t2", Instant.now())`
   - Pattern-matches both via a switch on `RoutineTimeCmd`
   - Asserts field values and `sessionId()`/`traceId()` inheritance

**Expected outcome**: All assertions pass.

**See contracts**: [RoutineTimeCmd-contract.md](./contracts/RoutineTimeCmd-contract.md)

---

### Scenario 2: AgentCommand.parse() /routine

**Objective**: Verify that `AgentCommand.parse()` returns the correct command type for `/routine` inputs.

**Prerequisites**: Scenario 1 complete.

**Steps**:
1. Write a Spock test that calls `AgentCommand.parse("/routine 0 */2 * * * ?", "s1", "t1")`
2. Assert the returned object is a `RegisterRoutineCmd`
3. Assert `cronExpression()` equals `"0 */2 * * * ?"`
4. Test with blank args: `AgentCommand.parse("/routine", "s1", "t1")`
5. Assert `cronExpression()` equals `""` (blank)
6. Test that non-slash input still returns `AcquireGoalCmd` (no regression)

**Expected outcome**: All assertions pass, no existing parsing behavior broken.

**See contracts**: [RoutineTimeCmd-contract.md](./contracts/RoutineTimeCmd-contract.md) (Integration Contract: AgentCommand.parse())

---

### Scenario 3: CLI alice routine Subcommand

**Objective**: Verify the picocli `routine` subcommand parses correctly.

**Prerequisites**: Scenario 2 complete.

**Steps**:
1. Run the full build: `./gradlew :alice-facade-cmd:build`
2. The CommandParser contract tests verify:
   - `CommandParser.parse(new String[]{"routine", "0 */2 * * * ?"})` returns `RunConfig` with `routineCron` set
   - `CommandParser.parse(new String[]{"routine", "--list"})` returns `RunConfig` with `listRoutines=true`
   - `CommandParser.parseToAgentCommand("/routine 0 */2 * * * ?")` returns `RegisterRoutineCmd`

**Expected outcome**: All tests pass.

**See contracts**: [CommandParser-contract.md](./contracts/CommandParser-contract.md)

---

### Scenario 4: TUI /routine Slash Command

**Objective**: Verify the TUI slash command parser and handler work correctly.

**Prerequisites**: Scenario 2 complete.

**Steps**:
1. Run the full build: `./gradlew :alice-facade-tui:build`
2. The TUI contract tests verify:
   - `SlashCommand.parse("/routine 0 */2 * * * ?")` returns a non-null `SlashCommand` with `Type.CONFIG`
   - `CommandHandler` dispatches `RegisterRoutineCmd` via `onAgentCommand` callback
   - `/routine` (no args) displays usage message without dispatching
   - `SlashCommand.helpText()` contains `/routine`

**Expected outcome**: All tests pass.

**See contracts**: [SlashCommand-contract.md](./contracts/SlashCommand-contract.md)

---

### Scenario 5: No Regression

**Objective**: Verify that no existing functionality is broken.

**Steps**:
```bash
./gradlew build
./gradlew spotlessCheck
```

**Expected outcome**: `BUILD SUCCESSFUL` everywhere — all existing 222+ tests pass, all modules compile without warnings, `spotlessCheck` clean.

## Verification Checklist

- [ ] Scenario 1: `RoutineTimeCmd` instantiation and pattern matching
- [ ] Scenario 2: `AgentCommand.parse()` `/routine` handling
- [ ] Scenario 3: CLI `alice routine` subcommand
- [ ] Scenario 4: TUI `/routine` slash command
- [ ] Scenario 5: No regression (`./gradlew build && spotlessCheck`)
