---
title: "alice-model README"
summary: "Model provider layer - abstraction over LLM providers (OpenAI, Gemma4, etc.)"
read_when:
  - "understanding model provider layer"
scope:
  - "alice-model"
status: "active"
updated: "2026-06-13"
---
# alice-model — Model Provider Layer

## Overview

Multi-model access layer that decouples Agent business logic from physical LLM API implementations. Supports hot-swappable models, cost tracking, and unified call lifecycle management.

## Modules Structure

```
alice-model/src/main/java/org/cland/alice/model
├── Call.java                   # Call lifecycle (traceId, status, payload, result, metrics)
├── CallStatus.java             # State machine: CREATED → PENDING → RUNNING → FINISHED
├── Model.java                  # Model metadata (capability bitmask, pricing)
├── ModelContext.java           # Extended context map
├── ModelProvider.java          # Core entry: register suppliers/models, route, dispatch
├── ModelSupplier.java          # SPI interface for API adapters (@FunctionalInterface)
├── common/
│   └── ModelEnum.java          # 14 built-in model definitions with pricing
└── supplier/
    └── OpenAiSupplier.java     # OpenAI Chat Completion adapter
```

## Data Flow

```
Agent → ModelProvider.dispatch(modelId, prompt)
         ├── router → lookup ModelSupplier
         ├── create Call(Payload)
         ├── Call.transitionTo(PENDING → RUNNING)
         ├── ModelSupplier.request(Call)
         ├── Call.updateResult(Response)
         └── return Call (FINISHED / FAILED)
```

## Key Concepts

| Concept | Description |
|---|---|
| **ModelProvider** | Singleton. Register suppliers & models, dispatch calls. |
| **Call** | Per-invocation object with status state machine, metrics, token usage. |
| **ModelSupplier** | SPI — implement `request(Call)` to add a new provider. |
| **Model** | Metadata: modelId, supplier, capability flags, pricing. |

## State Machine

```
CREATED → PENDING → RUNNING → FINISHED
  │         │          │
  │         │          ├→ FAILED
  │         │          └→ RETRY → PENDING
  │         │                     └→ FAILED
  │         └→ RETRY
  └→ ABORTED
```

Terminal states: `FINISHED`, `FAILED`, `ABORTED`

## Usage

```java
ModelProvider provider = ModelProvider.getInstance();

// Register built-in models
provider.registerBuiltinModels();

// Register an API supplier
provider.registerSupplier(new OpenAiSupplier(System.getenv("OPENAI_API_KEY")));

// Dispatch a call
Call call = provider.dispatch("gpt-4o", "Hello!");
System.out.println(call.result().content());
```

## Adding a New Provider

1. Implement `ModelSupplier`
2. Register it:
```java
provider.registerSupplier(new MyCustomSupplier(...));
```

## Configuration

Set via environment variables:
- `OPENAI_API_KEY` — OpenAI API key
