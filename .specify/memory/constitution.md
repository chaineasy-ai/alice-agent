<!--
Sync Impact Report

Version change: N/A → 1.0.0 (initial constitution fill)
Modified principles: N/A (new constitution)
Added sections: All 5 core principles, Technology Stack & Constraints, Development Workflow, Governance
Removed sections: N/A

Templates requiring updates:
  ✅ .specify/templates/plan-template.md — Constitution Check section already uses generic "Gates determined based on constitution file" — no changes needed
  ✅ .specify/templates/spec-template.md — No mandatory constitution-referenced sections added that would require changes
  ✅ .specify/templates/tasks-template.md — No principle-driven task type changes needed
  ✅ .specify/templates/checklist-template.md — Generic checklist, no changes needed
  ✅ AGENTS.md — Already reflects current project structure; no outdated references to constitution principles
  ⚠ TODO(RATIFICATION_DATE): Original ratification date unknown — set as TODO in Governance section

Follow-up TODOs:
  - TODO(RATIFICATION_DATE): Determine original adoption date from project history and fill in
-->

# Alice Agent Constitution

## Core Principles

### I. Module-Separate Design

Every functional component MUST be a standalone Gradle module with its own
`module-info.java` using the Java Platform Module System (JPMS). Internal
packages MUST NOT leak as public API — use `exports` precisely for the minimum
surface area. Sealed interfaces (e.g., `AgentCommand`) MUST be preferred over
enums for extensible type hierarchies. Each module MUST be independently
testable via Spock without requiring the full application runtime.

**Rationale**: 11 modules already enforce this pattern (bootstrap, core-agent,
core-planner, model, env-adapter, tool-gateway, guardrail, memory-vault,
agent-command, facade-cmd, facade-tui). Breaking this principle causes circular
dependencies and untestable code — the tool-gateway/alice-core-agent decoupling
via `Map` interfaces is the canonical success story.

### II. Java 25 + Spock Testing

Java 25 language features — records, sealed classes, pattern matching, and
text blocks — MUST be used pervasively where appropriate. Testing MUST use
Spock 2.4 (Groovy 4.0.30) with JUnit Platform Launcher. Every module MUST
have at least contract tests; integration tests MUST cover inter-service
communication and inter-module contracts. Tests MUST be written before
implementation (test-first discipline: write → verify failure → implement →
verify pass → refactor).

**Rationale**: Java 25 features (records for value objects, sealed interfaces
for command hierarchies) are core architectural decisions. Spock provides
readable behavioral specifications that serve as living documentation.

### III. CI-Code Quality Gates (NON-NEGOTIABLE)

The following gates MUST pass before any merge into the main branch:
- `./gradlew spotlessCheck` MUST pass (Google Java Format 1.28.0 enforced
  by Spotless 6.25.0)
- `./gradlew check` MUST pass (all Spock tests succeed)
- No compilation warnings in any module
- SLF4J 2.0 + Logback 1.5 MUST be used for all logging — no `System.out` or
  `System.Logger` calls in production code
- Cross-module dependency changes MUST update `module-info.java` exports/requires

**Rationale**: Automated quality gates catch regressions early. The migration
from `System.Logger` to SLF4J across 28 files in 10 modules proved this
principle's value.

### IV. Documentation Discipline

All project `.md` files MUST include YAML front-matter headers per the
`DOC_SPEC.md` standard (`title`, `summary`, `read_when`, `scope`, `status`,
`updated` fields). CHANGELOG.md MUST follow the structured format with dated
sections and categorized entries (Changes / Fixes / Breaking Changes / Docs /
Tests / Build). Active work items MUST be tracked in GFM-format TODO task
boards under `todos/`. Commit messages MUST follow the Chris Beams style
(50/72 rule, imperative mood, module prefix).

**Rationale**: The 35-file documentation standardization effort established
this as a project-wide expectation. AI-agent-readable metadata in every `.md`
file enables automated context retrieval.

### V. Observability & Secure Execution

