---
title: "E2E Case — alice-memory-vault endpoints"
summary: "Hole test specification for alice-memory-vault module — VaultController, EpisodicVault, SemanticVault, ProceduralVault, WalStore public API boundaries."
read_when:
  - "implementing or modifying hole tests for alice-memory-vault"
scope:
  - "alice-agent-command"
  - "alice-memory-vault"
status: "active"
updated: "2026-06-19"
---

# E2E Case — alice-memory-vault (Hole Test)

## 1. Purpose

Probe the **alice-memory-vault** module's public API boundary — memory storage, retrieval, and persistence.

## 2. Hole Design

```
Experience ──► VaultController.memorize() / recall() ──► MemorySet
                  ● (MEM-P01)           ● (MEM-P01)
SessionId ──► EpisodicVault.getRecentTrace() ──► List<Step>
                  ● (MEM-P02)
Query ──► SemanticVault.search() ──► List<Knowledge>
            ● (MEM-P03)
Context ──► ProceduralVault.matchPattern() ──► List<SOP>
              ● (MEM-P04)
WAL entry ──► WalStore.write() / crash / recover
                ● (MEM-P05)
```

## 3. Hole Tests

### MEM-P01: `VaultController.memorize()` + `recall()`

| Field | Value |
|-------|-------|
| **Input** | Memorize 1 experience, recall with matching context |
| **Expected** | Recalled MemorySet contains the experience |
| **Assertion** | `memorySet.size() >= 1` |

### MEM-P02: `EpisodicVault.getRecentTrace()`

| Field | Value |
|-------|-------|
| **Input** | Log 3 steps for session, get recent 2 |
| **Expected** | Returns 2 most recent steps |
| **Assertion** | `len(trace) == 2`, trace matches last 2 steps |

### MEM-P03: `SemanticVault.search()` vector search

| Field | Value |
|-------|-------|
| **Input** | Insert knowledge with embedding, search by similar query |
| **Expected** | Returns relevant knowledge |
| **Assertion** | `results.size() > 0`, results have similarity score |

### MEM-P04: `ProceduralVault.matchPattern()`

| Field | Value |
|-------|-------|
| **Input** | Register SOP for "file_read", query with file-reading context |
| **Expected** | Returns matched SOP |
| **Assertion** | `sops.size() >= 1`, sop matches expected pattern |

### MEM-P05: WAL crash recovery

| Field | Value |
|-------|-------|
| **Input** | Write data → simulate crash → recover |
| **Expected** | Recovered data matches pre-crash data |
| **Assertion** | Data integrity after recovery |
