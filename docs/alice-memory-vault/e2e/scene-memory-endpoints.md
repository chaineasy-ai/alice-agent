---
title: "Hole Scene — alice-memory-vault memory endpoints"
summary: "Module-level hole tests probing VaultController, EpisodicVault, SemanticVault, ProceduralVault, WalStore public API boundaries."
read_when:
  - "running or debugging hole tests for alice-memory-vault"
scope:
  - "alice-memory-vault"
status: "active"
updated: "2026-06-19"
---

# Hole Scene — alice-memory-vault Memory Endpoints

## 1. Scene Overview

5 hole probes into the `alice-memory-vault` module.

**Case doc**: `docs/alice-agent-command/e2e/case-memory-vault.md`

## 2. Probe Map

```
┌─────────────────────────────────────┐
│         alice-memory-vault          │
│                                     │
│  MEM-P01  VaultController CRUD      │
│  MEM-P02  EpisodicVault trace       │
│  MEM-P03  SemanticVault search      │
│  MEM-P04  ProceduralVault match     │
│  MEM-P05  WAL crash recovery        │
└─────────────────────────────────────┘
```

## 3. How to Run

```bash
python docs/alice-memory-vault/e2e/hole_test_memory.py
```
