---
title: "alice-memory-vault — Inbound Integration"
summary: "Aggregation root and inbound integration points for alice-memory-vault module. Describes VaultController as the facade, module boundary interfaces, and consumer packages (core-agent, bootstrap)."
read_when:
  - "understanding how alice-memory-vault is consumed by other modules"
  - "adding new consumers of memory vault API"
  - "reviewing module boundary and exported packages"
scope:
  - "alice-memory-vault"
  - "alice-core-agent"
status: "active"
updated: "2026-06-20"
---

# alice-memory-vault — Inbound Integration

## 1. Aggregation Root: `VaultController`

`VaultController` is the **single facade** and **aggregation root** for the memory vault module.
It composes three vault types (episodic, semantic, procedural) behind a unified API:

```
Consumer (Agent) ──► VaultController
                        ├── EpisodicVault   (short-term trace)
                        ├── SemanticVault   (long-term knowledge)
                        ├── ProceduralVault (SOP / best practices)
                        ├── MemoryRouter    (routing logic)
                        └── StorageBackend  (persistence)
```

**Exported packages** (from `module-info.java`):

| Package | Contents | Exported |
|---------|----------|----------|
| `org.cland.alice.memory.agent` | `AgentSession`, `Context` | ✅ yes |
| `org.cland.alice.memory.core` | `Experience`, `Knowledge`, `MemorySet`, `Step`, `SOP`, `Summary` | ✅ yes |
| `org.cland.alice.memory.vault` | `EpisodicVault`, `SemanticVault`, `ProceduralVault` + impls | ✅ yes |
| `org.cland.alice.memory.storage` | `StorageBackend`, `InMemoryStorageBackend` | ✅ yes |
| `org.cland.alice.memory.router` | `MemoryRouter`, `MemorySummarizer` | ✅ yes |
| `org.cland.alice.memory.controller` | `VaultController` | ✅ yes |
| `org.cland.alice.memory.wal` | `WalStore`, `FileWalStore`, `WalSession`, `RawMessage` | ✅ yes |

## 2. Consumer Map

```
alice-bootstrap (AliceApp)
  └── creates AgentSession (via VaultController)

alice-core-agent (Agent, AgentExecutor)
  ├── Agent            → AgentSession (memorize/recall via VaultController)
  ├── AgentExecutor    → WalSession (WAL append for execution replay)
  └── planner          → uses Context model for memory routing
```

### 2.1 `Agent` (core-agent)

`Agent.java` is the **primary consumer**. It holds an `AgentSession` reference:

```java
public class Agent {
    private AgentSession memory;

    // Called during perceive phase:
    public String getShortTermContext(String sessionId) { ... }

    // Called during observe phase:
    public void memorize(AgentCommand command, ExecutionResult result) { ... }
}
```

`AgentSession` wraps `VaultController` — it internally calls `controller.recall(ctx)` for retrieval
and `controller.memorize(exp)` for storage.

### 2.2 `AgentExecutor` (core-agent)

`AgentExecutor` uses `WalSession` directly for WAL-based execution tracking and replay:

```java
public class AgentExecutor {
    private final WalSession walSession;

    // Writes each ReAct step to WAL:
    public void recordStep(String sessionId, Action action, Observation obs) { ... }
}
```

## 3. Public API Summary

### VaultController (aggregate root)

| Method | Description |
|--------|-------------|
| `recall(Context) → MemorySet` | Retrieve relevant memories by query context |
| `memorize(Experience)` | Ingest a single interaction into episodic vault |
| `finalizeSession(sessionId) → CompletableFuture<Summary>` | Trigger consolidation (async) |

### Context (query model)

| Method | Description |
|--------|-------------|
| `query()` | The user query / goal |
| `sessionId()` | Current session identifier |
| `metadata(key)` | Extra query metadata |

### AgentSession (consumer adapter)

Provides `getShortTerm()`, `putLongTerm()`, `clearSession()` for Agent's convenience.

### WalSession (execution log)

Appends each ReAct step to WAL for crash recovery and session replay.

## 4. Module Dependency Direction

```
alice-core-agent ──requires──► alice-memory-vault (ONE-WAY)
```

The memory vault module **does not** depend on any alice module — it is a pure domain module
with no reverse dependencies.
