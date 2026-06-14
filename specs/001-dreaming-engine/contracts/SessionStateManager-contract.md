---

title: "SessionStateManager Contract"
summary: "Contract for the session state tracking added to WalStore"
read_when:
  - "implementing or consuming session state transitions"
scope:
  - "alice-memory-vault"
status: "active"
updated: "2026-06-15"
---

# SessionStateManager Contract

## Package
`org.cland.alice.memory.dreaming` (with a backing map in InMemoryWalStore)

## Related Contracts
- [DreamingEngine](./DreamingEngine-contract.md)

## SessionStateManager API

```java
/**
 * Manages WalSession lifecycle states for Dreaming Engine deduplication.
 * Uses ConcurrentMap<String, SessionState> in the backing WalStore
 * (NOT modifying the WalStore interface to maintain backward compatibility).
 */
public final class SessionStateManager {
    public SessionStateManager(WalStore walStore);

    /**
     * Get current state for a session. Default: CREATED.
     */
    public SessionState getState(String sessionId);

    /**
     * Try to transition to a new state. Thread-safe via CAS.
     *
     * @return true if transition was valid and applied
     * @throws StateTransitionException on invalid transition
     */
    public boolean transition(String sessionId, SessionState from, SessionState to);

    /**
     * Check if a session can be dreamed (COMPLETED or CRASHED).
     */
    public boolean isDreamable(String sessionId);

    /**
     * Mark session as DREAMING (atomically, only if COMPLETED or CRASHED).
     * Returns false if another thread already transitioned it.
     */
    public boolean tryLockForDreaming(String sessionId);
}

/**
 * Session lifecycle states for WalSession.
 */
public enum SessionState {
    CREATED,
    RUNNING,
    COMPLETED,
    CRASHED,
    DREAMING,
    ARCHIVED
}

/**
 * Thrown on invalid state transitions.
 */
public class StateTransitionException extends RuntimeException {
    public StateTransitionException(String sessionId, SessionState from, SessionState to);
    // + standard constructors
}
```

## Behavioral Contract

1. State map is stored in WalStore's internal data (not in the interface)
2. `transition()` validates the legal transition table from data-model.md
3. `tryLockForDreaming()` is the atomic CAS entry point:
   - Reads current state
   - If COMPLETED or CRASHED → CAS to DREAMING → return true
   - Otherwise → return false
4. On Dreaming success: `transition(sessionId, DREAMING, ARCHIVED)`
5. On Dreaming failure: `transition(sessionId, DREAMING, COMPLETED)` or
   back to CRASHED (based on original state)
