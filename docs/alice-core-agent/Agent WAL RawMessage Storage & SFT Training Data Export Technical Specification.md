---
title: "Agent WAL — RawMessage Storage & SFT Training Data Export Specification"
summary: "WAL-based dual-track persistence (RawMessage + Checkpoint) for Agent runtime logs, full-link observability, and offline SFT training ETL"
read_when:
  - "understanding or modifying the WAL (Write-Ahead Log) subsystem"
  - "implementing SFT training data ETL pipelines"
  - "debugging message chain integrity, crash recovery, or context assembly"
scope:
  - "alice-core-agent"
  - "alice-memory-vault"
status: "active"
updated: "2026-06-25"
---

# Agent WAL — RawMessage Storage & SFT Training Data Export Specification

**Version:** V1.1 | **Scope:** Agent runtime WAL persistence, full-stack observability, offline SFT training ETL, distributed multi-node multi-agent clusters  
**Dependent Standards:** [OpenAI Chat Completions Message Spec](../OpenAI%20Chat%20Completions%20%E6%B6%88%E6%81%AF%E5%AF%B9%E8%B1%A1%E8%A7%84%E8%8C%83.md), W3C Trace Context, Custom Multi-Agent Sub-Agent Linkage Protocol  
**Target Audience:** Backend Engineers, Observability Platform Developers, Data ETL Engineers, LLM Training Engineers

---

## Table of Contents

