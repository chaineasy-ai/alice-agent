# Feature Specification: /sub-agent — Multi-Agent via ACP Protocol

**Feature Directory**: `specs/003-sub-agent-acp`

**Created**: 2026-06-14

**Status**: Draft

**Input**: User description: "add /sub-agent command to support alice agent spawn sub-agents, or connect third-party ACP-compliant agents"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Alice Agent spawns a sub-agent (P1)

Alice Agent receives a complex task involving multiple domains. Instead of handling everything in one session, it spawns a dedicated sub-agent via `/sub-agent spawn --goal "analyze database schema"` which runs independently, produces results, and reports back to the parent session.

**Why this priority**: Sub-agent spawning is the core capability — without it, the feature has no MVP value. This is the primary use case.

**Independent Test**: Can be fully tested by spawning a sub-agent with a simple goal, waiting for completion, and verifying the parent session receives the sub-agent's output summary.

**Acceptance Scenarios**:

1. **Given** Alice Agent is running with a parent session active, **When** `/sub-agent spawn --goal "list files in /tmp" --type alice` is executed, **Then** a new sub-agent session is created and starts executing independently
2. **Given** a sub-agent is spawned with a specific goal, **When** the sub-agent completes its task, **Then** the parent session receives a completion notification with a result summary
3. **Given** a sub-agent is executing a long-running task, **When** the parent session runs `/sub-agent list`, **Then** the running sub-agent is listed with its status and current activity

---

### User Story 2 - Connect to third-party ACP agent (P1)

Alice Agent needs specialized data analysis capabilities not available in its own toolset. It connects to an external ACP-compliant agent (e.g., a code analysis agent via its ACP endpoint) using `/sub-agent connect --name "code-analyzer" --acp-endpoint http://localhost:8081/acp`.

**Why this priority**: Third-party agent integration is equally critical — it allows Alice to leverage the ACP ecosystem of specialized agents, providing significantly more value than sub-agents alone.

**Independent Test**: Can be tested by running a simple ACP-compliant agent on localhost, connecting via the command, and verifying a prompt/response exchange.

**Acceptance Scenarios**:

1. **Given** a third-party ACP agent is running at a known endpoint, **When** `/sub-agent connect --name "analyzer" --acp-endpoint http://localhost:9000/acp` is executed, **Then** the external agent is registered in the parent session's sub-agent registry
2. **Given** an external ACP agent is connected, **When** the parent sends a prompt via `/sub-agent prompt "analyzer" "analyze this Python code"`, **Then** the external agent returns a response that is displayed in the parent session

---

### User Story 3 - Monitor and manage sub-agents (P2)

During a complex workflow with multiple sub-agents running, the user wants to see the status of all active sub-agents, cancel a stuck one, or review results from completed ones.

**Why this priority**: Management commands provide usability and control, but the feature still delivers value without them.

**Independent Test**: Can be tested by spawning two sub-agents, listing them, canceling one, and verifying the cancellation and remaining active agent.

**Acceptance Scenarios**:

1. **Given** multiple sub-agents are running and/or completed, **When** `/sub-agent list` is executed, **Then** all sub-agents are displayed with their status (running/completed/failed/connected), goal, and duration
2. **Given** a running sub-agent is stuck or no longer needed, **When** `/sub-agent cancel <id>` is executed, **Then** the sub-agent's session is terminated and its status changes to "canceled"
3. **Given** a sub-agent has completed, **When** `/sub-agent results <id>` is executed, **Then** the sub-agent's final output summary is displayed

---

### User Story 4 - Communication between parent and sub-agents (P2)

The parent agent and sub-agents need to exchange structured messages — not just final results. This enables collaborative task execution where the parent delegates sub-tasks and receives intermediate progress updates.

**Why this priority**: Basic spawn-and-collect works without this, but rich multi-agent collaboration requires parent-child communication.

**Independent Test**: Can be tested by spawning a sub-agent that sends a progress update, then the parent queries the sub-agent for its current state.

**Acceptance Scenarios**:

1. **Given** a sub-agent is running, **When** the parent sends a structured message via `/sub-agent send <id> <structured_message>`, **Then** the sub-agent receives and processes the message within its context
2. **Given** a sub-agent has incremental results, **When** the parent queries via `/sub-agent status <id>`, **Then** it receives the sub-agent's latest intermediate state and any partial results

