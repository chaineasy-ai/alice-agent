---
title: "Alice Agent Command Module — Hole Test Scene"
summary: "Scene documentation for 2 probe holes in the alice-agent-command module boundary"
read_when:
  - "reviewing command module hole test coverage"
  - "running or extending command module hole tests"
  - "understanding the module boundary probe map"
scope:
  - "docs/alice-agent-command/e2e"
  - "alice-agent-command/src/hole/java/"
  - "alice-agent-command/src/main/java/org/cland/alice/agent/command/"
status: "active"
updated: "2026-06-21"
---

# Alice Agent Command Module — Hole Test Scene

## Probe Map

```
                    ┌──────────────────────────────────────┐
                    │  alice-agent-command module          │
                    │                                      │
                    │  ● CMD-P01: Module classpath         │
   Class.forName()──┤  ├── 21 concrete records resolve    │
                    │  ├── Each instanceof AgentCommand   │
                    │  └── Proves module-info.java exports│
                    │                                      │
                    │  ● CMD-P02: Parse dispatch pipeline │
   "/run ..." ──────┤  ├── ExecutionCmd.AcquireGoalCmd    │
   "/reload" ───────┤  ├── CapabilityCmd.ReloadKernelCmd  │
   "/model ..." ────┤  ├── AlignmentCmd.SwitchModelCmd    │
   "/new" ──────────┤  ├── ControlCmd.ResetSessionCmd     │
   "/routine ..." ──┤  ├── RoutineTimeCmd.RegisterRoutineCmd│
   "/sub-agent ..."─┤  └── SubAgentCmd.ListSubAgentsCmd   │
                    │                                      │
                    └──────────────────────────────────────┘
```

## Hole Status

| Hole ID | Status | Verifier | Notes |
|---------|--------|----------|-------|
| CMD-P01 | 🟩 GREEN | `CommandHoleTest.probeCmdP01()` | 21 classes resolve via Class.forName() |
| CMD-P02 | 🟩 GREEN | `CommandHoleTest.probeCmdP02()` | 6 branches dispatch in standalone JVM |

## How to Run

### Prerequisites
- Python 3.10+
- JDK 25+
- Gradle wrapper (`./gradlew.bat` on Windows, `./gradlew` on Unix)

### Run All Hole Tests
```bash
python docs/alice-agent-command/e2e/hole_test_command_module.py
```

### Run Individual Holes via Gradle
```bash
# CMD-P01: module classpath resolution
./gradlew :alice-agent-command:runHoleTest --args "CMD-P01"

# CMD-P02: dispatch pipeline (1 per branch)
./gradlew :alice-agent-command:runHoleTest --args "CMD-P02"
```

### Verify Unit Tests Still Pass
```bash
./gradlew :alice-agent-command:test
```

## Source Files Under Probe

| Source File | Probed By |
|-------------|-----------|
| `AgentCommand.java` | CMD-P01, CMD-P02 |
| `ExecutionCmd.java` | CMD-P01, CMD-P02 |
| `CapabilityCmd.java` | CMD-P01, CMD-P02 |
| `AlignmentCmd.java` | CMD-P01, CMD-P02 |
| `ControlCmd.java` | CMD-P01, CMD-P02 |
| `RoutineTimeCmd.java` | CMD-P01, CMD-P02 |
| `SubAgentCmd.java` | CMD-P01, CMD-P02 |
| `SpawnSubAgentCmd.java` | CMD-P01 |
| `ConnectSubAgentCmd.java` | CMD-P01 |
| `ListSubAgentsCmd.java` | CMD-P01, CMD-P02 |
| `CancelSubAgentCmd.java` | CMD-P01 |
| `GetSubAgentResultsCmd.java` | CMD-P01 |
| `SendToSubAgentCmd.java` | CMD-P01 |
| `PromptSubAgentCmd.java` | CMD-P01 |
| `module-info.java` | CMD-P01 (exports must be correct for Class.forName) |

## Hole Test Implementation

All holes are in a single Java class:

**File**: `alice-agent-command/src/hole/java/org/cland/alice/agent/command/CommandHoleTest.java`

| Method | Hole | Assertion | Lines |
|--------|------|-----------|-------|
| `probeCmdP01()` | CMD-P01 | All 21 FQCNs resolve via Class.forName() | 18 |
| `probeCmdP02()` | CMD-P02 | All 6 branches dispatch to correct subtype | 12 |

## Removed Holes

Previously this module had 4 holes. 3 were removed per the
[Hole Design Principles](C:\Users\Administrator\.agents\skills\hole-tdd\SKILL.md#8-forbid-repeating-unit-tests--hole-is-inbound-business-case-only):

| Hole | Violation | Migrated To |
|------|-----------|-------------|
| CMD-P02 (sealed hierarchy) | Duplicated `AgentCommandSealedHierarchySpec` + `SubAgentCmdSealedHierarchySpec` | Already in Spock |
| CMD-P03 (toString integrity) | Duplicated `SubAgentCmdSpec` toString tests; Java record auto-generates toString | Already in Spock |
| CMD-P04 (null safety) | Edge case testing (null injections), not a business case | Added to `ExecutionCmdSpec`, `CapabilityCmdSpec`, `ControlCmdSpec` |

## Extending Hole Tests

To add a new hole:

1. Define the hole in `case-hole-test.md` following the HOLE-ID table format
2. Add a probe method `probeCmdP0X()` in `CommandHoleTest.java`
3. Add a `case "CMD-P0X"` dispatch in the `main()` method
4. Add a test function in `hole_test_command_module.py`
5. Register the test in `main()`
6. Run the hole test script until GREEN
7. Update this scene doc with the new hole status

**Checklist before adding a hole:**
- ❓ Is the assertion already covered by an existing Spock spec? → If yes, **don't add**
- ❓ Is this an edge case (null, empty, invalid)? → If yes, add to `src/test/groovy` instead
- ❓ Does it probe a true module boundary concern? → If no, reconsider
