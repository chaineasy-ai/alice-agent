---
title: "Alice Agent Command Module — Hole Test Case"
summary: "Case specification for 2 probe holes drilled into the alice-agent-command module boundary"
read_when:
  - "adding hole tests for the command module"
  - "verifying module-level public API coverage"
  - "understanding module classpath resolution and dispatch pipeline"
scope:
  - "docs/alice-agent-command/e2e"
  - "alice-agent-command/src/hole/java/"
  - "alice-agent-command/src/main/java/org/cland/alice/agent/command/"
status: "active"
updated: "2026-06-21"
---

# Alice Agent Command Module — Hole Test Case

## Design Principles Applied

This hole test suite follows these constraints:

| Principle | Applied |
|-----------|---------|
| No duplicate unit test coverage | ✅ All holes probe boundaries NOT covered by Spock specs |
| No edge cases | ✅ Null/empty/invalid tested only in `src/test/groovy` |
| One hole = one business case | ✅ 2 holes, each with a single business assertion |
| Probe boundary, not internals | ✅ Classloading + dispatch pipeline, not record fields |

### Unit Test Coverage (src/test/groovy) — NOT Reduplicated

| What Spock covers | What holes DON'T test |
|-------------------|-----------------------|
| All 17 parse permutations returning correct subtype | No individual parse cases (CMD-P02 uses 1/branch as smoke) |
| All 21 records instanceof AgentCommand | **Removed** (CMD-P01 tests classpath resolution instead) |
| All 21 records toString() contain class name | **Removed** (Java record contract, not a business probe) |
| Null safety — all 69 mandatory fields reject null | **Removed** — edge cases belong in Spock |
| Missing null safety now added to: `ExecutionCmdSpec`, `CapabilityCmdSpec`, `ControlCmdSpec` | ✅ Gap closed in unit tests |

## Module Boundary

The `alice-agent-command` module exposes a single public API surface:

```
                     ┌────────────────────────────────┐
                     │ alice-agent-command module      │
                     │                                │
  raw input ─────────► AgentCommand.parse()  ─────────► AgentCommand (sealed)
                     │   ├── ExecutionCmd             │
  "/run ..." ────────┤   ├── CapabilityCmd            │
  "/exec ..." ───────┤   ├── AlignmentCmd             │
  "/model ..." ──────┤   ├── ControlCmd               │
  "/new ..." ────────┤   ├── RoutineTimeCmd           │
  "/sub-agent ..." ──┤   └── SubAgentCmd              │
                     │                                │
                     │  (21 concrete records total)    │
                     └────────────────────────────────┘
```

## Probe Holes

### CMD-P01: Module Classpath Resolves All Sealed Types

| Field | Value |
|-------|-------|
| **Target** | `Class.forName()` for all 21 concrete record classes |
| **Input** | 21 fully-qualified class names (including inner classes like `ExecutionCmd$AcquireGoalCmd`) |
| **Expected** | Every class loads and is `instanceof AgentCommand` |
| **Assertion** | `Class.forName(fqcn) != null` + `AgentCommand.class.isAssignableFrom(clazz)` for all 21 classes |
| **Entry** | `CommandHoleTest.probeCmdP01()` |
| **Why not Spock?** | Spock constructs records directly, never testing `Class.forName()` or module path reflection. This hole detects missing `module-info.java` exports, split packages, or classpath/module-path conflicts. |

### CMD-P02: Parse Dispatch Pipeline End-to-End (1 per Branch)

| Field | Value |
|-------|-------|
| **Target** | `AgentCommand.parse(String, String, String)` |
| **Input** | 6 commands, one per branch: `/run`, `/reload`, `/model`, `/new`, `/routine`, `/sub-agent list` |
| **Expected** | Every result is non-null with the correct simple class name |
| **Assertion** | `result.getClass().getSimpleName().equals(expectedSimpleName)` for all 6 |
| **Entry** | `CommandHoleTest.probeCmdP02()` |
| **Why not Spock?** | Spock tests individual parse cases inside the Gradle test runner. This hole exercises the full JavaExec runtime: module path resolution, class loading, static init, and sealed dispatch in a standalone JVM session — proving the module works as a runtime dependency for bootstrap. |

## File Layout

| File | Purpose |
|------|---------|
| `src/hole/java/.../CommandHoleTest.java` | Java main class with 2 probe methods |
| `build.gradle` | Hole source set + `runHoleTest` JavaExec task |
| `e2e/hole_test_command_module.py` | Python orchestrator that builds and runs all holes |
| `e2e/case-hole-test.md` | This case doc |
| `e2e/scene-command-endpoints.md` | Scene doc with how-to-run and status |

## Removed Holes (moved to unit tests)

| Hole | Reason | Migrated to |
|------|--------|-------------|
| CMD-P02 (sealed hierarchy) | Duplicates `AgentCommandSealedHierarchySpec` + `SubAgentCmdSealedHierarchySpec` | Already covered |
| CMD-P03 (toString) | Java record contract; duplicates `SubAgentCmdSpec` toString tests | Already covered |
| CMD-P04 (null safety) | Edge case testing, not business case | Added to `ExecutionCmdSpec`, `CapabilityCmdSpec`, `ControlCmdSpec` (2026-06-21) |