### Edge Cases

- What happens when a third-party ACP endpoint is unreachable?
- How does the system handle sub-agent spawn failures (resource exhaustion, invalid goal)?
- What happens when the parent session crashes with active sub-agents?
- How are sub-agent results handled if the parent session terminates before the sub-agent completes?
- What is the maximum number of concurrent sub-agents allowed?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST support a `/sub-agent` command with sub-commands for spawn, connect, list, cancel, results, send, and prompt
- **FR-002**: `sub-agent spawn` MUST create a new Alice Agent session with an independent ReAct loop, WAL, and memory context
- **FR-003**: `sub-agent connect` MUST register an external ACP-compliant agent by its endpoint URL, enabling prompt/response exchange over the ACP protocol
- **FR-004**: All sub-agents (spawned and connected) MUST be registered in a sub-agent registry associated with the parent session
- **FR-005**: Each sub-agent MUST have a unique ID, type (alice/acp), status (running/completed/failed/canceled/connected), goal/description, creation timestamp, and optional result summary
- **FR-006**: Spawned Alice sub-agents MUST use isolated WAL storage (separate session ID) so their execution trace does not pollute the parent's context
- **FR-007**: Sub-agent cancellation MUST terminate the spawned agent's ReAct loop and close its session cleanly
- **FR-008**: Parent session MUST be able to query sub-agent status and retrieve completed results
- **FR-009**: Connected third-party ACP agents MUST use the ACP protocol's existing message format for communication — no custom protocol translation is required
- **FR-010**: System MUST enforce a configurable maximum of **5** concurrent sub-agents by default, adjustable via configuration to prevent resource exhaustion
- **FR-011**: System MUST log all sub-agent lifecycle events (spawn, connect, complete, fail, cancel) in the parent session's audit log
- **FR-012**: The `/sub-agent` command MUST extend the sealed AgentCommand hierarchy as a **new 6th sealed branch `SubAgentCmd`**, with sub-types for each sub-command (spawn, connect, list, cancel, results, send, prompt)

### Key Entities *(include if feature involves data)*

- **SubAgentRecord**: Represents a managed sub-agent in the registry — id, type (alice/acp), status, goal, sessionId (for spawned), endpoint (for acp-connected), created_at, completed_at, result_summary
- **SubAgentRegistry**: Parent-session-scoped registry holding all active/completed sub-agents; supports CRUD and lifecycle tracking
- **SubAgentCmd**: New sealed command branch (or sub-type of ExecutionCmd) for `/sub-agent` and its sub-commands (SpawnSubAgentCmd, ConnectSubAgentCmd, ListSubAgentsCmd, CancelSubAgentCmd, GetSubAgentResultsCmd, SendToSubAgentCmd, PromptSubAgentCmd)
- **AcpClient**: An ACP client wrapper that connects to a third-party ACP agent endpoint and handles prompt/response over the ACP protocol

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can spawn an Alice sub-agent and receive its completion result within the same parent session in under 30 seconds for a simple goal (e.g., "list files in /tmp")
- **SC-002**: Users can connect to an external ACP agent and exchange at least 3 prompt-response rounds in under 10 seconds per round
- **SC-003**: The sub-agent list command displays all active/completed agents within 1 second
- **SC-004**: Canceling a running sub-agent completes in under 2 seconds, and the sub-agent process is confirmed terminated
- **SC-005**: Concurrent sub-agents can execute independently without cross-contamination of WAL state or memory context

## Assumptions

- ACP Java SDK is already available in the project (in `docs/acp/README.md`) — the ACP client module can be built upon this SDK
- The parent session has sufficient resources to manage sub-agent lifecycle without significant performance degradation
- Spawned sub-agents share the same Java process (in-process) — no separate JVM or process spawning required
- Third-party ACP agents are assumed to be available and responsive — timeouts and retries are the responsibility of the ACP client implementation
- Existing AgentExecutor, WalSession, and memory-vault infrastructure is reused for spawned sub-agents
- The sealed command hierarchy can be extended to include a new SubAgentCmd branch without breaking existing interface contracts
- Default max concurrent sub-agents: 5 (adjustable via configuration)
- Sub-agent results are stored in the parent's WAL as structured messages; raw sub-agent execution logs remain in the sub-agent's isolated WAL
