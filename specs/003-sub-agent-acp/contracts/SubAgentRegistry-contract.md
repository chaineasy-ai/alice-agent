# Contract: SubAgentRegistry — Sub-Agent Lifecycle Manager

## Overview

The `SubAgentRegistry` is a parent-session-scoped registry that manages the lifecycle of all sub-agents (spawned Alice agents and connected ACP agents).

## Interface

```java
public interface SubAgentRegistry {
    SubAgentRecord register(SubAgentRecord record);
    Optional<SubAgentRecord> get(String id);
    List<SubAgentRecord> list();
    boolean updateStatus(String id, SubAgentStatus status);
    boolean updateResult(String id, String resultSummary);
    boolean remove(String id);
    int countByStatus(SubAgentStatus status);
    int activeCount();
}
```

## Lifecycle Rules

1. Registration validates: max concurrent check (≤ 5 by default), required fields per type
2. Status transitions follow the state machine in data-model.md
3. Terminal states (COMPLETED, FAILED, CANCELED) are immutable — further updates are no-ops
4. Removal is only allowed from terminal states to prevent orphaned processes
5. The registry must be thread-safe (ConcurrentHashMap-backed)

## Concurrency

- Reads (`get`, `list`, `countByStatus`, `activeCount`) are lock-free
- Writes (`register`, `updateStatus`, `updateResult`, `remove`) use `computeIfPresent` for atomicity
- `activeCount()` must reflect real-time state for enforcement of the max limit
