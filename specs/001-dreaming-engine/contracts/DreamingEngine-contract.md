---

title: "DreamingEngine Interface Contract"
summary: "Public API contract for the DreamingEngine orchestrator"
read_when:
  - "understanding the DreamingEngine public API surface"
  - "implementing or consuming the DreamingEngine"
scope:
  - "alice-memory-vault"
status: "active"
updated: "2026-06-15"
---

# DreamingEngine Interface Contract

## Package
`org.cland.alice.memory.dreaming`

## Related Contracts
- [ConflictResolver](./ConflictResolver-contract.md)
- [Crystallizer](./Crystallizer-contract.md)
- [SessionStateManager](./SessionStateManager-contract.md)

## DreamingEngine API

```java
public final class DreamingEngine {
    // ============================================================
    // Constructor
    // ============================================================

    /**
     * @param walStore          WAL storage for reading session logs
     * @param episodicVault     Target for episodic summaries
     * @param semanticVault     Target for semantic knowledge
     * @param proceduralVault   Target for crystallized SOPs
     * @param triggerConfig     Configuration for triggers and limits
     */
    public DreamingEngine(
        WalStore walStore,
        EpisodicVault episodicVault,
        SemanticVault semanticVault,
        ProceduralVault proceduralVault,
        DreamingTriggerConfig triggerConfig
    );

    // ============================================================
    // Core Processing
    // ============================================================

    /**
     * Process a single WalSession through the full pipeline.
     * Locks the session to DREAMING state, runs PromptMelter →
     * ConflictResolver → Crystallizer, then transitions to ARCHIVED.
     *
     * @param sessionId  The session to process
     * @return DreamingSession record with outcome statistics
     * @throws IllegalArgumentException if sessionId is null/empty
     * @throws StateTransitionException on invalid state transition
     */
    public DreamingSession process(String sessionId);

    /**
     * Process all pending COMPLETED/CRASHED sessions.
     * Skips sessions that are already DREAMING or ARCHIVED.
     *
     * @return List of DreamingSession records
     */
    public List<DreamingSession> processAll();

    // ============================================================
    // Trigger Mechanisms
    // ============================================================

    /**
     * Start the background polling timer for idle and WAL-threshold
     * triggers. Uses triggerConfig.pollingIntervalMs.
     */
    public void startBackgroundTriggers();

    /**
     * Stop background polling. In-flight Dreaming cycles complete.
     */
    public void stopBackgroundTriggers();

    /**
     * Check if background triggers are active.
     */
    public boolean isBackgroundRunning();

    /**
     * Get current pending session count (COMPLETED + CRASHED).
     */
    public int pendingSessionCount();

    // ============================================================
    // Configuration
    // ============================================================

    /**
     * Get the current trigger configuration (immutable copy).
     */
    public DreamingTriggerConfig getTriggerConfig();

    // ============================================================
    // Status
    // ============================================================

    /**
     * Get DreamingSession records for recent processing runs
     * (newest first, up to limit).
     */
    public List<DreamingSession> recentSessions(int limit);
}
```

## Behavioral Contract

### Normal Flow
1. `process(sessionId)` checks session state
2. If state is DREAMING or ARCHIVED → return SKIPPED DreamingSession
3. If state is COMPLETED or CRASHED → atomically transition to DREAMING
4. Read WAL logs from WalStore
5. Run PromptMelter → write EpisodicSummary to EpisodicVault
6. Run ConflictResolver → write Knowledge to SemanticVault
7. Run Crystallizer → write SOP to ProceduralVault
8. Transition state to ARCHIVED
9. Return DreamingSession with outcome=SUCCESS

### Error Flow
- If any pipeline stage throws, catch exception, log error, revert session state
- Return DreamingSession with outcome=FAILURE and error details

### Thread Safety
- `process(sessionId)` is synchronized per-session via CAS on session state
- Different sessions can be processed concurrently (up to maxConcurrency)
- `processAll()` iterates and dispatches individual `process()` calls
