---

description: "Implementation tasks for /sub-agent — Multi-Agent via ACP Protocol"
---

# Tasks: /sub-agent — Multi-Agent via ACP Protocol

**Input**: Design documents from `specs/003-sub-agent-acp/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Test tasks are included (Spock unit tests per command, facade dispatch tests, integration tests)

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions

- `alice-agent-command/src/main/java/...` — sealed command classes
- `alice-core-agent/src/main/java/...` — core sub-agent infrastructure
- `alice-facade-cmd/src/main/java/...` — CLI dispatch
- `alice-facade-tui/src/main/java/...` — TUI dispatch
- `*/src/test/groovy/...` — Spock tests

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization — add ACP SDK dependency, create new sealed command files

- [x] T001 Add `com.agentclientprotocol:acp-core:0.9.0` dependency to `alice-core-agent/build.gradle`
- [x] T002 [P] Create `SubAgentCmd.java` sealed interface in `alice-agent-command/src/main/java/org/cland/alice/agent/command/SubAgentCmd.java`
- [x] T003 [P] Create `SpawnSubAgentCmd.java` record in `alice-agent-command/src/main/java/org/cland/alice/agent/command/SpawnSubAgentCmd.java`
- [x] T004 [P] Create `ConnectSubAgentCmd.java` record in `alice-agent-command/src/main/java/org/cland/alice/agent/command/ConnectSubAgentCmd.java`
- [x] T005 [P] Create `ListSubAgentsCmd.java` record in `alice-agent-command/src/main/java/org/cland/alice/agent/command/ListSubAgentsCmd.java`
- [x] T006 [P] Create `CancelSubAgentCmd.java` record in `alice-agent-command/src/main/java/org/cland/alice/agent/command/CancelSubAgentCmd.java`
- [x] T007 [P] Create `GetSubAgentResultsCmd.java` record in `alice-agent-command/src/main/java/org/cland/alice/agent/command/GetSubAgentResultsCmd.java`
- [x] T008 [P] Create `SendToSubAgentCmd.java` record in `alice-agent-command/src/main/java/org/cland/alice/agent/command/SendToSubAgentCmd.java`
- [x] T009 [P] Create `PromptSubAgentCmd.java` record in `alice-agent-command/src/main/java/org/cland/alice/agent/command/PromptSubAgentCmd.java`
- [x] T010 Update `AgentCommand.java` sealed interface to permit `SubAgentCmd` as a new branch
- [x] T011 Update `alice-agent-command/src/main/java/module-info.java` to export the new command types if needed — already covered by existing `exports org.cland.alice.agent.command;`
- [x] T012 [P] Create `CommandParser` parse rule for `/sub-agent` in `alice-facade-cmd/src/main/java/org/cland/alice/facade/cmd/config/CommandParser.java`
- [ ] T013 [P] Create `SubAgentCmdParseSpec.groovy` in `alice-agent-command/src/test/groovy/org/cland/alice/agent/command/SubAgentCmdParseSpec.groovy` — test parse of all 7 sub-commands
- [ ] T014 [P] Create `SubAgentCmdSealedHierarchySpec.groovy` in `alice-agent-command/src/test/groovy/org/cland/alice/agent/command/SubAgentCmdSealedHierarchySpec.groovy` — verify sealed interface completeness

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core sub-agent infrastructure that must be complete before any user story

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T015 Create `SubAgentRecord.java` (Java record) in `alice-core-agent/src/main/java/org/cland/alice/agent/subagent/SubAgentRecord.java`
- [ ] T016 Create `SubAgentType.java` enum and `SubAgentStatus.java` enum in `alice-core-agent/src/main/java/org/cland/alice/agent/subagent/SubAgentType.java`
- [ ] T017 [P] Create `SubAgentRegistry.java` interface + implementation in `alice-core-agent/src/main/java/org/cland/alice/agent/subagent/SubAgentRegistry.java` (ConcurrentHashMap-backed, thread-safe)
- [ ] T018 [P] Create `SubAgentResult.java` record in `alice-core-agent/src/main/java/org/cland/alice/agent/subagent/SubAgentResult.java`
- [ ] T019 Create `SubAgentManager.java` orchestrator in `alice-core-agent/src/main/java/org/cland/alice/agent/subagent/SubAgentManager.java` — threading, lifecycle coordination, max-concurrent enforcement
- [ ] T020 [P] Create `SubAgentRegistrySpec.groovy` in `alice-core-agent/src/test/groovy/org/cland/alice/agent/subagent/SubAgentRegistrySpec.groovy` — register/list/updateStatus/remove/activeCount
- [ ] T021 [P] Create `SubAgentManagerSpec.groovy` in `alice-core-agent/src/test/groovy/org/cland/alice/agent/subagent/SubAgentManagerSpec.groovy` — lifecycle orchestration, concurrent limit

**Checkpoint**: Foundation ready — registry, records, and manager all operational. User story implementation can begin.

---

## Phase 3: User Story 1 — Alice Agent spawns a sub-agent (Priority: P1) 🎯 MVP

**Goal**: Parent session can spawn an in-process Alice sub-agent with independent WAL and ReAct loop, receive completion results

**Independent Test**: Spawn sub-agent with `--goal "list files in /tmp"`, verify it creates a new session, completes, and parent receives the result summary

### Tests for User Story 1

- [ ] T022 [P] [US1] Contract test: `AliceCliLauncherSpec.groovy` add `/sub-agent spawn` dispatch test in `alice-facade-cmd/src/test/groovy/org/cland/alice/facade/cmd/AliceCliLauncherSpec.groovy`
- [ ] T023 [P] [US1] Contract test: `TuiSpec.groovy` add `/sub-agent spawn` dispatch test in `alice-facade-tui/src/test/groovy/org/cland/alice/facade/tui/TuiSpec.groovy`
- [ ] T024 [US1] Integration test: `SubAgentSpawnE2ESpec.groovy` in `alice-core-agent/src/test/groovy/org/cland/alice/agent/subagent/SubAgentSpawnE2ESpec.groovy` — full spawn → execute → complete → read results

### Implementation for User Story 1

- [ ] T025 [P] [US1] Implement `SpawnSubAgentCmd` handler in `SubAgentManager.java` — create new `Agent` with isolated config, generate unique session ID, start async execution
- [ ] T026 [US1] Wire spawn handler to `AgentExecutor` — initialize with fresh `WalSession` using isolated session ID
- [ ] T027 [US1] Implement async completion notification — sub-agent execution completes, result summary returned to parent session context
- [ ] T028 [US1] Add SLF4J logging for spawn lifecycle events (FR-011) in `SubAgentManager.java`

**Checkpoint**: At this point, User Story 1 is fully functional — Alice can spawn sub-agents.

---

## Phase 4: User Story 2 — Connect to third-party ACP agent (Priority: P1)

**Goal**: Parent session can connect to an external ACP-compliant agent and exchange prompts/responses

**Independent Test**: Run a simple ACP-compliant agent on localhost, connect via `/sub-agent connect`, send a prompt, verify response received

### Tests for User Story 2

- [ ] T029 [P] [US2] Contract test: `AcpClientSpec.groovy` in `alice-core-agent/src/test/groovy/org/cland/alice/agent/acp/AcpClientSpec.groovy` — mock ACP agent connection and prompt/response
- [ ] T030 [US2] Integration test: `SubAgentConnectE2ESpec.groovy` in `alice-core-agent/src/test/groovy/org/cland/alice/agent/subagent/SubAgentConnectE2ESpec.groovy` — connect to real/local ACP agent, send prompt, verify response

### Implementation for User Story 2

- [ ] T031 [P] [US2] Create `AcpClient.java` in `alice-core-agent/src/main/java/org/cland/alice/agent/internal/acp/AcpClient.java` — wrap `AcpSyncClient` with three-phase lifecycle (initialize → newSession → prompt)
- [ ] T032 [P] [US2] Create `AcpConnection.java` in `alice-core-agent/src/main/java/org/cland/alice/agent/internal/acp/AcpConnection.java` — connection state, endpoint URL, session config
- [ ] T033 [US2] Implement `ConnectSubAgentCmd` handler in `SubAgentManager.java` — register external agent, initialize ACP connection
- [ ] T034 [US2] Implement `PromptSubAgentCmd` handler in `SubAgentManager.java` — route prompt to connected ACP agent, return response
- [ ] T035 [US2] Handle connection failures gracefully — `AcpConnectionException`, `AcpTimeoutException`, user-friendly error messages
- [ ] T036 [US2] Add SLF4J logging for connect/prompt lifecycle events (FR-011)

**Checkpoint**: User Stories 1 AND 2 both functional — Alice sub-agents AND external ACP integration work.

---

## Phase 5: User Story 3 — Monitor and manage sub-agents (Priority: P2)

**Goal**: Parent session can list all sub-agents, cancel running ones, and retrieve completed results

**Independent Test**: Spawn two sub-agents, list them, cancel one, verify cancellation and remaining agent

### Tests for User Story 3

- [ ] T037 [P] [US3] Contract test: CLI `ListSubAgentsCmd` dispatch test in `AliceCliLauncherSpec.groovy`
- [ ] T038 [P] [US3] Contract test: CLI `CancelSubAgentCmd` dispatch test in `AliceCliLauncherSpec.groovy`
- [ ] T039 [P] [US3] Contract test: CLI `GetSubAgentResultsCmd` dispatch test in `AliceCliLauncherSpec.groovy`
- [ ] T040 [US3] Integration test: `SubAgentManagementE2ESpec.groovy` in `alice-core-agent/src/test/groovy/org/cland/alice/agent/subagent/SubAgentManagementE2ESpec.groovy` — spawn → list → cancel → verify → results

### Implementation for User Story 3

- [ ] T041 [US3] Implement `ListSubAgentsCmd` handler in `SubAgentManager.java` — query registry, format output with ID/type/status/goal/duration
- [ ] T042 [US3] Implement `CancelSubAgentCmd` handler in `SubAgentManager.java` — terminate AgentExecutor, close WalSession, update status
- [ ] T043 [US3] Implement `GetSubAgentResultsCmd` handler in `SubAgentManager.java` — retrieve completed result summary from registry
- [ ] T044 [US3] Wire all three handlers into `AliceCliLauncher.dispatchCommand()` switch statement
- [ ] T045 [US3] Wire all three handlers into `AliceTuiLauncher.dispatchAgentCommand()` switch statement
- [ ] T046 [US3] Add SLF4J logging for list/cancel/results lifecycle events (FR-011)

**Checkpoint**: Full sub-agent lifecycle management operational.

---

## Phase 6: User Story 4 — Communication between parent and sub-agents (Priority: P2)

**Goal**: Parent and sub-agents can exchange structured messages during execution, enabling collaborative task execution

**Independent Test**: Spawn a sub-agent that sends incremental progress updates, parent queries its state, receives partial results

### Tests for User Story 4

- [ ] T047 [P] [US4] Contract test: CLI `SendToSubAgentCmd` dispatch test in `AliceCliLauncherSpec.groovy`
- [ ] T048 [US4] Integration test: `SubAgentCommunicationE2ESpec.groovy` in `alice-core-agent/src/test/groovy/org/cland/alice/agent/subagent/SubAgentCommunicationE2ESpec.groovy` — spawn → send message → sub-agent processes → parent queries status

### Implementation for User Story 4

- [ ] T049 [US4] Implement `SendToSubAgentCmd` handler in `SubAgentManager.java` — route structured message to running sub-agent's context
- [ ] T050 [US4] Add message queue/channel to `SubAgentManager` for parent-child message exchange
- [ ] T051 [US4] Wire `SendToSubAgentCmd` handler into `AliceCliLauncher.dispatchCommand()`
- [ ] T052 [US4] Wire `SendToSubAgentCmd` handler into `AliceTuiLauncher.dispatchAgentCommand()`
- [ ] T053 [US4] Add SLF4J logging for send lifecycle events (FR-011)

**Checkpoint**: All user stories complete — full multi-agent communication functionality.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Improve robustness, documentation, and quality across all stories

- [ ] T054 [P] Add ACP SDK dependency to `alice-core-agent/module-info.java` — `requires com.agentclientprotocol.sdk.client` and related modules
- [ ] T055 [P] Run `./gradlew spotlessCheck` and fix any formatting issues across all new files
- [ ] T056 [P] Run `./gradlew check` — verify all Spock tests pass
- [ ] T057 Update `CHANGELOG.md` with `/sub-agent` feature entry under "Features" section
- [ ] T058 Update `todos/TODO-spec.md` main board — mark `/sub-agent` tasks complete
- [ ] T059 Run `quickstart.md` validation scenarios end-to-end

---

## Dependencies & Execution Order

### Phase Dependencies

| Phase | Depends On | Description |
|-------|-----------|-------------|
| **P1 Setup** | — | Can start immediately |
| **P2 Foundational** | P1 | SubAgentCmd sealed interface + registry infrastructure |
| **P3 US1 (P1)** | P2 | Alice sub-agent spawning |
| **P4 US2 (P1)** | P2 | ACP agent connection (independent of US1) |
| **P5 US3 (P2)** | P3, P4 | List/cancel/results (builds on spawn + connect) |
| **P6 US4 (P2)** | P3 | Parent-child messaging (builds on spawn) |
| **P7 Polish** | All | Cross-cutting cleanup |

### User Story Independence

- **US1** and **US2** are **fully independent** — can be implemented in parallel after P2
- **US3** depends on US1 + US2 (needs both spawned and connected agents for full list)
- **US4** depends on US1 only (parent-child messaging only applies to spawned agents)
- Each story is independently testable

### Parallel Opportunities

- All T001–T014 (Setup) marked [P] can run in parallel
- T015–T021 (Foundational) sequential within phase but [P] for independent files
- US1 (T022–T028) and US2 (T029–T036) can run in parallel
- Tests within each story marked [P] can run in parallel
- Polish tasks T054–T057 independent and [P]-parallel

---

## Parallel Example: User Story 1

```bash
# Launch all tests for User Story 1 together:
Task: "Add spawn dispatch test in AliceCliLauncherSpec.groovy"
Task: "Add spawn dispatch test in TuiSpec.groovy"