1. [Design Overview](#1-design-overview)
2. [RawMessage Entity](#2-rawmessage-entity)
3. [Role Semantics & WAL Write Rules](#3-role-semantics--wal-write-rules)
4. [Multi-Agent & Sub-Agent Linkage](#4-multi-agent--sub-agent-linkage)
5. [Online Inference Context Assembly](#5-online-inference-context-assembly)
6. [SFT Training Data ETL](#6-sft-training-data-etl)
7. [Observability Query](#7-observability-query)
8. [Production Constraints](#8-production-constraints)
9. [Version Compatibility](#9-version-compatibility)
10. [Cross-Reference Table](#10-cross-reference-table)

---

## 1. Design Overview

### 1.1 Design Objectives

1. **Single source of truth** via append-only WAL — one log powers online inference context assembly, full-link observability, and offline fine-tuning sample export, eliminating schema inconsistency between online/offline datasets.
2. **Layered ID system** decouples user sessions, single-turn queries, execution spans, and private sub-agent contexts to resolve nested multi-agents, parallel tool invocations, and multi-level subtask tracing.
3. **`compact` role** for long-context rolling compression — aligns input distributions between training and online inference; resolves "historical context fragmentation" in long-session SFT datasets.
4. **`isUserVisible` binary flag** separates two data pipelines: standard end-user dialogue SFT, and CoT/tool/multi-agent capability fine-tuning.
5. **Hard constraints** for extreme production scenarios: distributed concurrency, streaming output, parallel tool calls, compact message backtracking.

### 1.2 Core Terminology

| Term | Definition |
|------|------------|
| **sessionId** | Top-level session ID for one user chat window; shared by all messages (main + sub-agents) for session aggregation and training grouping |
| **traceId** | Turn ID — root trace for one user query; one trace maps to exactly one SFT sample; all LLM, tool, and sub-agent messages in this turn share the same traceId |
| **spanId** | Span ID — unique identifier for the smallest execution unit (LLM thought, tool call, sub-agent root, final response); tree reconstructed via `parentSpanId` |
| **subAgentLocalSessionId** | Private context identifier for sub-agents, stored only in metadata; isolates sub-agent logs without polluting top-level session |
| **compact** | Compressed summary role — lightweight substitute for evicted historical turns when context window is exceeded; never replaces system prompts |
| **messageId** | Session-wide globally ordered unique ID (Snowflake); primary sort key; local auto-increment forbidden in distributed clusters |

---

## 2. RawMessage Entity

### 2.1 Java Record Definition

```java
/**
 * RAW Message Entity — single record in the WAL (Write-Ahead Log).
 *
 * Follows the OpenAI Chat Completions message specification, supporting plaintext,
 * multimodal, and tool call scenarios. The Java Record type is natively immutable;
 * defensive copies of mutable collections are enforced at construction time.
 *
 * Each RawMessage is the smallest atomic unit of Agent runtime interaction,
 * appended to the WAL via append-only writes.
 *
 * @param messageId  Distributed session-wide unique ordered ID
 * @param sessionId  Top-level user session identifier
 * @param role       Message role: system | user | assistant | tool | compact
 * @param content    Plaintext payload; null if toolCalls is populated
 * @param toolCalls  Tool call instructions (assistant role only)
 * @param toolCallId Pairing ID for tool return payloads (tool role only)
 * @param name       Optional identifier for primary or sub-agent identities
 * @param timestamp  Millisecond timestamp for fallback chronological sorting
 * @param metadata   Extended metadata carrying trace identifiers, sub-agent tags,
 *                   visibility flags, token consumption, etc.
 */
public record RawMessage(
    long messageId,
    String sessionId,
    String role,
    String content,
    List<ToolCall> toolCalls,
    String toolCallId,
    String name,
    long timestamp,
    Map<String, Object> metadata) {

  public static final List<String> VALID_ROLES =
      List.of("system", "user", "assistant", "tool", "compact");

  /** Static factory — all callers MUST use this; direct new is prohibited. */
  public static RawMessage create(
      long messageId, String sessionId, String role, String content,
      List<ToolCall> toolCalls, String toolCallId, String name,
      long timestamp, Map<String, Object> metadata) {
    List<ToolCall> safeTools = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    Map<String, Object> safeMeta = metadata == null ? Map.of() : Map.copyOf(metadata);
    return new RawMessage(messageId, sessionId, role, content,
                          safeTools, toolCallId, name, timestamp, safeMeta);
  }
}
```

### 2.2 Field Specification

| Field | Type | Constraints & Semantics |
|-------|------|-------------------------|
| `messageId` | long | **Globally ordered, session-wide unique.** Local in-memory auto-increment is **strictly forbidden** in distributed clusters. Use Snowflake ID: `sessionId + msTimestamp + shardAtomicIncrement`. Primary sorting key. |
| `sessionId` | String | Immutable top-level session identifier, propagated uniformly across all messages. |
| `role` | String | Must be one of `VALID_ROLES`. Dictates the record's business semantics. |
| `content` | String | Primary text payload. May be `null` for assistant records carrying `toolCalls`. **Streaming fragments must never be persisted** — only fully assembled complete payloads. |
| `toolCalls` | `List<ToolCall>` | Valid only for `assistant` roles. Parallel multi-tool calls stored in ordered sequence; returned tool records must align. |
| `toolCallId` | String | Valid only for `tool` roles. One-to-one mapping to a single `ToolCall` in the upstream assistant record. |
| `name` | String | Optional label for primary agents or sub-agents (`system`/`assistant` records). |
| `timestamp` | long | Millisecond timestamp at WAL append time. Fallback sort key and latency calculation. |
| `metadata` | `Map<String,Object>` | Extended metadata container. **Immutable after persistence.** Standardized keys defined in §2.3. |

### 2.3 Standardized Metadata Keys

> All tracing, training, and sub-agent identifiers are encapsulated **within metadata only**. No new top-level RawMessage fields shall be introduced.

| Key | Type | Description |
|-----|------|-------------|
| `traceId` | String | Root trace ID for the current dialogue turn (unique turn identifier) |
| `spanId` | String | Unique identifier for the current execution span |
| `spanType` | String | Enumerated span category: `user_input` / `system_prompt_init` / `history_compact` / `llm_think` / `llm_final_response` / `sub_agent_container` / `llm_sub_response` / `tool_call` |
| `parentSpanId` | String | Parent span ID for reconstructing nested call trees; empty for root spans. For `compact` messages, points to the root `spanId` of the triggering trace. |
| `isUserVisible` | Boolean | `true` = rendered to end users, eligible for primary SFT samples. `false` = intermediate reasoning, sub-agent internal output, or compressed summaries (observability or CoT fine-tuning only). |
| `subAgentLocalSessionId` | String | Private sub-agent session tag; present exclusively on sub-agent-related records. |
| `subAgentName` | String | Human-readable sub-agent label, paired with `subAgentLocalSessionId`. |
| `token_input` / `token_output` | Long | Input/output token consumption for LLM invocations (billing and observability). |

### 2.4 ToolCall Sub-Entity

```java
public record ToolCall(String id, String type, Function function) {
  public record Function(String name, String arguments) {}

  public static ToolCall of(String id, String toolName, Map<String, Object> arguments);
  public static ToolCall ofJson(String id, String toolName, String jsonArguments);
}
```

- Follows the OpenAI Chat Completions `assistant.tool_calls` array element spec.
- `type` must be `"function"`.
- `arguments` is a JSON string (e.g., `{"location":"Beijing"}`).

---

## 3. Role Semantics & WAL Write Rules

### 3.1 System Role

| Aspect | Detail |
|--------|--------|
| **Semantics** | Defines global Agent persona, output formatting rules, tool boundaries, and safety guardrails |
| **Write rules** | • One initial system record at session initialization<br>• New system record when switching Agent personas mid-conversation<br>• Independent system records for sub-agents, tagged with `subAgentLocalSessionId` |
| **Training** | Every SFT sample must carry the active valid system prompt as the first entry; system prompts shall **never** be truncated or replaced by compact summaries |

### 3.2 User Role

| Aspect | Detail |
|--------|--------|
| **Semantics** | Raw end-user queries and multimodal input payloads |
| **Write rule** | One user record per complete user message submission, marking the start of a new trace |
| **Metadata** | `traceId`, `spanType=user_input`, `isUserVisible=true` |

### 3.3 Assistant Role (Multi-Type `spanType`)

> **Hard streaming constraint:** The WAL rejects partial streaming fragments. Buffer all partial stream output in memory until stream termination (`finish_reason` received, full content/toolCalls assembled). Only the complete aggregated `RawMessage` is appended to WAL.

| `spanType` | Description | `isUserVisible` |
|------------|-------------|:---------------:|
| `llm_think` | Intermediate chain-of-thought reasoning and tool planning | `false` (CoT fine-tuning only) |
| `llm_final_response` | Final externally exposed reply to end users | `true` (primary SFT source) |
| `sub_agent_container` | Root parent span record marking sub-agent initialization | `false` |
| `llm_sub_response` | Internal LLM output within a sub-agent (tagged with `subAgentLocalSessionId`) | `false` |

Records with non-empty `toolCalls` represent outgoing tool invocation requests.

### 3.4 Tool Role

> **Parallel multi-tool sequencing rule:**
> 1. Persisted tool records must be written in the **same order** as the `toolCalls` list in the upstream assistant record.
> 2. If asynchronous tool return payloads arrive out-of-order (disordered timestamps due to network latency), ETL pipelines **MUST** re-sort all associated tool records by referencing the upstream assistant's `toolCalls` list.

- `toolCallId` creates a one-to-one mapping with a single `ToolCall` entry in the upstream assistant record.
- Tool records may belong to the main agent or any nested sub-agent, inheriting the same top-level `sessionId` and `traceId`.

### 3.5 Compact (Compressed Summary) Role

> **Trigger condition:** Generated after exceeding the model context window threshold.

| Aspect | Detail |
|--------|--------|
| **Semantics** | Condensed substitute for evicted early dialogue turns |
| **Write rule** | One compact record per compression operation; `spanType=history_compact`, `isUserVisible=false` |
| **Anchoring** | `parentSpanId` MUST equal the root `spanId` of the trace that initiated compression |
| **Ordering** | `messageId` and `timestamp` are guaranteed to be larger than the corresponding turn's user record |
| **Placement** | In inference/training message lists, compact entries are fixed between the system prompt and fresh dialogue turns |
| **Quantity** | Maximum of **one** latest compact record per SFT sample; stacking is prohibited |

---

## 4. Multi-Agent & Sub-Agent Linkage

### 4.1 Global Context Propagation

All `RawMessage` records generated by sub-agents **inherit** the immutable top-level identifiers:
- `sessionId`
- `traceId`

Private sub-agent identifiers are encapsulated exclusively within `metadata`; no top-level schema fields are modified.

### 4.2 Nested Span Tree Construction

1. When the main agent dispatches a subtask, an assistant record with `spanType=sub_agent_container` is created as the sub-agent root span.
2. All internal LLM, tool, and system records generated by the sub-agent set `parentSpanId` equal to the container span's `spanId`.
3. Unlimited multi-layer sub-agent nesting is supported; full call trees are reconstructed recursively via `parentSpanId`.
4. Private system prompts and compact summaries belonging to a sub-agent only match records sharing the identical `subAgentLocalSessionId`.

### 4.3 Example Sub-Agent Container Metadata

```json
{
  "traceId": "turn_t001",
  "spanId": "span_sub_weather",
  "spanType": "sub_agent_container",
  "parentSpanId": "span_main_think",
  "subAgentLocalSessionId": "sub_sess_w001",
  "subAgentName": "Weather Query Sub-Agent",
  "isUserVisible": false
}
```

---

## 5. Online Inference Context Assembly

LLM input message lists are assembled in fixed priority order:

```
1. Active valid system prompt record
2. Compact record (if present, one only)
3. Chronologically ordered user/assistant/tool interaction turns
   → Parallel tool records re-sorted to match upstream toolCall sequence
```

For sub-agent internal inference:
```
1. Sub-agent's dedicated system prompt
2. Sub-agent's compact summary (if applicable)
3. Sub-agent's internal interaction logs
```

---

## 6. SFT Training Data ETL

### 6.1 Core Mappings

```
1 sessionId (full conversation)
  └── N traceId (single dialogue turns)
       └── 1 base SFT sample per unique traceId
```

Each `traceId` maps to multiple `RawMessage` records. Filtering logic is driven entirely via `metadata` flags.

### 6.2 System Prompt Extraction

| Step | Action |
|------|--------|
| 1 | Sort all session records ascending by `messageId` |
| 2 | Maintain `currentGlobalSystem` — overwritten when `role=system` is encountered |
| 3 | When a `user` record is encountered (new trace boundary), persist `traceId → currentGlobalSystem` |
| 4 | For sub-agent samples: match dedicated system prompt via `subAgentLocalSessionId` |
| 5 | Traces missing any associated system prompt are marked as **dirty data** and filtered out |

### 6.3 SFT Sample Formats

#### Format 1: Native Embedded Role (Recommended)

Compact exists as an independent list entry in `messages`. Compatible with training frameworks supporting custom roles.

```json
{
  "messages": [
    {"role": "system", "content": "You are a weather assistant; only respond with temperature and precipitation information."},
    {"role": "compact", "content": "Historical summary: The user previously queried weather for Beijing and Shanghai over three days; the assistant invoked tool calls to return temperature and rainfall data."},
    {"role": "user", "content": "What is the weather forecast for Guangzhou next week?"},
    {"role": "assistant", "content": "Guangzhou will see temperatures ranging from 22°C to 30°C next week, with light rain on Thursday and Friday."}
  ]
}
```

#### Format 2: Top-Level Independent Field (Legacy Compat)

For frameworks supporting only `system/user/assistant/tool`.

```json
{
  "system_prompt": "You are a weather assistant; only respond with temperature and precipitation information.",
  "history_compact_summary": "Historical summary: The user previously queried weather for Beijing and Shanghai over three days; the assistant invoked tool calls to return temperature and rainfall data.",
  "messages": [
    {"role": "user", "content": "What is the weather forecast for Guangzhou next week?"},
    {"role": "assistant", "content": "Guangzhou will see temperatures ranging from 22°C to 30°C next week, with light rain on Thursday and Friday."}
  ]
}
```

> **Mandatory safe concatenation template** (prevents summary contamination of system prompt weighting):
> ```
> ${system_prompt}
>
> ===== CONTEXT HISTORY SUMMARY =====
> ${history_compact_summary}
> ===================================
> ```

### 6.4 Sliding-Window Multi-Turn Slicing

1. **Single-turn-only export is prohibited.** Always use sliding-window continuous multi-turn sample generation.
2. Each window retains only the most recent N groups of user-assistant dialogue pairs.
3. **Compact backtracking:** Traverse all traceIds covered by the window slice; locate all compact records whose `parentSpanId` maps to the current `traceId`; select the chronologically latest compact entry. No compact added if none found.
4. **Per-sample limit:** Only the single latest compact summary is retained.

### 6.5 Two Training Target Filtering Pipelines

#### Scenario A: Standard End-User Dialogue SFT

| Filter out | Retain |
|------------|--------|
| `llm_think` records | `system`, `compact` |
| All sub-agent internal messages | `user` |
| Full tool call interaction chains | `assistant` with `isUserVisible=true && spanType=llm_final_response` |

#### Scenario B: Tool & Multi-Agent CoT Fine-Tuning

All interaction records are preserved unmodified: `system`, `compact`, `user`, `assistant` (all spanTypes), `tool`. Parallel tool records are re-sorted to match upstream `toolCall` order.

### 6.6 Core ETL Pseudocode

```java
// 1. Load session records sorted by messageId
List<RawMessage> sessionMsgList = loadSessionMessages(sessionId).sorted(byMessageId);
String currentGlobalSystem = null;
Map<String, String> traceSystemMap = new HashMap<>();
Map<String, String> subAgentSystemMap = new HashMap<>();
Map<String, RawMessage> traceCompactMap = new HashMap<>();
Map<String, RawMessage> traceToolSourceMap = new HashMap<>();

// 2. Single pass: build system, compact, tool source mappings
for (RawMessage msg : sessionMsgList) {
    String traceId = (String) msg.metadata().get("traceId");
    if ("system".equals(msg.role())) {
        String subSess = (String) msg.metadata().get("subAgentLocalSessionId");
        if (subSess != null) subAgentSystemMap.put(subSess, msg.content());
        else currentGlobalSystem = msg.content();
        continue;
    }
    if ("user".equals(msg.role())) {
        traceSystemMap.put(traceId, currentGlobalSystem);
        continue;
    }
    if ("assistant".equals(msg.role()) && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
        traceToolSourceMap.put(traceId, msg);
        continue;
    }
    if ("compact".equals(msg.role())) {
        traceCompactMap.put(traceId, msg);
        continue;
    }
}

// 3. Iterate over sliding window slices
for (WindowSlice slice : sessionSlidingWindowSlices) {
    String traceId = slice.associatedTraceId;
    String systemText = traceSystemMap.get(traceId);
    RawMessage compactMsg = traceCompactMap.get(traceId);
    String compactText = compactMsg == null ? null : compactMsg.content();

    // Reorder parallel async tool returns
    RawMessage toolSource = traceToolSourceMap.get(traceId);
    if (toolSource != null) reorderToolMessages(slice.records, toolSource.toolCalls());

    List<Message> filtered = filterValidTrainingMessages(slice.records);
    buildSftSample(systemText, compactText, filtered);
}
```

---

## 7. Observability Query

| Scenario | Query Pattern |
|----------|--------------|
| Full session log | Filter by `sessionId`; sort by `messageId` |
| Single-turn linkage graph | Filter by `traceId`; reconstruct waterfall via `spanId` + `parentSpanId` |
| Sub-agent isolated logs | Filter by `metadata.subAgentLocalSessionId` |
| Anomaly troubleshooting | Filter by `spanType`, token consumption, and timestamp |
| Compression tracing | Filter `compact` records by `parentSpanId` matching target trace root span |

---

## 8. Production Constraints

> The following are hard constraints — violations are considered defects.

1. **System prompts** shall never be truncated or replaced by compact summaries.
2. **Compact** entries function solely as historical context substitutes; maximum **one** per sample; each must anchor `parentSpanId` to the triggering trace.
3. **sessionId** remains immutable throughout the conversation lifecycle; sub-agent private context identifiers exist exclusively within `metadata`.
4. **Primary assistant text** for standard SFT samples is limited strictly to `isUserVisible=true && spanType=llm_final_response`.
5. **All tracing identifiers** are encapsulated within `metadata` — no new top-level RawMessage fields.
6. **WAL is append-only** — modification or deletion of persisted records is forbidden. Immutable defensive copies enforced via static `create` factory.
7. **Local auto-increment** for `messageId` is prohibited in distributed clusters — use unified Snowflake ID generation.
8. **Streaming partial fragments** — never persisted to WAL; only fully assembled complete records post stream termination.
9. **ETL parallel tool reordering** — MUST reorder async tool return records to match upstream `toolCalls` list.
10. **Sliding-window compact backtracking** — MUST locate compact records mapped to the target `traceId` to avoid lost historical context.

---

## 9. Version Compatibility

| Rule | Detail |
|------|--------|
| **Backward compatibility** | New `spanType` enumerations and metadata keys are backward-compatible; unrecognized fields are ignored without runtime failures. |
| **Legacy untraced records** | Historical RawMessage records missing `traceId`/`spanId`/`parentSpanId` are flagged as dirty data; filtered out of observability dashboards and training exports. |
| **New roles** | Must first be added to `VALID_ROLES` constant; records with unregistered role values are rejected during WAL write validation. |
| **Legacy mutable records** | Records lacking defensive immutable copies undergo offline immutable conversion during ETL preprocessing. |

---

## 10. Cross-Reference Table

| Identified Vulnerability | Remediation Location | Summary |
|--------------------------|----------------------|---------|
| Distributed concurrent `messageId` local auto-increment failure | §2.2 Field Constraints + Static Factory Method | Unified Snowflake ID; local auto-increment deprecated; formalized `messageId` primary sort |
| Parallel tool call chronological misalignment | §3.4 Parallel Tool Constraints + ETL Reordering | ETL re-sorts async tool payloads to match upstream `toolCall` sequence |
| Compact summary backtracking loss for early window slices | §3.5 Anchoring + §6.4 Backtracking | Compact records bind `parentSpanId` to triggering trace; sliding windows retrieve matching trace-bound compact |
| Mutable `HashMap` metadata post-write tampering | §2.1 Static `create` Factory | Full immutable deep copies for Map/List; direct `new Record` blocked |
| System prompt weight contamination from naive compact concatenation | §6.3 Standard Training Separator | Fixed separator block isolates system instructions from historical summary |
| Persistence of incomplete streaming fragments | §3.3 Streaming Output Constraints | Only fully assembled records with finished stream signals are written to WAL |

---

## Appendix A: WAL File Layout (FileWalStore)

```
<dataDir>/
  ├── <sessionId>.wal.jsonl          — WAL messages (Append-Only JSONL)
  ├── <sessionId>.checkpoint.json    — Latest checkpoint (overwrite)
  └── _seq                           — Sequence ID persistence
```

## Appendix B: Implementation Classes

| Class | Package | Responsibility |
|-------|---------|---------------|
| `RawMessage` | `org.cland.alice.core.agent.wal` | WAL record entity |
| `ToolCall` | `org.cland.alice.core.agent.wal` | Tool call sub-entity |
| `WalStore` | `org.cland.alice.core.agent.wal` | Storage layer interface |
| `InMemoryWalStore` | `org.cland.alice.core.agent.wal` | In-memory implementation (dev/testing) |
| `FileWalStore` | `org.cland.alice.core.agent.wal` | JSONL file implementation |
| `WalAppender` | `org.cland.alice.core.agent.wal` | Append-only writer, linkage validation |
| `WalSession` | `org.cland.alice.core.agent.wal` | Unified facade (WAL + Checkpoint + Recovery + Melter) |
| `Checkpoint` | `org.cland.alice.core.agent.wal` | Checkpoint entity |
| `CheckpointManager` | `org.cland.alice.core.agent.wal` | Checkpoint generation and idempotency |
| `RecoveryEngine` | `org.cland.alice.core.agent.wal` | Crash recovery and state replay |
| `WalCompactor` | `org.cland.alice.core.agent.wal` | Background compaction and cleanup |
| `PromptMelter` | `org.cland.alice.core.agent.wal` | Dual-track prompt melting |
