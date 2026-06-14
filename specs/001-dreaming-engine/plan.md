# Implementation Plan: Offline Dreaming Engine

**Branch**: `001-dreaming-engine` | **Date**: 2026-06-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-dreaming-engine/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Implement the Offline Dreaming Engine — an asynchronous background pipeline in
`alice-memory-vault` that consumes raw WAL logs from completed/crashed sessions
and produces refined episodic summaries, semantic knowledge, and crystallized
SOPs across the three vaults. The pipeline consists of three stages:
PromptMelter (log → episodic summary), ConflictResolver (fact → semantic
knowledge with conflict detection), and Crystallizer (pattern → SOP). It is
triggered by configurable mechanisms (idle timer, WAL threshold, on-demand
command) and ensures exactly-once processing via session state locking.

## Technical Context

**Language/Version**: Java 25 (toolchain `JavaLanguageVersion.of(25)`, release `25`)

**Primary Dependencies**: None new — all infrastructure exists within
`alice-memory-vault` (WalStore, Vault interfaces, WalSession lifecycle)

**Storage**: In-memory (via existing InMemoryWalStore, InMemoryEpisodicVault,
InMemorySemanticVault, InMemoryProceduralVault). The Dreaming Engine itself is
storage-agnostic — it consumes from WalStore and writes to the three Vault
interfaces.

**Testing**: Spock 2.4 (Groovy 4.0.30) with JUnit Platform Launcher. Tests in
`alice-memory-vault/src/test/groovy/`.

**Target Platform**: JVM (part of `alice-memory-vault` module, no platform dep)

**Project Type**: Library component within an existing multi-module Gradle project

**Performance Goals**: Single Dreaming cycle on 20-step session completes in
<60s wall-clock; no measurable impact on online ReAct latency (P95 < 5% increase)

**Constraints**: Dreaming is pure async background — MUST NOT block online
ReAct execution. Session in DREAMING state is READ-locked from online plane.
Maximum one concurrent Dreaming cycle per session (deduplication).

**Scale/Scope**: Designed for sessions up to 1000 steps per cycle. Configurable
concurrency (default: 1 thread). WAL threshold trigger at configurable byte/entry
count.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Constitution Principle V — Observability & Secure Execution**: This feature
directly implements the constitutional mandate that "the memory-vault MUST use
WAL + Checkpoint dual-track persistence for crash recovery" by adding the
offline processing layer (Dreaming) that closes the feedback loop. Compliant.

**Constitution Principle I — Module-Separate Design**: All Dreaming components
live within `alice-memory-vault`, respecting existing module boundaries. No new
module needed. Compliant.

**Constitution Principle II — Java 25 + Spock Testing**: All new components
will have Spock tests. Existing test patterns (Groovy Spock specs in
`src/test/groovy/`) will be followed. Compliant.

**Constitution Principle IV — Documentation Discipline**: YAML front-matter,
CHANGELOG, and TODO updates will be included. Compliant.

## Project Structure

### Documentation (this feature)

```text
specs/001-dreaming-engine/
├── spec.md              # Feature specification
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── contracts/           # Phase 1 output (interface contracts)
├── checklists/          # Quality checklists
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
alice-memory-vault/src/main/java/org/cland/alice/memory/
├── dreaming/                    # NEW: Dreaming Engine package
│   ├── DreamingEngine.java      # Pipeline orchestrator
│   ├── ConflictResolver.java    # Knowledge conflict resolution
│   ├── Crystallizer.java        # SOP pattern crystallization
│   ├── DreamingTriggerConfig.java  # Trigger configuration
│   └── DreamingSession.java     # Cycle run-time record
├── wal/                         # Existing: WAL infrastructure
│   ├── WalSession.java          # May need: state transitions (COMPLETED → DREAMING → ARCHIVED)
│   ├── WalStore.java
│   ├── InMemoryWalStore.java
│   └── ...                      # Existing WAL + Checkpoint files
└── vault/                       # Existing: Vault interfaces
    ├── EpisodicVault.java
    ├── SemanticVault.java
    └── ProceduralVault.java

alice-memory-vault/src/test/groovy/org/cland/alice/memory/dreaming/
├── DreamingEngineSpec.groovy    # Pipeline orchestrator tests
├── ConflictResolverSpec.groovy  # Conflict resolution tests
├── CrystallizerSpec.groovy      # Pattern crystallization tests
└── IntegrationSpec.groovy       # End-to-end pipeline tests
```

**Structure Decision**: Single project (default). All new code goes into a new
`dreaming` package within the existing `alice-memory-vault` module. Tests follow
the existing `src/test/groovy/` convention.

## Complexity Tracking

> No constitution check violations — this feature is fully aligned with existing
> architecture. The new `dreaming` package within `alice-memory-vault` follows the
> established module pattern, and no new external dependencies are introduced.
