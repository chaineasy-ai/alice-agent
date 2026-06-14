# Contract: SubAgentCmd — Sealed Command Hierarchy

## Overview

`SubAgentCmd` is a new 6th sealed branch under `AgentCommand`, parallel to `ExecutionCmd`, `CapabilityCmd`, `AlignmentCmd`, `ControlCmd`, and `RoutineTimeCmd`. It defines the `/sub-agent` command and its sub-commands.

## Sealed Interface Hierarchy

```java
// In alice-agent-command module
public sealed interface SubAgentCmd extends AgentCommand
    permits SpawnSubAgentCmd, ConnectSubAgentCmd, ListSubAgentsCmd,
            CancelSubAgentCmd, GetSubAgentResultsCmd, SendToSubAgentCmd,
            PromptSubAgentCmd {
}
```

### Per-subtype Contracts

#### SpawnSubAgentCmd
- **Command**: `/sub-agent spawn`
- **Parameters**: `goal` (String, required), `model` (String, optional — override model)
- **Behavior**: Creates a new in-process Alice Agent with isolated WAL session, runs the given goal asynchronously
- **Response**: Returns sub-agent ID immediately; completion is async via notification

#### ConnectSubAgentCmd
- **Command**: `/sub-agent connect`
- **Parameters**: `name` (String, required), `acpEndpoint` (URL, required)
- **Behavior**: Registers an external ACP-compliant agent; establishes connection via `AcpSyncClient`
- **Response**: Returns sub-agent ID on successful connection

#### ListSubAgentsCmd
- **Command**: `/sub-agent list`
- **Parameters**: (none)
- **Behavior**: Returns all sub-agents in the parent session's registry
- **Response**: Formatted list of SubAgentRecord entries with status, type, goal, duration

#### CancelSubAgentCmd
- **Command**: `/sub-agent cancel`
- **Parameters**: `id` (String, required)
- **Behavior**: Terminates the sub-agent's execution (Alice) or marks disconnected (ACP)
- **Response**: Confirmation of cancellation

#### GetSubAgentResultsCmd
- **Command**: `/sub-agent results`
- **Parameters**: `id` (String, required)
- **Behavior**: Retrieves the completed sub-agent's result summary
- **Response**: SubAgentResult with summary and metadata

#### SendToSubAgentCmd
- **Command**: `/sub-agent send`
- **Parameters**: `id` (String, required), `message` (String, required)
- **Behavior**: Sends a structured message to a running Alice sub-agent
- **Response**: Acknowledgment of delivery

#### PromptSubAgentCmd
- **Command**: `/sub-agent prompt`
- **Parameters**: `id` (String, required), `prompt` (String, required)
- **Behavior**: Sends a prompt to a connected ACP agent via the ACP protocol
- **Response**: Returns the ACP agent's response
