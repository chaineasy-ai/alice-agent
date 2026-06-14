---

title: "Dreaming Engine Research Notes"
summary: "Phase 0 research findings and design decisions for the offline Dreaming Engine"
read_when:
  - "reviewing design decisions for the dreaming feature"
  - "understanding the offline memory evolution pipeline"
scope:
  - "alice-memory-vault"
status: "active"
updated: "2026-06-15"
---

# Dreaming Engine — Research & Design Decisions

## Context

The Offline Dreaming Engine is a new subsystem within `alice-memory-vault`
that asynchronously processes raw WAL logs from completed agent sessions
through a three-stage pipeline (PromptMelter → ConflictResolver →
Crystallizer), writing refined memories to the three vaults
(EpisodicVault, SemanticVault, ProceduralVault).

All technical context was already resolved from the existing codebase —
no NEEDS CLARIFICATION markers remained after the initial plan creation.

---

## Decisions

### Decision 1: Package Location

| Decision | Detail |
|----------|--------|
| **Choice** | New `dreaming` package within `alice-memory-vault` |
| **Rationale** | All three vault interfaces (EpisodicVault, SemanticVault, ProceduralVault) and WAL infrastructure (WalStore, WalSession) already live in `alice-memory-vault`. Adding the Dreaming engine here avoids circular dependencies and respects existing module boundaries (Constitution Principle I). |
| **Alternatives** | New separate module (`alice-dreaming-engine`) — rejected because it would create a circular dependency between alice-memory-vault and the new module, since Dreaming needs both WAL and Vault interfaces. |

### Decision 2: Pipeline Orchestration Strategy

| Decision | Detail |
|----------|--------|
| **Choice** | Sequential three-stage pipeline (PromptMelter → ConflictResolver → Crystallizer) within a single `DreamingEngine.process()` call |
| **Rationale** | The stages have ordering dependencies: PromptMelter produces summaries that contain facts → ConflictResolver needs those facts to check against SemanticVault → Crystallizer needs the full session context to detect patterns. Sequential execution within one call simplifies locking, deduplication, and error handling. |
| **Alternatives** | Fully parallel stages — rejected because stage outputs are inputs to subsequent stages. Event-driven pipeline with message queue — over-engineered for v1 (will introduce if latency becomes problematic). |

### Decision 3: Conflict Resolution Heuristic

| Decision | Detail |
|----------|--------|
| **Choice** | Timestamp-based conflict resolution: newer fact wins, old fact marked DEPRECATED. If timestamps are equal (within configurable tolerance), mark both as MANUAL_REVIEW. |
| **Rationale** | WAL logs are chronologically ordered, so message ID order equals time order. The agent's latest decision is the most current. Equal-timestamp conflicts (impossible with sequential WAL but possible from concurrent sessions) require human review. |
| **Alternatives** | Semantic similarity comparison — computationally expensive for v1 and error-prone without a vector DB. Always keep both — leads to contradictory knowledge in SemanticVault and agent hallucination. |

### Decision 4: Pattern Crystallization Threshold

| Decision | Detail |
|----------|--------|
| **Choice** | Crystallizer marks a tool-call sequence as a candidate SOP when the identical sequence (same tools in same order with same success status) appears 3+ times within the same session. |
| **Rationale** | Repeated execution implies the pattern is stable and worth preserving. Threshold of 3 avoids noise from single occurrences while being low enough to capture patterns quickly. |
| **Alternatives** | Threshold of 5+ — more conservative, may miss valid patterns. Threshold of 2 — includes too many coincidental repetitions of simple tools (e.g., "list files" called twice). Cross-session matching — deferred to a future enhancement. |

### Decision 5: Session State Management

| Decision | Detail |
|----------|--------|
| **Choice** | Add a `ConcurrentMap<String, SessionState>` to WalStore/InMemoryWalStore with states: CREATED, RUNNING, COMPLETED, CRASHED, DREAMING, ARCHIVED. Implemented as a separate map to not break the existing WalStore interface. |
| **Rationale** | The existing WalStore has no session state concept (no state method exists). Adding an optional concurrent state map is backward-compatible and non-breaking. The existing `WalSession` lifecycle already implies logical states (it manages append/recover/clear), but no explicit state enum exists. |
| **Alternatives** | Add state to WalStore interface — would break all existing implementations. Use a separate `SessionStateManager` — creates another concept when the state-map pattern is simpler and testable. |

### Decision 6: LLM Usage in Pipeline

| Decision | Detail |
|----------|--------|
| **Choice** | PromptMelter and ConflictResolver use deterministic text processing for v1 (regex-based fact extraction, Jaccard similarity for text matching). LLM integration is a future enhancement. |
| **Rationale** | Constitution Principle V requires deterministic behavior for core operations. LLM calls add latency, non-determinism, and cost. The Dreaming spec's Assumptions section explicitly allows fallback deterministic behavior. |
| **Alternatives** | Require LLM for PromptMelter/ConflictResolver — blocks MVP deployment, violates "offline must not depend on online model availability." |

### Decision 7: Trigger Mechanism Architecture

| Decision | Detail |
|----------|--------|
| **Choice** | The `DreamingEngine` exposes three trigger paths: (1) `process(sessionId)` for on-demand, (2) `ScheduledExecutorService`-based polling for idle/timer triggers, (3) WAL data threshold via `messageCount()` + `activeSessionIds()` from WalStore. |
| **Rationale** | Simple, no new dependencies. `ScheduledExecutorService` is part of the JDK. Threshold is checked on each polling tick. On-demand is direct method call. |
| **Alternatives** | Reactive streams or event bus — over-engineered for v1 where the polling interval is measured in seconds/minutes. |

---

## Open Issues (Deferred)

| Issue | Notes |
|-------|-------|
| Cross-session pattern detection | Current Crystallizer only looks within a single session. Cross-session analysis will require its own offline sweep and is deferred to a future release. |
| Vector embeddings for ConflictResolver | Current conflict detection uses text equality/Jaccard similarity. True semantic conflict detection requires embeddings and is deferred. |
| Persistent WalStore | Current InMemoryWalStore is for dev/test. Production deployment will need a PostgreSQL/Redis backend, which would naturally include session state. |
