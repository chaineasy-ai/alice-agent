# Quickstart: /sub-agent — Multi-Agent via ACP Protocol

## Prerequisites

- Java 25+ JDK installed
- Project built: `./gradlew installDist`
- Alice Agent running in TUI or CLI interactive mode

## Validation Scenarios

### Scenario 1: Spawn Alice Sub-Agent

```bash
# From within an active Alice Agent session:
/sub-agent spawn --goal "list files in /tmp"

# Expected:
#   Sub-agent <uuid> spawned with goal "list files in /tmp"
#   [sub-agent runs asynchronously...]
#   Sub-agent <uuid> completed: [file listing results]
```

**Validates**: FR-001, FR-002, FR-006, FR-012

### Scenario 2: List Active Sub-Agents

```bash
# While sub-agents are running:
/sub-agent list

# Expected:
#   Sub-Agent ID     Type   Status    Goal                     Duration
#   a1b2c3d4-e5f6    ALICE  RUNNING   "list files in /tmp"     12s
```

**Validates**: FR-004, FR-005, FR-008, SC-003

### Scenario 3: Cancel a Sub-Agent

```bash
# Cancel a stuck sub-agent:
/sub-agent cancel a1b2c3d4-e5f6

# Expected:
#   Sub-agent a1b2c3d4-e5f6 canceled
```

**Validates**: FR-007, SC-004

### Scenario 4: Connect to External ACP Agent

```bash
# Requires an ACP-compliant agent running at a known endpoint:
/sub-agent connect --name "code-analyzer" --acp-endpoint http://localhost:9000/acp

# Expected:
#   Connected to ACP agent "code-analyzer" at http://localhost:9000/acp
#   Sub-agent <uuid> registered

# Then prompt the external agent:
/sub-agent prompt <uuid> "analyze this Python code"

# Expected:
#   [ACP agent's response]
```

**Validates**: FR-001, FR-003, FR-009, SC-002

### Scenario 5: Retrieve Sub-Agent Results

```bash
# After a sub-agent completes:
/sub-agent results <uuid>

# Expected:
#   Sub-Agent Results: <uuid>
#   Status: COMPLETED
#   Duration: 45s
#   Messages: 12
#   Summary: [result summary]
```

**Validates**: FR-008, SC-001

### Scenario 6: Concurrent Sub-Agent Limit

```bash
# Attempt to spawn more than 5 sub-agents:
/sub-agent spawn --goal "task 6"

# Expected:
#   Error: Maximum concurrent sub-agents (5) reached. Cancel an existing sub-agent first.
```

**Validates**: FR-010

## Expected Artifacts

After successful validation:

```
alice-agent-command/src/.../command/SubAgentCmd.java                    # New sealed class
alice-core-agent/src/.../agent/subagent/SubAgentRecord.java             # Java record
alice-core-agent/src/.../agent/subagent/SubAgentRegistry.java           # Registry implementation
alice-core-agent/src/.../agent/subagent/SubAgentManager.java            # Orchestrator
alice-core-agent/src/.../agent/internal/acp/AcpClient.java              # ACP client wrapper
alice-core-agent/src/.../agent/internal/acp/AcpConnection.java          # Connection state
```

## Test Commands

```bash
# Run all sub-agent unit tests
./gradlew :alice-agent-command:test --tests "*SubAgentCmd*"
./gradlew :alice-core-agent:test --tests "*SubAgent*"
./gradlew :alice-core-agent:test --tests "*AcpClient*"

# Run facade dispatch tests
./gradlew :alice-facade-cmd:test --tests "*AliceCliLauncherSpec*"
./gradlew :alice-facade-tui:test --tests "*TuiSpec*"

# Full build with quality gates
./gradlew spotlessCheck check
```

## References

- [Specification](./spec.md) — Feature spec with user stories and requirements
- [Data Model](./data-model.md) — Entity definitions and validation rules
- [SubAgentCmd Contract](./contracts/SubAgentCmd-contract.md) — Command hierarchy contract
- [SubAgentRegistry Contract](./contracts/SubAgentRegistry-contract.md) — Registry contract
- [AcpClient Contract](./contracts/AcpClient-contract.md) — ACP client contract
- [ACP Java SDK Documentation](../../docs/acp/README.md) — ACP SDK reference
