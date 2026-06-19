---
title: "Hole Scene — alice-core-agent executor endpoints"
summary: "Module-level hole tests probing AgentExecutor, StepResult, AgentContext, SubAgentManager public API boundaries. These are hole_test — not E2E, not unit."
read_when:
  - "running or debugging hole tests for alice-core-agent"
scope:
  - "alice-core-agent"
status: "active"
updated: "2026-06-19"
---

# Hole Scene — alice-core-agent Executor Endpoints

## 1. Scene Overview

This scene defines **4 hole probes** into the `alice-core-agent` module. Each probe verifies a public API boundary without inspecting internals.

**Case doc**: `docs/alice-agent-command/e2e/case-core-agent.md`

## 2. Probe Map

```
┌─────────────────────────────────────┐
│          alice-core-agent           │
│                                     │
│  AGT-P01  AgentExecutor.execute()   │
│  AGT-P02  StepResult sealed match   │
│  AGT-P03  AgentContext lifecycle    │
│  AGT-P05  SubAgentManager CRUD      │
└─────────────────────────────────────┘
```

## 3. How to Run

```bash
# Prerequisite: build the project
./gradlew :alice-core-agent:compileJava

# Run hole tests
python docs/alice-core-agent/e2e/hole_test_core_agent.py
```

## 4. Unit Test Cross-Reference

| Hole | Unit Test | Verifies |
|------|-----------|----------|
| AGT-P01 | `AgentPpaoLoopSpec.groovy` | Full PPAO loop iteration |
| AGT-P02 | `StepResultSpec.groovy` | StepResult sealed hierarchy |
| AGT-P03 | `AgentContextSpec.groovy` | Session state management |
| AGT-P05 | `SubAgentManagerSpec.groovy` | Sub-agent manager internals |