# Launch implementation tasks:
Task: "Implement SpawnSubAgentCmd handler in SubAgentManager"
Task: "Wire spawn handler to AgentExecutor with fresh WalSession"
```

## Parallel Example: User Story 2

```bash
# Launch all ACP client tasks together:
Task: "Create AcpClient.java wrapper"
Task: "Create AcpConnection.java"
Task: "Create AcpClientSpec.groovy"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup — sealed hierarchy, parse rules, basic tests
2. Complete Phase 2: Foundational — registry, records, manager
3. Complete Phase 3: User Story 1 — spawn sub-agent 🎯 **MVP**
4. **STOP and VALIDATE**: Run SubAgentSpawnE2ESpec, verify sub-agent spawns, executes, completes
5. Deploy/demo if ready

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. User Story 1 (spawn) → **MVP — Alice can spawn sub-agents** → Deploy/Demo
3. User Story 2 (ACP connect) → Alice can integrate external agents → Deploy/Demo
4. User Story 3 (management) → Full lifecycle management → Deploy/Demo
5. User Story 4 (communication) → Rich multi-agent collaboration → Deploy/Demo

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundation is done (P2 checkpoint):
   - Developer A: User Story 1 (spawn)
   - Developer B: User Story 2 (ACP connect)
3. Both P1 stories complete → Developer C: User Story 3 (management)
4. Developer A or B: User Story 4 (communication)

---

## Summary

| Phase | Tasks | Story | Priority |
|-------|-------|-------|----------|
| P1 Setup | T001–T014 (14) | — | — |
| P2 Foundational | T015–T021 (7) | — | — |
| P3 US1 | T022–T028 (7) | Spawn sub-agent | **P1 MVP** |
| P4 US2 | T029–T036 (8) | ACP connect | **P1** |
| P5 US3 | T037–T046 (10) | List/cancel/results | P2 |
| P6 US4 | T047–T053 (7) | Parent-child comms | P2 |
| P7 Polish | T054–T059 (6) | Cross-cutting | — |
| **Total** | **59 tasks** | | |

## Notes

- [P] tasks = different files, no dependencies — safe to parallelize
- [Story] label maps task to specific user story for traceability
- Each user story independently completable and testable
- Tests should be written/verified failing before implementation
- Commit after each task or logical group
- Final validation: run `quickstart.md` scenarios end-to-end
