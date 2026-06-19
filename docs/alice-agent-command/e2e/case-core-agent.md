---
title: "E2E Case — alice-core-agent endpoints"
summary: "Hole test specification for alice-core-agent module — AgentExecutor, StepResult, AgentContext, SubAgentManager public API boundaries."
read_when:
  - "implementing or modifying hole tests for alice-core-agent"
scope:
  - "alice-agent-command"
  - "alice-core-agent"
status: "active"
updated: "2026-06-19"
---

# E2E Case — alice-core-agent (Hole Test)

## 1. Purpose

Probe the **alice-core-agent** module's public API boundary by calling its core interfaces directly. These are **hole_test** — not full E2E, not unit tests, but endpoint verification holes that ensure module entry points don't break.

## 2. Hole Design

```
AgentExecutor.execute(Input) ──► StepResult [Finish|Continue|Failure]
         ● (AGT-P01)                     ● (AGT-P02)
AgentContext.getSession() ──► SessionState
         ● (AGT-P03)
SubAgentManager.register() / lookup() ──► SubAgent
         ● (AGT-P05)
```

Each hole injects known input at the module boundary and verifies the output shape/type — no internals introspection.

## 3. Hole Tests

### AGT-P01: `AgentExecutor.execute()` happy path

| Field | Value |
|-------|-------|
| **Target** | `AgentExecutor.execute(Input)` |
| **Input** | Mock `Input` with simple goal `"say hello"` |
| **Expected** | Returns `StepResult`, instance of `Finish` or `Failure` (not null, not exception) |
| **Assertion** | `result instanceof StepResult` |
| **Unit ref** | `AgentPpaoLoopSpec.groovy` — PPAO loop iteration |

### AGT-P02: `StepResult` sealed pattern match

| Field | Value |
|-------|-------|
| **Target** | `StepResult` sealed hierarchy |
| **Input** | Construct 3 instances: `Finish("ok")`, `Continue(action)`, `Failure(err)` |
| **Expected** | Switch pattern match reaches each branch |
| **Assertion** | Each branch produces expected side effect (e.g. log/return) |
| **Unit ref** | `StepResultSpec.groovy` — sealed hierarchy validation |

### AGT-P03: `AgentContext` session lifecycle

| Field | Value |
|-------|-------|
| **Target** | `AgentContext.getSession()` / `createSession()` / `clearSession()` |
| **Input** | Create session → read state → clear → read again |
| **Expected** | After create: session exists. After clear: session empty or re-created |
| **Assertion** | `getSession() != null` after create; state transition clean |
| **Unit ref** | `AgentContextSpec.groovy` — session state management |

### AGT-P04: (skipped — config loading is unit-only, no module boundary)

### AGT-P05: `SubAgentManager` register/list/lookup

| Field | Value |
|-------|-------|
| **Target** | `SubAgentManager.register()` / `listAll()` / `lookup()` |
| **Input** | Register 2 sub-agents, list, lookup by id, unregister |
| **Expected** | List returns 2, lookup by id returns correct one |
| **Assertion** | `listAll().size() == 2`, `lookup(id).id == id` |
| **Unit ref** | `SubAgentManagerSpec.groovy`, `SubAgentRegistrySpec.groovy` |

## 4. Test File

`docs/alice-core-agent/e2e/hole_test_core_agent.py`

Run via:
```bash
python docs/alice-core-agent/e2e/hole_test_core_agent.py
```
