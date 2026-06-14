# Research: /sub-agent — Multi-Agent via ACP Protocol

## Research Tasks

### Task 1: ACP Java SDK Client API

**Decision**: Use `AcpSyncClient` from the ACP Java SDK for connecting to third-party agents.

**Rationale**: The ACP Java SDK (v0.9.0) provides both sync and async clients. The sync client (`AcpSyncClient`) is simpler and appropriate for our use case — Alice Agent needs to send a prompt and wait for a response. The lifecycle is:

1. `client.initialize()` — handshake with the ACP agent
2. `client.newSession(new NewSessionRequest(...))` — create a new session
3. `client.prompt(new PromptRequest(...))` — send prompt and get response
4. `client.close()` — cleanup

**Alternatives considered**:
- Async client (`AcpAsyncClient`): More complex, requires Project Reactor. Not worth the overhead for simple prompt/response patterns.
- Custom HTTP client: Would require implementing the ACP spec from scratch. Unnecessary given the SDK exists.
- Stdio transport (launching subprocess ACP agent): Possible for spawning third-party agents as subprocesses, but not needed for `connect` which targets HTTP/WebSocket endpoints.

### Task 2: Sub-Agent Spawning Architecture (In-Process)

**Decision**: Spawned Alice sub-agents run in-process, sharing the same JVM but with completely isolated state.

**Rationale**: Creating a new `Agent` instance with a fresh `AgentConfig`, independent `WalSession`, and separate ReAct loop is straightforward since the existing `Agent` class already supports parameterized construction. The `SubAgentManager` orchestrator:
1. Creates a new `Agent` (reusing the same `ModelProvider` from parent)
2. Creates a new `WalSession` with a unique session ID
3. Attaches an `AgentExecutor` with WAL
4. Runs the goal in a separate thread/future
5. Returns results asynchronously

**Alternatives considered**:
- Separate process: Adds IPC complexity, serialization overhead, startup latency. Not justified for in-JVM multi-agent.
- Thread pool per parent session: Simpler than separate Agent instances, but violates module-separate design and isolation guarantees.

### Task 3: Sealed Command Hierarchy — SubAgentCmd

**Decision**: Add `SubAgentCmd` as a 6th sealed branch under `AgentCommand`, parallel to `ExecutionCmd`, `CapabilityCmd`, `AlignmentCmd`, `ControlCmd`, and `RoutineTimeCmd`.

**Rationale**: The user explicitly chose this option. Sub-agent operations are semantically distinct from execution (they manage child sessions/lifecycles rather than performing work). The existing `AgentCommand` sealed interface needs a new permitted subclass:

```java
public sealed interface AgentCommand permits
    ExecutionCmd, CapabilityCmd, AlignmentCmd, ControlCmd, RoutineTimeCmd,
    SubAgentCmd { ... }
```

SubAgentCmd then has its own sealed hierarchy:

```java
public sealed interface SubAgentCmd extends AgentCommand permits
    SpawnSubAgentCmd, ConnectSubAgentCmd, ListSubAgentsCmd,
    CancelSubAgentCmd, GetSubAgentResultsCmd, SendToSubAgentCmd,
    PromptSubAgentCmd { ... }
```

**Alternatives considered**: N/A — user decision confirmed.

### Task 4: SubAgentRegistry — Parent-Session-Scoped Registry

**Decision**: Thread-safe registry using `ConcurrentHashMap<String, SubAgentRecord>` keyed by sub-agent ID (UUID), scoped to the parent session.

**Rationale**: The parent session may have multiple concurrent sub-agents (up to 5). `ConcurrentHashMap` provides lock-free reads and fine-grained concurrency. The registry supports:
- `register()` — add a new sub-agent record
- `get(id)` — lookup by ID
- `list()` — return all records (active + completed)
- `updateStatus(id, status)` — lifecycle state transitions
- `remove(id)` — cleanup on cancel/complete

**Alternatives considered**:
- Persistent store: Overkill for runtime state. Sub-agent records are ephemeral (parent-session-scoped).
- CopyOnWriteArrayList: Better for iteration-heavy workloads, but concurrent modifications (status updates) would create excessive copies.

### Task 5: ACP Client as Internal Module

**Decision**: `AcpClient` and `AcpConnection` live in `alice-core-agent` under `org.cland.alice.agent.internal.acp`, not a separate module.

**Rationale**: The ACP client is a thin wrapper (3-4 methods: `initialize`, `prompt`, `getSession`, `close`) that maps to the ACP Java SDK's `AcpSyncClient`. It doesn't warrant its own Gradle module or JPMS module. Keeping it internal to `alice-core-agent` matches the existing pattern (no `internal` package leaks as public API).

**Alternatives considered**:
- New `alice-acp-client` module: Too heavyweight for a thin wrapper. Would need its own build.gradle, module-info.java, and cross-module dependency.
- Inline in SubAgentManager: Mixes concerns. The wrapper provides testability and swapability.
