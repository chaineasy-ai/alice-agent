---

title: "Dreaming Engine — Quickstart Validation Guide"
summary: "How to validate the Dreaming Engine feature end-to-end"
read_when:
  - "validating the Dreaming Engine implementation"
  - "running feature validation tests"
scope:
  - "alice-memory-vault"
status: "active"
updated: "2026-06-15"
---

# Dreaming Engine — Quickstart Validation Guide

## Prerequisites

1. All code changes for the Dreaming Engine are compiled (`./gradlew :alice-memory-vault:compileJava`)
2. All unit tests pass (`./gradlew :alice-memory-vault:test`)
3. Spotless check passes (`./gradlew spotlessCheck`)

## Validation Scenarios

### Scenario 1: Basic Dreaming Cycle (MVP)

This validates User Story 1 — the core pipeline.

**Setup**:
```bash
# Ensure everything compiles
./gradlew :alice-memory-vault:compileJava
```

**Procedure** (via Spock test `DreamingEngineSpec.groovy`):
1. Create an `InMemoryWalStore` and populate it with 5+ WAL entries for a
   session in COMPLETED state
2. Create `InMemoryEpisodicVault`, `InMemorySemanticVault`, `InMemoryProceduralVault`
3. Create `DreamingEngine(walStore, episodicVault, semanticVault, proceduralVault, defaultConfig)`
4. Call `engine.process("test-session-1")`

**Expected Outcome**:
- Returns `DreamingSession` with `outcome = SUCCESS`
- `EpisodicVault` contains a Step with action="dreaming_summary"
- `SemanticVault` collection "_dreaming_facts" contains ≥ 1 Knowledge entry
- Pipeline completes in under 60s (for 20-step sessions)

### Scenario 2: Conflict Resolution

This validates User Story 2.

**Procedure** (via `ConflictResolverSpec.groovy`):
1. Pre-seed SemanticVault with Knowledge("db_timeout is 30s", createdAt=T1)
2. Create a DreamingFact with content "db_timeout is 60s", timestamp=T2 > T1
3. Run `conflictResolver.resolve([fact], "session-1")`

**Expected Outcome**:
- Old Knowledge still exists but marked with "(DEPRECATED)" prefix
- New Knowledge exists with content "db_timeout is 60s"
- `ResolveResult.deprecatedFacts = 1`, `newFacts = 1`

### Scenario 3: Session Locking & Deduplication

This validates User Story 3.

**Procedure** (via `DreamingEngineConcurrencySpec.groovy`):
1. Start two threads, each calling `engine.process("same-session")`
2. Use a `CountDownLatch` to synchronize their start

**Expected Outcome**:
- Exactly one thread completes with SUCCESS
- The other thread returns SKIPPED (or processes a different session)
- Session state advances to ARCHIVED

### Scenario 4: Trigger Mechanisms

This validates User Story 4.

**Procedure** (via `DreamingEngineTriggerSpec.groovy`):
1. Create engine with `idleCooldownMs = 100`
2. Set system as idle, wait 200ms

**Expected Outcome**:
- Background polling detects idle condition
- Pending sessions are processed automatically within 2 polling intervals

## Running All Tests

```bash
# Unit tests for the dreaming package
./gradlew :alice-memory-vault:test --tests "org.cland.alice.memory.dreaming.*"
```

Expected: All tests pass with 0 failures.

## Full Build Verification

```bash
# Complete build + tests + formatting
./gradlew :alice-memory-vault:build
./gradlew spotlessCheck
```

## Additional Resources

- See [data-model.md](./data-model.md) for full entity definitions and state machine
- See [contracts/](./contracts/) for interface contracts
- See [tasks.md](./tasks.md) for implementation task breakdown
