---

title: "Dreaming Engine — Data Model"
summary: "Entity definitions, relationships, and state transitions for the offline Dreaming Engine"
read_when:
  - "implementing the Dreaming Engine data models"
  - "understanding entity relationships and state machines"
scope:
  - "alice-memory-vault"
status: "active"
updated: "2026-06-15"
---

# Dreaming Engine — Data Model

## Entity Overview

The Dreaming Engine introduces five new entity types and extends one existing
interface (WalStore) with session state tracking. All entities live in the
`org.cland.alice.memory.dreaming` package within `alice-memory-vault`.

---

## Entity: `DreamingTriggerConfig`

An immutable record holding configuration parameters for the Dreaming Engine's
trigger mechanisms.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `idleCooldownMs` | `long` | `60000` | Milliseconds of system idle before automatic Dreaming starts |
| `walThresholdEntries` | `int` | `500` | Max unprocessed WAL entries before forced Dreaming |
| `walThresholdBytes` | `long` | `10_485_760` | Max unprocessed WAL bytes (10 MB) before forced Dreaming |
| `pollingIntervalMs` | `long` | `30_000` | Polling interval for idle/checkpoint triggers |
| `maxConcurrency` | `int` | `1` | Max simultaneous Dreaming cycles across different sessions |
| `maxStepsPerCycle` | `int` | `1000` | Max WAL entries processed per Dreaming cycle (safety limit) |

**Validation Rules**:
- `idleCooldownMs` ≥ 1000
- `walThresholdEntries` ≥ 10
- `pollingIntervalMs` ≥ 1000
- `maxConcurrency` ≥ 1
- `maxStepsPerCycle` ≥ 10

**Java Type**: `public record DreamingTriggerConfig(...)` (sealed, immutable)

---

## Entity: `DreamingSession`

An immutable record tracking a single Dreaming cycle execution.

| Field | Type | Description |
|-------|------|-------------|
| `sessionId` | `String` | The WalSession that was processed |
| `startTime` | `long` | Epoch millis when processing started |
| `endTime` | `Long` | Epoch millis when processing ended (null if in progress/failed) |
| `durationMs` | `Long` | Wall-clock duration (null if in progress/failed) |
| `episodicSummaryId` | `String` | ID of the EpisodicSummary written to EpisodicVault (null if skipped) |
| `conflictCount` | `int` | Number of conflicts detected during resolution |
| `patternsCrystallized` | `int` | Number of SOP patterns crystallized |
| `outcome` | `DreamingOutcome` | SUCCESS, FAILURE, or SKIPPED |

**Embedded Enum**:
```java
public enum DreamingOutcome {
    /** Pipeline completed successfully */
    SUCCESS,
    /** Pipeline failed with an error */
    FAILURE,
    /** Session was skipped (already DREAMING or ARCHIVED) */
    SKIPPED
}
```

---

## Entity: `EpisodicSummary`

A structured summary of a single WalSession produced by the offline PromptMelter.

| Field | Type | Description |
|-------|------|-------------|
| `summaryId` | `String` | Unique ID (generated from sessionId + timestamp) |
| `sessionId` | `String` | Source WalSession |
| `stepCount` | `int` | Number of steps summarized |
| `keyActions` | `List<String>` | Key actions taken (tool calls, decisions) |
| `outcome` | `String` | Session outcome description |
| `summaryText` | `String` | Human-readable condensed summary |
| `createdAt` | `long` | Epoch millis |

**Stored In**: EpisodicVault (as a Step-like entry with action="dreaming_summary")

---

## Entity: `DreamingFact`

A single extracted factual statement from WAL logs, used as intermediate data
between PromptMelter output and SemanticVault/ConflictResolver input.

