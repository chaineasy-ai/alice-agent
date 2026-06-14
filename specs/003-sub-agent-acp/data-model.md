# Data Model: /sub-agent — Multi-Agent via ACP Protocol

## Entities

### SubAgentRecord

Represents a managed sub-agent (spawned Alice agent or connected ACP agent) in the parent session's registry.

| Field | Type | Description | Validation |
|-------|------|-------------|------------|
| `id` | `String` (UUID) | Unique sub-agent identifier | Auto-generated on register; immutable |
| `type` | `SubAgentType` | Enum: `ALICE` (spawned) or `ACP` (connected external) | Required |
| `status` | `SubAgentStatus` | Enum: `RUNNING`, `COMPLETED`, `FAILED`, `CANCELED`, `CONNECTED` | Transitions: RUNNING→COMPLETED/FAILED/CANCELED; CONNECTED→RUNNING/FAILED |
| `goal` | `String` | Description/purpose of the sub-agent | Required; max 500 chars |
| `sessionId` | `String` (optional) | WAL session ID for spawned Alice sub-agents | Present only if type=ALICE; must be a valid UUID |
| `endpoint` | `String` (optional) | ACP endpoint URL for connected external agents | Present only if type=ACP; must be a valid URL (http/https) |
| `createdAt` | `long` | Timestamp (epoch millis) of creation | Auto-set on register; immutable |
| `completedAt` | `Long` (nullable) | Timestamp (epoch millis) of completion/failure/cancellation | null while running; set on terminal status transition |
| `resultSummary` | `String` (nullable) | Result summary from completed sub-agent | null while running; max 2000 chars |

**State transitions:**

```text
  ┌──────────┐
  │ CREATING │  (transient — not exposed in registry)
  └────┬─────┘
       │
       ▼
  ┌──────────┐     ┌───────────┐
  │ RUNNING  │────▶│ COMPLETED │  (ALICE type only — goal achieved)
  │ (ALICE)  │     └───────────┘
  └────┬─────┘
       │
       ▼
  ┌───────────┐
  │ CONNECTED │────▶│ RUNNING  │  (ACP type — when actively prompting)
  │  (ACP)    │     └──────────┘
  └────┬─────┘
       │
       ├──▶ FAILED    (any type — error/exception)
       └──▶ CANCELED  (any type — user or parent cancellation)
```

### SubAgentType

```java
public enum SubAgentType {
    ALICE,  // Spawned in-process Alice Agent
    ACP     // Connected external ACP-compliant agent
}
```

### SubAgentStatus

```java
public enum SubAgentStatus {
    RUNNING,    // Actively executing (ALICE) or available for prompts (ACP)
    COMPLETED,  // Goal achieved (ALICE only)
    FAILED,     // Terminated due to error
    CANCELED,   // User-initiated cancellation
    CONNECTED   // Registered and connected but idle (ACP only)
}
```

### SubAgentRegistry

Parent-session-scoped, thread-safe registry using `ConcurrentHashMap<String, SubAgentRecord>`.

| Method | Signature | Description |
|--------|-----------|-------------|
| `register` | `(SubAgentRecord) → SubAgentRecord` | Adds a new sub-agent; auto-generates UUID if id is null |
| `get` | `(String id) → Optional<SubAgentRecord>` | Lookup by sub-agent ID |
| `list` | `() → List<SubAgentRecord>` | Returns all sub-agents (active + completed) |
| `updateStatus` | `(String id, SubAgentStatus) → boolean` | Updates status; returns false if sub-agent not found |
| `updateResult` | `(String id, String resultSummary) → boolean` | Sets result summary; typically paired with COMPLETED status |
| `remove` | `(String id) → boolean` | Removes from registry (cleanup after cancel) |
| `countByStatus` | `(SubAgentStatus) → int` | Count of sub-agents with given status |
| `activeCount` | `() → int` | Count of RUNNING + CONNECTED sub-agents |

**Concurrency**: All mutations are synchronized via the ConcurrentHashMap; individual record updates use `computeIfPresent` for atomicity.

### SubAgentResult

Payload returned when a spawned sub-agent completes.

| Field | Type | Description |
|-------|------|-------------|
| `subAgentId` | `String` | Unique sub-agent identifier |
| `status` | `SubAgentStatus` | Terminal status (COMPLETED/FAILED/CANCELED) |
| `summary` | `String` | Human-readable result summary |
| `messageCount` | `int` | Number of messages exchanged during execution |
| `durationMs` | `long` | Execution duration in milliseconds |

## Relationships

```text
ParentSession (1) ──has──▶ SubAgentRegistry (1)
                                │
                                ├── (0..*) SubAgentRecord [type=ALICE]
                                │         └── WalSession (1)   [isolated session ID]
                                │
                                └── (0..*) SubAgentRecord [type=ACP]
                                          └── AcpConnection (1) [endpoint URL]
```

## Validation Rules

1. `id` must be a valid UUID when provided (auto-generated if null)
2. `endpoint` must be present and valid URL if type=ACP
3. `sessionId` must be present and valid if type=ALICE
4. Concurrent sub-agents must not exceed configured max (default: 5)
5. Status transitions must follow the defined state machine
6. `goal` must not be blank; max 500 characters
7. `resultSummary` must not exceed 2000 characters