All production code MUST use SLF4J 2.0 + Logback 1.5 for structured logging
with configurable log levels. The GuardrailService MUST perform pre-execution
(permission sandbox, hallucination detection) and post-execution (logic sanity,
policy engine) validation on every action. The env-adapter MUST capture
environment snapshots before execution and support automatic rollback on
failure. The memory-vault MUST use WAL + Checkpoint dual-track persistence
for crash recovery.

**Rationale**: Security and debuggability are non-negotiable in an agent
framework that executes arbitrary tool calls and LLM inferences. The
guardrail/env-adapter/memory-vault triad provides defense in depth.

## Technology Stack & Constraints

### Runtime Requirements

- **Java 25+** JDK (toolchain `JavaLanguageVersion.of(25)`, release flag `25`)
- **Gradle 9.5** (wrapper provided via `./gradlew`)
- **JPMS**: All modules use `module-info.java` — no classpath-only modules

### Core Dependencies

| Dependency | Version | Module(s) |
|---|---|---|
| Spock 2.4 + Groovy 4.0.30 | 2.4-groovy-4.0 | All test sources |
| JUnit Platform Launcher | 1.11.x | Test runtime |
| SLF4J + Logback | 2.0.16 / 1.5.16 | All production modules |
| Spotless (Google Java Format) | 6.25.0 / 1.28.0 | Build plugin |
| picocli | 4.7.6 | alice-facade-cmd |
| JLine 3 | 3.27.1 | alice-facade-tui |
| Vert.x | 5.0.8 | alice-facade-tui, alice-core-agent |
| Gson | Latest stable | alice-env-adapter |
| Jackson (databind, jsr310) | 2.18.3 | alice-tool-gateway |
| Guava | Latest stable | alice-tool-gateway, alice-env-adapter |

### Build Artifacts

- Distribution archives: `alice-bootstrap/build/distributions/`
- Installed application: `alice-bootstrap/build/install/alice-agent/bin/alice-agent`
- Test reports: `build/reports/tests/` per module

## Development Workflow

### Feature Lifecycle

1. **Specification**: Create feature spec in `/specs/[###-feature]/spec.md` with
   user stories (prioritized P1, P2, P3...) and acceptance scenarios
2. **Planning**: Generate implementation plan via `/speckit.plan` with
   Constitution Check gate
3. **Tasking**: Generate task breakdown via `/speckit.tasks` organized by user
   story
4. **Implementation**: Follow task ordering — foundation first, then user
   stories in priority order
5. **Testing**: Write Spock tests before implementation (test-first); contract
   tests for inter-module boundaries; integration tests for end-to-end flows
6. **Review**: Submit PR with checklist — `spotlessCheck`, `check`, module-info
   hygiene, doc updates, CHANGELOG entry
7. **Merge**: Only after all gates pass and at least one reviewer approves

### Quality Gates

- `./gradlew spotlessCheck` MUST pass
- `./gradlew check` MUST pass
- CHANGELOG.md MUST be updated under the appropriate date section
- TODO files in `todos/` MUST be updated (completed items marked, new items added)
- module-info.java exports/requires MUST be updated for cross-module changes

### Post-Merge

- Distribution builds via `./gradlew assembleDist` or `./gradlew installDist`
- E2E tests in `e2e/` may be run against installed distribution

## Governance

The Constitution supersedes all other project practices. Amendments to this
constitution require:
1. A documented rationale for the change
2. A version bump following semantic versioning rules:
   - MAJOR: Backward incompatible governance/principle removals or redefinitions
   - MINOR: New principle/section added or materially expanded guidance
   - PATCH: Clarifications, wording, typo fixes, non-semantic refinements
3. A migration plan if the amendment deprecates existing practices
4. All PRs/reviews MUST verify constitution compliance before merging
5. Complexity MUST be justified in the plan.md "Complexity Tracking" section
   when constitution check violations occur

**Version**: 1.0.0 | **Ratified**: TODO(RATIFICATION_DATE): original adoption date unknown — determine from project history | **Last Amended**: 2026-06-13
