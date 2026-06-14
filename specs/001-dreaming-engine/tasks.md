---

description: "Task list for Offline Dreaming Engine feature implementation"
---

# Tasks: Offline Dreaming Engine

**Input**: Design documents from `/specs/001-dreaming-engine/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Test tasks are included only where needed to validate core behavior. Tests are minimized per Task Generation Rules (not explicitly requested in spec), but constitution-mandated Spock tests are included for key components.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Source root**: `alice-memory-vault/src/main/java/org/cland/alice/memory/`
- **Test root**: `alice-memory-vault/src/test/groovy/org/cland/alice/memory/`
- New dreaming package: `dreaming/` under both source and test roots

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the dreaming package directory structure and configure module-info exports

- [x] T001 Create `dreaming` package directory at `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/`
- [x] T002 Create `dreaming` test directory at `alice-memory-vault/src/test/groovy/org/cland/alice/memory/dreaming/`
- [x] T003 Check `alice-memory-vault/src/main/java/module-info.java` — no export needed if all dreaming classes are consumed internally within `alice-memory-vault`; add `exports org.cland.alice.memory.dreaming` only if required by other modules

**Checkpoint**: Package structure ready for implementation

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core entity and configuration types that MUST be complete before ANY user story can begin

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T004 Create `DreamingTriggerConfig.java` — immutable record with fields: `idleCooldownMs` (default 60000), `walThresholdEntries` (default 500), `walThresholdBytes` (default 10_485_760), `pollingIntervalMs` (default 30000), `maxConcurrency` (default 1), `maxStepsPerCycle` (default 1000). Include validation in compact constructor that throws `IllegalArgumentException` for values outside allowed ranges. Write to `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/DreamingTriggerConfig.java`
- [x] T005 Create `DreamingSession.java` — immutable record with fields: `sessionId` (String), `startTime` (long), `endTime` (Long, nullable), `durationMs` (Long, nullable), `episodicSummaryId` (String, nullable), `conflictCount` (int), `patternsCrystallized` (int), `outcome` (DreamingOutcome enum: SUCCESS, FAILURE, SKIPPED). Write to `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/DreamingSession.java`
- [x] T006 Create `DreamingFact.java` — immutable record with fields: `factId` (String), `content` (String), `sourceSessionId` (String), `sourceMessageId` (long), `timestamp` (long), `confidence` (double, 0.0–1.0). Write to `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/DreamingFact.java`
- [x] T007 Create `CrystallizedPattern.java` — immutable record with fields: `patternId` (String), `toolSequence` (List\<String\>), `occurrenceCount` (int), `sourceSessionId` (String), `firstSeen` (long), `successRate` (double, 0.0–1.0). Write to `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/CrystallizedPattern.java`
- [x] T008 [P] Create `SessionState.java` — enum with values: CREATED, RUNNING, COMPLETED, CRASHED, DREAMING, ARCHIVED. Write to `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/SessionState.java`
- [x] T009 [P] Create `StateTransitionException.java` — runtime exception with fields: `sessionId` (String), `from` (SessionState), `to` (SessionState). Write to `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/StateTransitionException.java`
- [x] T010 [P] Add SLF4J `Logger` field to all new classes using `LoggerFactory.getLogger(getClass())`
- [x] T011 Create Spock test `DreamingTriggerConfigSpec.groovy` — covers construction, defaults, validation rules (idleCooldownMs ≥ 1000, walThresholdEntries ≥ 10, etc.) in `alice-memory-vault/src/test/groovy/org/cland/alice/memory/dreaming/DreamingTriggerConfigSpec.groovy`
- [x] T012 [P] Create Spock test `DreamingSessionSpec.groovy` — covers all fields, null rejection, enum values, equality in `alice-memory-vault/src/test/groovy/org/cland/alice/memory/dreaming/DreamingSessionSpec.groovy`

**Checkpoint**: Foundation ready — user story implementation can now begin in parallel

---

## Phase 3: User Story 1 — Automatic Memory Evolution (P1) 🎯 MVP

**Goal**: Implement the core DreamingEngine pipeline that consumes raw WAL logs from a COMPLETED/CRASHED WalSession and produces episodic summaries (→ EpisodicVault), semantic facts (→ SemanticVault), and crystallized SOPs (→ ProceduralVault).

**Independent Test**: Write known WAL entries to InMemoryWalStore, call `DreamingEngine.process(sessionId)`, assert that EpisodicVault contains an episodic summary Step, SemanticVault "_dreaming_facts" collection contains Knowledge entries, and ProceduralVault contains SOP entries.

### Implementation for User Story 1

- [x] T013 [P] [US1] Create offline `PromptMelter.java` — component that reads all raw WAL messages from a session via `WalStore.getAllMessages(sessionId)`, produces an `EpisodicSummary` record (summaryId, sessionId, stepCount, keyActions as List\<String\>, outcome string, summaryText, createdAt long). Apply noise reduction: strip `tool_call_id` metadata, deduplicate consecutive identical status messages, normalize timestamps. Write to `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/PromptMelter.java`

  **Note**: This is the *offline dreaming* PromptMelter, distinct from the existing `wal/PromptMelter.java` which serves the *online context assembly* use case.

- [x] T014 [P] [US1] Create `Crystallizer.java` — component that scans WAL messages for `assistant` messages containing non-null `toolCalls`, extracts ordered tool-call sequences via sliding window (size 2–5), and if any exact sequence appears 3+ times, creates a SOP entry via `ProceduralVault.register()`. SOP naming: `sopId = "dreaming-<sessionId>-<hash>"`, `name` = tool names joined by "→", `pattern` = comma-separated tool names, `version = "0.1.0"`. Write to `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/Crystallizer.java`
- [x] T015 [US1] Create `DreamingEngine.java` — orchestrator that:
  - Constructor takes `WalStore`, `EpisodicVault`, `SemanticVault`, `ProceduralVault`, `DreamingTriggerConfig`
  - `process(sessionId)` method:
    1. Reads all messages via `WalStore.getAllMessages(sessionId)`
    2. Runs `PromptMelter` → writes Step with `action="dreaming_summary"` to `EpisodicVault.appendStep(sessionId, step)`
    3. Extracts `DreamingFact` entries from messages → runs `ConflictResolver.resolve(facts, sessionId)` to write to `SemanticVault`
    4. Runs `Crystallizer.crystallize(messages, sessionId)` to write SOPs to `ProceduralVault`
    5. Returns `DreamingSession` with outcome SUCCESS and stats
  - `processAll()` — iterates `WalStore.activeSessionIds()`, calls process() on each
  - `startBackgroundTriggers()` / `stopBackgroundTriggers()` / `isBackgroundRunning()` — stub methods (implemented in US4)
  - `pendingSessionCount()` — stub
  - `recentSessions(limit)` — returns empty list (stub)
  - Write to `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/DreamingEngine.java`

- [x] T016 [US1] Create Spock test `DreamingEngineSpec.groovy` — covers:
  - Processing session with 5+ WAL entries → EpisodicVault contains Step with action="dreaming_summary"
  - Processing session with repeated tool patterns → ProceduralVault contains SOP entries
  - Processing session with zero entries → graceful handling (empty summary, no errors)
  - Write to `alice-memory-vault/src/test/groovy/org/cland/alice/memory/dreaming/DreamingEngineSpec.groovy`

**Checkpoint**: At this point, User Story 1 should be fully functional and testable independently. The core Dreaming pipeline (WAL → episodic + semantic + procedural) is complete.

---

## Phase 4: User Story 2 — Conflict Resolution & Knowledge Pruning (P2)

**Goal**: Add ConflictResolver that detects contradictions between new WAL facts and existing SemanticVault knowledge, marks old facts as DEPRECATED, and promotes newer facts.

**Independent Test**: Pre-seed SemanticVault collection "_dreaming_facts" with a Knowledge entry (content "db_timeout is 30s", createdAt=T1). Call `conflictResolver.resolve([DreamingFact("db_timeout is 60s", timestamp=T2>T1)], "session-1")`. Verify: old Knowledge content has "(DEPRECATED)" prefix, new Knowledge exists with content "db_timeout is 60s", `ResolveResult.deprecatedFacts = 1`.

### Implementation for User Story 2

- [x] T017 [P] [US2] Create `ConflictResolver.java` — component that:
  - Constructor takes `SemanticVault`
  - `resolve(List<DreamingFact> facts, String sessionId)` method:
    1. For each DreamingFact with `confidence >= 0.5` (skip low-confidence facts):
       - Search SemanticVault collection "_dreaming_facts" for matching content (exact match after whitespace normalization via `getAll()`)
       - If match found: compare timestamps
         - Newer fact wins: store new Knowledge, mark old by appending "(DEPRECATED)" to its content (via `remove()` then `store()`)
         - Equal timestamp (within 1s tolerance): mark old with "(MANUAL_REVIEW)" prefix, store new
       - If no match: store as new Knowledge in "_dreaming_facts" collection
    2. Returns `ResolveResult` record (factsProcessed, newFacts, deprecatedFacts, manualReviewFacts)
  - Write to `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/ConflictResolver.java`
- [x] T018 [US2] Integrate ConflictResolver into DreamingEngine — call `conflictResolver.resolve(facts, sessionId)` after PromptMelter output, replacing the direct SemanticVault write. Update `DreamingEngine.process()` to pass extracted facts through ConflictResolver. File: `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/DreamingEngine.java`
- [x] T019 [P] [US2] Create Spock test `ConflictResolverSpec.groovy` — covers:
  - Newer fact with same content → old DEPRECATED, new stored
  - No conflict → new fact stored as ACTIVE
  - Equal timestamps → both marked MANUAL_REVIEW
  - Low-confidence fact (< 0.5) → skipped
  - Empty fact list → no-op
  - Write to `alice-memory-vault/src/test/groovy/org/cland/alice/memory/dreaming/ConflictResolverSpec.groovy`

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently.

---

## Phase 5: User Story 3 — Session Lifecycle Locking & Deduplication (P3)

**Goal**: Add SessionStateManager for thread-safe session state tracking. Ensure exactly-once Dreaming processing via atomic CAS locking.

**Independent Test**: Initiate two concurrent `DreamingEngine.process("same-session")` calls — exactly one returns SUCCESS, the other returns SKIPPED. Session transitions: COMPLETED → DREAMING → ARCHIVED.

### Implementation for User Story 3

- [x] T020 [P] [US3] Create `SessionStateManager.java` — component that:
  - Constructor takes `WalStore walStore`
  - Maintains `ConcurrentMap<String, SessionState> stateMap` (not modifying WalStore interface)
  - `getState(sessionId)` — returns state or CREATED if absent
  - `transition(sessionId, from, to)` — validates legal transitions from data-model.md state machine:
    - `CREATED → RUNNING`, `RUNNING → COMPLETED`, `RUNNING → CRASHED`
    - `COMPLETED → DREAMING`, `CRASHED → DREAMING`
    - `DREAMING → ARCHIVED`, `DREAMING → COMPLETED`, `DREAMING → CRASHED`
    - `COMPLETED → ARCHIVED` (replay skip)
    - Invalid → throws `StateTransitionException`
  - `isDreamable(sessionId)` — returns true if state is COMPLETED or CRASHED
  - `tryLockForDreaming(sessionId)` — atomic CAS: COMPLETED→DREAMING or CRASHED→DREAMING
  - Write to `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/SessionStateManager.java`
- [x] T021 [US3] Integrate SessionStateManager into DreamingEngine — update `process(sessionId)`:
  1. Before processing: call `sessionStateManager.tryLockForDreaming(sessionId)` → if false, return DreamingSession with SKIPPED
  2. On success: call `sessionStateManager.transition(sessionId, DREAMING, ARCHIVED)`
  3. On failure (catch Exception): call `sessionStateManager.transition(sessionId, DREAMING, COMPLETED)` or `CRASHED` based on original state, return DreamingSession with FAILURE
  4. Update `DreamingEngine` constructor to accept optional `SessionStateManager` parameter
  - File: `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/DreamingEngine.java`
- [x] T022 [P] [US3] Create Spock test `SessionStateManagerSpec.groovy` — covers:
  - All legal transitions succeed
  - Invalid transitions throw StateTransitionException
  - `isDreamable` returns true for COMPLETED/CRASHED, false for others
  - `tryLockForDreaming` atomically locks and returns true/false
  - Write to `alice-memory-vault/src/test/groovy/org/cland/alice/memory/dreaming/SessionStateManagerSpec.groovy`
- [x] T023 [US3] Create concurrency Spock test `DreamingEngineConcurrencySpec.groovy` — covers:
  - Two threads calling `process("same-session")` via CountDownLatch → one SUCCESS, one SKIPPED
  - Successful process → state becomes ARCHIVED
  - Process on DREAMING/ARCHIVED session → SKIPPED
  - Write to `alice-memory-vault/src/test/groovy/org/cland/alice/memory/dreaming/DreamingEngineConcurrencySpec.groovy`

**Checkpoint**: User Stories 1, 2, and 3 are independently functional. Deduplication and lifecycle safety confirmed.

---

## Phase 6: User Story 4 — Trigger Mechanisms (P3)

**Goal**: Add on-demand, idle-timer, and WAL-threshold trigger mechanisms. Enforce READ-lock on DREAMING sessions for online ReAct.

**Independent Test**: Configure `idleCooldownMs = 100`, set system idle, verify automatic processing within polling interval. Configure `walThresholdEntries = 10`, populate 11 entries, verify forced processing.

### Implementation for User Story 4

- [x] T024 [US4] Implement `processAll()` in DreamingEngine — iterate all sessions via `WalStore.activeSessionIds()`, filter dreamable via `SessionStateManager.isDreamable()`, call `process(sessionId)` for each — in `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/DreamingEngine.java`
- [x] T025 [US4] Implement `startBackgroundTriggers()` — use `ScheduledExecutorService.newSingleThreadScheduledExecutor()` to schedule a polling task at `triggerConfig.pollingIntervalMs()`:
  - Check last-activity timestamp (tracked via a simple `lastActivityTime` AtomicLong in DreamingEngine, updated on every ReAct call via a setter)
  - If `System.currentTimeMillis() - lastActivityTime >= triggerConfig.idleCooldownMs()` → call `processAll()`
  - Also check total WAL entries across all sessions via `WalStore.activeSessionIds()` → sum of `messageCount()` — if exceeds `triggerConfig.walThresholdEntries()` → call `processAll()`
  - Must respect `triggerConfig.maxConcurrency()` (use `Semaphore` to limit concurrent process() calls)
  - Write to `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/DreamingEngine.java`
- [x] T026 [US4] Implement `stopBackgroundTriggers()` — `scheduler.shutdown()` and await termination — and `isBackgroundRunning()` — check if scheduler is not shutdown — in `DreamingEngine.java`
- [x] T027 [US4] Add `setLastActivityTime()` method to DreamingEngine — to be called by the online ReAct loop to indicate system activity; update `pendingSessionCount()` to return count of sessions with COMPLETED or CRASHED state — in `DreamingEngine.java`
- [x] T028 [US4] Enforce READ-lock on DREAMING sessions — create `WalSessionReadGuard.java` that wraps a `WalStore` reference and checks `SessionStateManager.getState(sessionId)` before allowing reads:
  - If state is DREAMING → throws `IllegalStateException("Session is being dreamed")`
  - Otherwise → delegate to WalStore
  - Write to `alice-memory-vault/src/main/java/org/cland/alice/memory/dreaming/WalSessionReadGuard.java`
- [x] T029 [P] [US4] Create Spock test `DreamingEngineTriggerSpec.groovy` — covers:
  - `processAll()` processes all COMPLETED sessions
  - `processAll()` skips non-dreamable sessions
  - `recentSessions(limit)` returns correct number of records
  - Write to `alice-memory-vault/src/test/groovy/org/cland/alice/memory/dreaming/DreamingEngineTriggerSpec.groovy`
- [x] T030 [P] [US4] Create Spock test `WalSessionReadGuardSpec.groovy` — covers:
  - Read from DREAMING session is blocked (IllegalStateException)
  - Read from COMPLETED session is allowed
  - Read from non-existent session returns empty
  - Write operations allowed on DREAMING sessions
  - Write to `alice-memory-vault/src/test/groovy/org/cland/alice/memory/dreaming/WalSessionReadGuardSpec.groovy`

**Checkpoint**: All user stories should now be independently functional. Trigger mechanisms and online-plane isolation confirmed.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [x] T031 Create integration test `DreamingEngineIntegrationSpec.groovy` — full end-to-end: write WAL entries → trigger Dreaming → verify all three vaults updated correctly — at `alice-memory-vault/src/test/groovy/org/cland/alice/memory/dreaming/DreamingEngineIntegrationSpec.groovy`
- [x] T032 Update `alice-memory-vault/src/main/java/module-info.java` — export `org.cland.alice.memory.dreaming` package if consumed externally, or keep as internal package (internally consumed, no export needed)
- [x] T033 Run `./gradlew :alice-memory-vault:build` — ensure all existing + new tests pass, no compilation warnings
- [x] T034 Run `./gradlew spotlessCheck` — ensure Google Java Format compliance (included in build)
- [x] T035 Update `todos/TODO-memory-vault.md` — mark Dreaming Engine feature as in-progress, add completed tasks

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Stories (Phase 3–6)**: All depend on Foundational completion
  - US1 (P1): Core pipeline — no dependency on other stories
  - US2 (P2): Depends on US1 (DreamingEngine must exist to integrate ConflictResolver)
  - US3 (P3): Depends on US1 (DreamingEngine core), independent of US2
  - US4 (P3): Depends on US1 + US3 (needs DreamingEngine + SessionStateManager), independent of US2
- **Polish (Phase 7)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Start after Foundational — no other story dependencies
- **User Story 2 (P2)**: Start after US1 complete — integrates into existing `process()` method
- **User Story 3 (P3)**: Start after US1 complete — adds locking wrapper around `process()`
- **User Story 4 (P3)**: Start after US1 + US3 complete — needs locking for deduplication

### Within Each User Story

- Data model/entity tasks before service/component tasks
- Component tasks before engine integration tasks
- Test tasks after implementation (or in parallel with same-file work)

### Parallel Opportunities

| Tasks | Reason |
|-------|--------|
| T004–T010 | All different files, no dependencies |
| T013 (PromptMelter) + T014 (Crystallizer) | Independent components |
| T017 (ConflictResolver) + T020 (SessionStateManager) | Independent feature areas |
| T024–T028 | Different trigger mechanisms, same file but different methods |
| T011 + T012 (tests) | Independent test files |

---

## Parallel Example: User Story 1

```bash
# Launch PromptMelter and Crystallizer in parallel:
Task: T013 [P] Create offline PromptMelter in dreaming/PromptMelter.java
Task: T014 [P] Create Crystallizer in dreaming/Crystallizer.java
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T003)
2. Complete Phase 2: Foundational (T004–T012)
3. Complete Phase 3: User Story 1 (T013–T016)
4. **STOP and VALIDATE**: Run `DreamingEngineSpec.groovy` — assert core pipeline works end-to-end
5. Deploy/demo: Core WAL → memory evolution pipeline operational

### Incremental Delivery

1. Phase 1 + Phase 2 → Foundation ready
2. Phase 3 (US1) → Core pipeline (MVP!) → Test independently
3. Phase 4 (US2) → Conflict resolution → Test independently
4. Phase 5 (US3) → Session locking + deduplication → Test independently
5. Phase 6 (US4) → Trigger mechanisms → Test independently
6. Phase 7 → Polish, integration tests, CI verification

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable
- The offline PromptMelter (dreaming/) is distinct from the existing online PromptMelter (wal/) — they share the name but serve different stages (offline dreaming vs. online context assembly)
- `SessionStateManager` uses an internal ConcurrentMap — it does NOT modify the WalStore interface, maintaining backward compatibility
- `WalSessionReadGuard` provides READ-lock enforcement for the online ReAct plane
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
