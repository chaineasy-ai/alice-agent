---
title: "Technology Stack"
summary: "Key technologies and frameworks used in the Alice Agent project"
read_when:
  - "understanding the technology stack and dependencies"
  - "evaluating dependency versions or library choices"
  - "onboarding new contributors"
scope:
  - "alice-bootstrap"
  - "alice-core-agent"
  - "alice-core-planner"
  - "alice-model"
  - "alice-env-adapter"
  - "alice-tool-gateway"
  - "alice-guardrail"
  - "alice-memory-vault"
  - "alice-agent-command"
  - "alice-facade-cmd"
  - "alice-facade-tui"
status: "active"
updated: "2026-06-30"
---

# Technology Stack

## Core Language & Platform

| Technology | Version | Purpose |
|------------|---------|---------|
| **Java** | 25 | Primary language; all modules use Java 25 features (records, sealed classes, pattern matching) |
| **JPMS** | Java 25 module system | Modular architecture; each Alice module is a named module with explicit exports/requires |
| **Gradle** | 9.5 | Build system; multi-module project with toolchain `JavaLanguageVersion.of(25)` |
| **Groovy** | 4.0.30 | Test language (Spock specifications) |

## Graph & DAG Processing

| Technology | Version | Module | Purpose |
|------------|---------|--------|---------|
| **JGrapht** | 1.5.2 | `alice-memory-vault` | In-memory DAG for SOP (Standard Operating Procedure) workflows; provides `DirectedAcyclicGraph`, topological sort, graph traversal |
| **JGrapht IO** | 1.5.2 | `alice-memory-vault` | GraphML import/export for SOP graph persistence (`~/.alice/sops/*.graphml`) |
| **JGrapht Ext** | 1.5.2 | `alice-memory-vault` | Extended graph algorithms (future use) |
| **JGraphX** | 4.2.2 | `alice-memory-vault` | Graph visualization (mxGraph) — renders SOP DAGs as interactive diagrams; complements JGrapht's in-memory graph with a visual layer |

Key integration: `SopGraph` wraps JGrapht's `DefaultDirectedGraph<SopNode, SopEdge>` with SOP-specific node/edge types in `alice-memory-vault`'s `org.cland.alice.memory.sop` package. The SOP DAG is always in memory (JGrapht) — GraphML is used solely for save/restore, and JGraphX for optional visual rendering.

## AI / Model Integration

| Technology | Version | Module | Purpose |
|------------|---------|--------|---------|
| **OpenAI API** | — | `alice-model` | Default model provider (GPT-4o, GPT-4o-mini) |
| **Anthropic Claude API** | — | `alice-model` | Claude Sonnet/Opus integration via `ClaudeSupplier` |
| **ACP SDK** | 0.9.0 | `alice-core-agent` | Agent Communication Protocol — connect to external ACP-compliant agents |
| **PlannerModelSupplier** | internal | `alice-core-planner` | Model abstraction for planner decisions (reasoning vs instruction models) |

## Serialization & Data

| Technology | Version | Module | Purpose |
|------------|---------|--------|---------|
| **Jackson** | 2.18.3 | `alice-core-agent`, `alice-tool-gateway` | JSON serialization: WAL JSONL files, tool call schemas, messaging |
| **Gson** | 2.11.0 | `alice-model` | JSON parsing for model API responses |
| **GraphML** | 1.0 (XML) | `alice-core-planner` | SOP DAG persistence format via JGrapht IO |
| **FreeMarker** | 2.3.34 | `alice-core-agent` | Template engine for prompt templates |

## Memory & Vector Search

| Technology | Version | Module | Purpose |
|------------|---------|--------|---------|
| **JVector** | 4.0.0-beta.6 | `alice-memory-vault` | Embedding-based vector search for semantic memory (`JVectorSemanticVault`) |
| **Guava** | 33.6.0-jre | all modules | Concurrency utilities, caching (LoadingCache for tool/command caches), collections |

## CLI & TUI Frontends

| Technology | Version | Module | Purpose |
|------------|---------|--------|---------|
| **JLine 3** | 4.2.1 | `alice-facade-cmd` | Interactive CLI: line editing, history, tab completion, chat session |
| **Picocli** | 4.7.6 | `alice-facade-cmd` | CLI argument parsing, subcommands (`alice routine`, `alice chat`, etc.) |
| **Vert.x** | 5.0.8 | `alice-facade-tui` | Event bus, async I/O for TUI frontend |
| **JANSI** | 2.4.1 | `alice-facade-cmd` | ANSI escape codes for colored terminal output |
| **JNA** | 5.14.0 | `alice-facade-tui` | Native terminal operations (terminal size, raw mode) |

## Logging & Observability

| Technology | Version | Module | Purpose |
|------------|---------|--------|---------|
| **SLF4J** | 2.0.16 | all modules | Logging facade |
| **Logback Classic** | 1.5.16 | all modules | Logging implementation |
| **Logback Core** | 1.5.16 | all modules | Logging core |

## Testing

| Technology | Version | Module | Purpose |
|------------|---------|--------|---------|
| **Spock** | 2.4-groovy-4.0 | test | Specification-based testing (Groovy DSL) |
| **JUnit Platform** | 1.12.2 | test | Test launcher (Gradle integration) |
| **Mockito** | 5.17.0 | test | Mocking for Spock tests |
| **Spock Reports** | 2.5.1-groovy-4.0 | test | Test report generation |

## Internal Modules

| Module | Purpose |
|--------|---------|
| `alice-bootstrap` | JVM entry point, facade selection (TUI vs CLI) |
| `alice-core-agent` | Agent lifecycle, ReAct loop, WAL + Checkpoint, AgentExecutor, sub-agent management |
| `alice-core-planner` | Planning engine: MCTS ThinkingTree, StrategySelector, FastPath/SlowPath, PlannerService |
| `alice-model` | Model abstraction: ModelSupplier, OpenAI/Claude adapters, call/response |
| `alice-tool-gateway` | Tool execution: ToolRegistry, ExecutionEngine, sandbox, JSON schema generation |
| `alice-env-adapter` | Environment: MCP client (Stdio/SSE transport), shell execution |
| `alice-guardrail` | Security: Pre/Post validators, policy engine, hallucination detection |
| `alice-memory-vault` | Memory: Episodic/Procedural/Semantic vaults, JVector vector search, MemoryRouter, **SOP DAG (SopGraph/SopRegistry/StaticPlanner via JGrapht)** |
| `alice-agent-command` | Command model: sealed `AgentCommand` hierarchy with 6 branches |
| `alice-facade-cmd` | CLI frontend (Picocli + JLine 3) |
| `alice-facade-tui` | TUI frontend (Vert.x) |

## File Storage Conventions

| Path | Purpose |
|------|---------|
| `~/.alice/sops/*.graphml` | SOP DAG persistence (GraphML format via JGrapht) |
| `~/.alice/wal/` | Write-Ahead Log + Checkpoint files (JSONL format via Jackson) |
