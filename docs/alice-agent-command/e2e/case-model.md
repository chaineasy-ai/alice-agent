---
title: "E2E Case — alice-model endpoints"
summary: "Hole test specification for alice-model module — ModelProvider dispatch, Call lifecycle, ModelSupplier adapter, config loading, multi-provider routing."
read_when:
  - "implementing or modifying hole tests for alice-model"
scope:
  - "alice-agent-command"
  - "alice-model"
status: "active"
updated: "2026-06-19"
---

# E2E Case — alice-model (Hole Test)

## 1. Purpose

Probe the **alice-model** module's public API boundary — model provider dispatch, call lifecycle, supplier adaptation, and routing.

## 2. Hole Design

```
Request ──► ModelProvider.dispatch() ──► Call
              ● (MDL-P01)     ─── with FakeSupplier
Call ──► Call.execute() ──► Response
         ● (MDL-P02)  ─── state: NEW→RUNNING→DONE/FAILED
ChatReq ──► ModelSupplier.chat() ──► ChatResponse
              ● (MDL-P03)  ─── Mock HTTP response
Config ──► ModelConfigLoader.load() ──► ModelConfig
            ● (MDL-P04)
2 suppliers ──► ModelProvider by modelId ──► correct supplier
                  ● (MDL-P05)
```

## 3. Hole Tests

### MDL-P01: `ModelProvider.dispatch()` with FakeSupplier

| Field | Value |
|-------|-------|
| **Input** | Register FakeSupplier, dispatch request with matching modelId |
| **Expected** | Returns `Call` routed to FakeSupplier |
| **Assertion** | `call.supplierId == "fake"` |

### MDL-P02: `Call.execute()` lifecycle

| Field | Value |
|-------|-------|
| **Input** | Construct Call, execute via FakeSupplier that succeeds |
| **Expected** | State: NEW → RUNNING → DONE |
| **Assertion** | `call.status == DONE`, `call.response != null` |

### MDL-P03: `ModelSupplier.chat()` response parsing

| Field | Value |
|-------|-------|
| **Input** | Mock HTTP response JSON, call `parseResponse()` |
| **Expected** | Returns `ChatResponse` with parsed content |
| **Assertion** | `response.content == expected` |

### MDL-P04: `ModelConfigLoader.loadConfig()` from JSON

| Field | Value |
|-------|-------|
| **Input** | JSON config string with model definitions |
| **Expected** | Returns `ModelConfig` with correct fields |
| **Assertion** | `config.models.size() > 0`, `config.models[0].name == expected` |

### MDL-P05: Multi-supplier routing by modelId

| Field | Value |
|-------|-------|
| **Input** | Register supplier A for "gpt-4o", supplier B for "claude-3" |
| **Expected** | dispatch("gpt-4o") → A, dispatch("claude-3") → B |
| **Assertion** | Correct supplier handles each request |
