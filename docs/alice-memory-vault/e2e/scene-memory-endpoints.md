---
title: "Hole Scene — alice-memory-vault memory endpoints"
summary: "Module-level hole tests probing VaultController, EpisodicVault, SemanticVault, ProceduralVault, WalStore public API boundaries."
read_when:
  - "running or debugging hole tests for alice-memory-vault"
scope:
  - "alice-memory-vault"
status: "active"
updated: "2026-06-20"
---

# Hole Scene — alice-memory-vault Memory Endpoints

## 1. Scene Overview

5 hole probes into the `alice-memory-vault` module.

**Aggregation root**: `VaultController` (facade over all three vault types)

**Case doc**: `docs/alice-agent-command/e2e/case-memory-vault.md`

**Inbound doc**: `docs/alice-memory-vault/inbound.md`

## 2. Probe Map

```
┌──────────────────────────────────────────────┐
│           alice-memory-vault                 │
│                                              │
│                 ┌──────────────┐             │
│                 │VaultController│            │
│                 │(Aggregate Root)│            │
│                 └──────┬───────┘             │
│         ┌──────────────┼──────────────┐      │
│         ▼              ▼              ▼      │
│  ┌────────────┐ ┌────────────┐ ┌──────────┐ │
│  │ Episodic   │ │ Semantic   │ │Procedural│ │
│  │ Vault      │ │ Vault      │ │ Vault    │ │
│  │ MEM-P02    │ │ MEM-P03    │ │ MEM-P04  │ │
│  └────────────┘ └────────────┘ └──────────┘ │
│                                              │
│  ┌──────────────┐   ┌──────────────┐        │
│  │ WalStore     │   │ VaultCtrl    │        │
│  │ MEM-P05      │   │ MEM-P01      │        │
│  └──────────────┘   └──────────────┘        │
└──────────────────────────────────────────────┘
```

## 3. Probe Details

| Hole | Key | Method | What it proves |
|------|-----|--------|----------------|
| MEM-P01 | `mem_ctrl` | `VaultController.memorize()` + `recall()` + `finalizeSession()` + null safety | Full CRUD round-trip through aggregate root, async consolidation, NPE contracts |
| MEM-P02 | `episodic` | All 10 EpisodicVault methods | Append, getRecentSteps/over-request, getImportantSteps/threshold-edge, getTrace/missing, sessionCount, getActiveSessionIds, stepCount, clearSession, penalizeStep/non-existent, clearAll |
| MEM-P03 | `semantic` | All 11 SemanticVault methods | store(collection,k), store(k)/default-collection, storeAll, search/fallback, searchAll/fallback, getAll/missing-collection, getCollections, count/missing, remove/missing, removeCollection/missing, clearAll |
| MEM-P04 | `procedural` | All 9 ProceduralVault methods | register, registerAll, match/exact+no-match, findByTool/exact+missing, getById/missing, getAll, count, re-register/update, remove/missing, clearAll |
| MEM-P05 | `wal` | All 17 FileWalStore public methods | appendMessage, getAllMessages/missing-session, getMessage/found+missing, getMessagesAfter, messageCount, saveCheckpoint, getLatestCheckpoint/missing, checkpointCount, getCheckpointHistory, deleteCheckpointsUpTo, deleteMessagesUpTo, crash recovery, activeSessionIds, clearSession, clearAll |

## 4. How to Run

### Java hole test (direct module boundary probes)

```bash
# Run all probes
./gradlew :alice-memory-vault:runHoleTest --args="all"

# Run individual probes
./gradlew :alice-memory-vault:runHoleTest --args="mem_ctrl"
./gradlew :alice-memory-vault:runHoleTest --args="episodic"
./gradlew :alice-memory-vault:runHoleTest --args="semantic"
./gradlew :alice-memory-vault:runHoleTest --args="procedural"
./gradlew :alice-memory-vault:runHoleTest --args="wal"
```

### Python orchestrator

```bash
python docs/alice-memory-vault/e2e/hole_test_memory.py
```

## 5. Probe Flow Diagram

```
MEM-P01 (VaultController)
  ──► new VaultController()
  ──► memorize(Experience{sessionId, action, observation, result})
  ──► recall(Context{query, sessionId})
  ──► MemorySet.entries() contains expected action

MEM-P02 (EpisodicVault)
  ──► new InMemoryEpisodicVault()
  ──► appendStep(session, step) × 3
  ──► getRecentSteps(session, 2) → last 2
  ──► getImportantSteps(session, threshold) → filtered
  ──► getTrace(session) → full 3 steps
  ──► clearSession(session) → stepCount = 0
  ──► penalizeStep(session, stepId, 0.5) → importance reduced

MEM-P03 (SemanticVault)
  ──► new InMemorySemanticVault()
  ──► store("docs", Knowledge{content}) × 2
  ──► store("guide", Knowledge{content}) × 1
  ──► search("docs", query) → results with Alice
  ──► searchAll(query) → cross-collection results
  ──► remove("docs", k1) → count("docs") = 1

MEM-P04 (ProceduralVault)
  ──► new InMemoryProceduralVault()
  ──► register(SOP{pattern, toolName}) × 2
  ──► match(Context{query}) → ranked SOPs
  ──► findByTool("read_file") → matched SOP
  ──► remove("sop-read") → count = 1

MEM-P05 (WAL FileWalStore)
  ──► new FileWalStore(tempDir)
  ──► appendMessage(RawMessage) × 3
  ──► getAllMessages("session-1") → 2 entries
  ──► messageCount("session-1") → 2
  ──► new FileWalStore(tempDir) [crash simulation]
  ──► getAllMessages("session-1") → 2 (persisted)
```