| Field | Type | Description |
|-------|------|-------------|
| `factId` | `String` | Unique ID |
| `content` | `String` | The factual statement |
| `sourceSessionId` | `String` | Source WalSession |
| `sourceMessageId` | `long` | Source WAL message ID (for ordering) |
| `timestamp` | `long` | When the fact was observed |
| `confidence` | `double` | 0.0–1.0 (heuristic: extracted from system messages = 0.9, from assistant = 0.7, from user = 0.5) |

---

## Entity: `CrystallizedPattern`

A detected tool-call sequence pattern ready for conversion into a SOP entry.

| Field | Type | Description |
|-------|------|-------------|
| `patternId` | `String` | Unique ID |
| `toolSequence` | `List<String>` | Ordered list of tool names (e.g., ["list_files", "read_file", "write_file"]) |
| `occurrenceCount` | `int` | How many times this sequence appeared |
| `sourceSessionId` | `String` | Session where pattern was detected |
| `firstSeen` | `long` | Epoch millis of first occurrence |
| `successRate` | `double` | Percentage of successful executions in this sequence |

---

## State Machine: `SessionState` (WalStore Extension)

Added as a `ConcurrentMap<String, SessionState>` within WalStore (not breaking
the interface):

```text
        [ CREATED ]
            |
       start_session()
            v
        [ RUNNING ]
   ┌─────────┴──────────┐
   │                     │
 task_complete()    crash/error
   │                     │
   v                     v
[ COMPLETED ]      [ CRASHED ]
   │                     │
   └──────┬──────────────┘
          │ DreamingEngine.process() locks session
          v
      [ DREAMING ]
          │
   ┌──────┴──────┐
   │             │
  success      failure/error
   │             │
   v             v
[ ARCHIVED ]  [ COMPLETED or CRASHED ]
                   (reverted)
```

**Legal Transitions**:
- `CREATED → RUNNING`
- `RUNNING → COMPLETED` (normal end)
- `RUNNING → CRASHED` (error/crash)
- `COMPLETED → DREAMING`
- `CRASHED → DREAMING`
- `DREAMING → ARCHIVED` (successful dream)
- `DREAMING → COMPLETED` (failed dream, revert to completed)
- `DREAMING → CRASHED` (failed dream, revert to crashed)
- `COMPLETED → ARCHIVED` (replay detection — skip re-dreaming)

**Invalid Transitions** (throw `IllegalStateException`):
- `RUNNING → DREAMING` (cannot dream an active session)
- `DREAMING → RUNNING` (cannot restart while dreaming)
- `ARCHIVED → DREAMING` (archived sessions are read-only)

---

## Entity: `StateTransitionException`

A runtime exception thrown when an invalid session state transition is attempted.

| Field | Type | Description |
|-------|------|-------------|
| `sessionId` | `String` | The session involved |
| `from` | `SessionState` | Current state |
| `to` | `SessionState` | Attempted target state |

---

## Entity Relationships

```text
WalStore (existing)
  ├── RawMessage (existing) — append-only log entries
  ├── Checkpoint (existing) — state snapshots
  └── SessionState (new) — CREATED | RUNNING | COMPLETED | CRASHED | DREAMING | ARCHIVED
        │
        └── DreamingEngine (new) — orchestrator
              ├── consumes WalStore RawMessages
              ├── produces DreamingSession (run record)
              ├── uses PromptMelter (offline) → EpisodicSummary → written to EpisodicVault
              ├── uses ConflictResolver → DreamingFact → Knowledge → written to SemanticVault
              └── uses Crystallizer → CrystallizedPattern → SOP → written to ProceduralVault
```

## Key Design Decisions (from research.md)

| Decision | Value |
|----------|-------|
| Package | `org.cland.alice.memory.dreaming` in alice-memory-vault |
| Session state map | ConcurrentMap in InMemoryWalStore, not in interface |
| Conflict heuristic | Timestamp-based: newer wins, equal → MANUAL_REVIEW |
| Crystallization threshold | 3+ identical tool sequences in same session |
| LLM usage | Deterministic v1, LLM enhancement deferred |
| Trigger mechanism | Polling via ScheduledExecutorService |
