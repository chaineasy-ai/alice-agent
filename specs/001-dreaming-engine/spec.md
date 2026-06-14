# Feature Specification: Offline Dreaming Engine

**Feature Branch**: `001-dreaming-engine`

**Created**: 2026-06-15

**Status**: active

**Input**: User description: "Implement the memory dreaming/simulation mechanism for the alice-memory-vault module, an offline processing pipeline that converts raw WAL logs into refined episodic summaries, semantic knowledge, and crystallized SOPs."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Automatic Memory Evolution After Sessions (Priority: P1)

As the Alice Agent system, I want the Dreaming Engine to automatically process completed (or crashed) WalSessions during idle periods, extracting meaningful episodic summaries, semantic facts, and procedural SOPs from raw WAL logs — so that the agent's memory grows richer over time without manual intervention and the online ReAct loop stays lightweight.

**Why this priority**: This is the core value proposition of the Dreaming mechanism — autonomous memory evolution. Without it, there is no feature.

**Independent Test**: Can be fully tested by: 1) writing known WAL entries, 2) triggering a Dreaming cycle, 3) asserting that the correct episodic summary, semantic facts, and crystallized SOPs appear in the respective vaults.

**Acceptance Scenarios**:

1. **Given** a completed WalSession with 5+ ReAct cycles of raw WAL entries, **When** the Dreaming Engine processes it, **Then** the EpisodicVault contains a concise structured summary of the session's key steps.
2. **Given** a WalSession containing repeated successful tool-call patterns (e.g., same 3-step sequence executed correctly 3+ times), **When** Dreaming completes, **Then** the ProceduralVault contains a new SOP entry for that pattern.
3. **Given** a WalSession with factual statements and their corrections (e.g., "API key is X" → later corrected to "API key is Y"), **When** Dreaming completes, **Then** the SemanticVault reflects only the latest correct fact.

---

### User Story 2 - Conflict Resolution and Knowledge Pruning (Priority: P2)

As the Alice Agent system, I want the Dreaming Engine to detect and resolve conflicts between newly processed information and existing knowledge in SemanticVault — so that the agent never acts on contradictory or outdated information.

**Why this priority**: Knowledge quality is critical. Without conflict resolution, the agent could hallucinate due to contradictory memories.

**Independent Test**: Can be tested by pre-seeding SemanticVault with stale facts, running a Dreaming cycle with contradicting WAL input, and verifying the vault is updated to reflect the newer information.

**Acceptance Scenarios**:

1. **Given** SemanticVault contains fact "Database connection timeout is 30s" and WAL log contains "Updated timeout to 60s", **When** Dreaming conflict resolution runs, **Then** SemanticVault updates the fact to "Database connection timeout is 60s" and the old entry is marked DEPRECATED.
2. **Given** a WAL entry contradicts no existing SemanticVault knowledge, **When** Dreaming conflict resolution runs, **Then** the new fact is added as ACTIVE with no DEPRECATED side-effects.

---

### User Story 3 - Triggered and Scheduled Dreaming Cycles (Priority: P3)

As an operator, I want the Dreaming Engine to be triggerable in multiple ways — on-demand via API/command, on a configurable schedule, and automatically when accumulated WAL data exceeds a configurable threshold — so that I can control when and how often offline processing occurs.

**Why this priority**: The trigger mechanism is essential for production deployment but is secondary to the core pipeline functionality.

**Independent Test**: Can be tested by configuring each trigger mechanism, asserting that the Dreaming pipeline fires under the expected conditions without duplicating work.

**Acceptance Scenarios**:

1. **Given** a WalSession in COMPLETED state, **When** the system is idle for more than the configured cooldown period, **Then** Dreaming automatically starts processing the session.
2. **Given** accumulated unprocessed WAL data exceeds the configured threshold (e.g., 10MB), **When** the threshold condition is met, **Then** Dreaming starts processing regardless of schedule.
3. **Given** a user invokes the Dreaming command (e.g., `/dream`), **When** the command is received, **Then** Dreaming immediately starts processing pending sessions.

---

### User Story 4 - Session Lifecycle Locking and Deduplication (Priority: P3)

As the Alice Agent system, I want the Dreaming Engine to ensure that each WalSession is processed exactly once — locking the session during processing, preventing concurrent Dreaming runs on the same session, and marking it as ARCHIVED upon completion.

**Why this priority**: Without deduplication and locking, the same logs could be processed multiple times, leading to duplicated memories and wasted resources.

**Independent Test**: Can be tested by initiating two concurrent Dreaming runs on the same session and verifying only one processes it while the other skips.

**Acceptance Scenarios**:

1. **Given** a WalSession in DREAMING state, **When** a second Dreaming cycle attempts to process it, **Then** the second cycle skips that session.
2. **Given** a Dreaming cycle completes successfully on a session, **When** the pipeline finishes, **Then** the WalSession transitions to ARCHIVED state.
3. **Given** a Dreaming cycle fails mid-processing, **When** the error is handled, **Then** the WalSession returns to COMPLETED state (not stuck in DREAMING).

### Edge Cases

- What happens when Dreaming processes a session that was being actively written to?
- How does the system handle a WalSession with zero log entries?
- What if ConflictResolver encounters two equally recent contradicting facts with no clear resolution?
- How are very long sessions (>1000 steps) handled — is there a step limit per Dreaming cycle?
- What happens if the LLM call (for PromptMelter or ConflictResolver) times out or returns an error?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: DreamingEngine MUST consume raw logs from a COMPLETED or CRASHED WalSession and produce a structured episodic summary written to EpisodicVault.
- **FR-002**: DreamingEngine MUST extract factual knowledge from WAL logs and write it to SemanticVault, with deduplication and conflict detection against existing knowledge.
- **FR-003**: DreamingEngine MUST identify repeated successful tool-call sequences (3+ occurrences of an identical sequence) and crystallize them as SOP entries in ProceduralVault.
- **FR-004**: A ConflictResolver component MUST compare incoming facts against existing SemanticVault entries, mark contradicting old entries as DEPRECATED, and promote the newer fact to ACTIVE.
- **FR-005**: A Crystallizer component MUST analyze multi-step patterns across sessions, extracting stable execution chains as versioned SOP records.
- **FR-006**: A PromptMelter component MUST compress and denoise raw WAL log streams into concise structured summaries, removing redundancy and normalizing timestamps.
- **FR-007**: DreamingEngine MUST lock a WalSession (set to DREAMING state) while processing it to prevent concurrent processing.
- **FR-008**: Upon successful completion, DreamingEngine MUST transition the WalSession state to ARCHIVED and advance the WAL checkpoint.
- **FR-009**: Upon failure or timeout, DreamingEngine MUST return the WalSession to its previous state (COMPLETED or CRASHED), preserving error details.
- **FR-010**: DreamingEngine MUST support at least three trigger modes: on-demand command, timed schedule, and WAL data threshold.
- **FR-011**: DreamingEngine MUST log all processing steps, conflicts found, patterns crystallized, and errors via SLF4J.
- **FR-012**: The system MUST respect WalSession READ lock during Dreaming — online ReAct execution MUST NOT read from a session in DREAMING state.

### Key Entities *(include if feature involves data)*

- **DreamingEngine**: The orchestrator that manages the full offline pipeline. Accepts trigger events, selects pending WalSessions, invokes PromptMelter → ConflictResolver → Crystallizer in sequence, and manages session state transitions.
- **PromptMelter**: Component that consumes raw WAL log streams, applies noise reduction, temporal normalization, and text compression, outputting structured episodic summaries for EpisodicVault.
- **ConflictResolver**: Component that compares new semantic facts against existing SemanticVault entries, performing conflict detection, deprecation marking, and atomic update.
- **Crystallizer**: Component that analyzes multi-session execution patterns, detecting repeated stable tool-call sequences and generating SOP records for ProceduralVault.
- **DreamingTriggerConfig**: Configuration object defining trigger parameters (idle cooldown duration, WAL threshold in bytes/entries, polling interval).
- **DreamingSession**: Run-time record for a single Dreaming cycle, tracking which WalSession was processed, timestamps, processing duration, conflict count, patterns crystallized, and outcome status.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A single Dreaming cycle processing a session of 20 ReAct steps completes in under 60 seconds of wall-clock time.
- **SC-002**: After processing 10 simulated session logs (each with 5-10 steps), at least 8 of 10 sessions produce a correct episodic summary verifiable by human review.
- **SC-003**: When conflict resolution is triggered with 5 deliberately contradictory fact pairs, 100% of conflicts are resolved with the newer fact winning and the old fact marked DEPRECATED.
- **SC-004**: When running 10 sessions with an identical 3-step tool sequence, at least 1 SOP entry is crystallized by the third Dreaming cycle.
- **SC-005**: Concurrent Dreaming attempts on the same WalSession never result in duplicate processing — 100% deduplication rate in 100 simulated concurrent trigger attempts.
- **SC-006**: Online ReAct execution latency shows no measurable degradation during a concurrently running Dreaming cycle (P95 latency increase < 5%).

## Assumptions

- The existing `WalSession`, `WalStore`, `CheckpointManager`, `EpisodicVault`, `SemanticVault`, and `ProceduralVault` components from `alice-memory-vault` are already implemented and stable (WAL + Checkpoint dual-track persistence).
- The Dreaming Engine will be part of the `alice-memory-vault` module, consistent with the existing module boundaries.
- An LLM call may be used for the PromptMelter and ConflictResolver steps (text compression, knowledge comparison), but fallback deterministic behavior must exist if the LLM is unavailable.
- Dreaming is purely asynchronous and non-blocking — it runs in its own thread pool with configurable concurrency (default: 1).
- Memory requirements for Dreaming processing are bounded per-session (configurable max log entries per cycle).
- Session state machine (CREATED → RUNNING → COMPLETED/CRASHED → DREAMING → ARCHIVED) is already implemented in the WalSession lifecycle.
