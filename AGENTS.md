# Alice Agent — Contributor Quickstart Guide

**Alice Agent** is a modular Java agent framework designed as a multi-module Gradle project. It provides a complete agent lifecycle with core planning, memory management, environment adaptation (MCP), tool execution, guardrails, and dual frontends (TUI + CLI).

## Repository Layout

| Module | Description |
|--------|-------------|
| `alice-bootstrap` | JVM entry point (`AliceApp` → `AliceAgent`); orchestrates startup |
| `alice-core-agent` | Core agent lifecycle: `Agent`, `AgentContext`, `AgentExecutor`, ReAct loop |
| `alice-core-planner` | Planning service: `Plan`, `ThinkingTree`, strategies (FastPath/SlowPath), decision engine |
| `alice-model` | Model abstraction layer: `Model`, `ModelProvider`, `ModelSupplier` (OpenAI, Gemma4) |
| `alice-env-adapter` | Environment adapter: MCP client (`StdioMcpTransport`/`SseMcpTransport`), `EnvManager`, snapshots |
| `alice-tool-gateway` | Tool execution: `ToolRegistry`, `ExecutionEngine`, `SandboxProvider`, schema generation |
| `alice-guardrail` | Guardrail/validation: `PreValidator`, `PostValidator`, `PolicyEngine`, hallucination detection |
| `alice-memory-vault` | Memory management: episodic/procedural/semantic vaults, `MemoryRouter`, summarization |
| `alice-agent-command` | Sealed command interface: `AgentCommand`, `ControlCmd`, `ExecutionCmd`, `AlignmentCmd` |
| `alice-facade-cmd` | CLI frontend (picocli + JLine 3): `AliceCliLauncher`, `ExecutionCoordinator`, output renderers |
| `alice-facade-tui` | TUI frontend (JLine 3 + Vert.x): `AliceTuiLauncher`, `ChatComponent`, `InputComponent`, event bridge |

## General Guidance

- **Java 25** is required (toolchain `JavaLanguageVersion.of(25)`, release flag `25`). All modules use the Java Platform Module System (JPMS).
- **Test framework**: Spock 2.4 (Groovy 4.0.30) with JUnit Platform Launcher. Each module has its own test sources under `src/test/groovy`.
- **Code formatting**: Google Java Format 1.28.0 via Spotless 6.25.0. Formatting runs automatically on compile.
- **Logging**: SLF4J 2.0.16 + Logback 1.5.16 (classic + core).
- Internal modules should never leak public API changes without updating all callers. Anything under `internal` is not public API.
- Sealed interfaces (`AgentCommand`) should be preferred for extensible command patterns.

## Building and Testing

### Prerequisites
- JDK 25+ (temurin or equivalent)
- Gradle 9.5 (wrapper provided)

### Build the entire project
```bash
./gradlew clean build
```

### Run all tests
```bash
./gradlew check
```

### Run a single module's tests
```bash
./gradlew :alice-core-agent:test --tests "org.cland.alice.core.agent.*"
```

### Format code (applied automatically during compile)
```bash
./gradlew spotlessApply
```

### Run the application (both frontends available)

**CLI frontend:**
```bash
./gradlew :alice-facade-cmd:run
```

**TUI frontend (default):**
```bash
./gradlew :alice-bootstrap:run
```

### Build distribution archives
```bash
./gradlew assembleDist
```
Distribution archives are in `alice-bootstrap/build/distributions`.

### Install distribution (unpacked)
```bash
./gradlew installDist
```
Application binaries are at `alice-bootstrap/build/install/alice-agent/bin/`.

## Key Source Files

| File | Purpose |
|------|---------|
| `alice-bootstrap/.../AliceApp.java` | JVM entry point, lifecycle init |
| `alice-bootstrap/.../AliceAgent.java` | Agent orchestration |
| `alice-bootstrap/.../FacadeSelector.java` | Chooses TUI vs CLI based on args |
| `alice-core-agent/.../Agent.java` | Core agent interface |
| `alice-core-agent/.../lifecycle/ReAct.java` | ReAct loop (Observation → Action → Observation...) |
| `alice-core-planner/.../PlannerService.java` | Planning engine |
| `alice-model/.../supplier/OpenAiSupplier.java` | OpenAI model adapter |
| `alice-model/.../supplier/Gemma4Supplier.java` | Gemma 4 model adapter |
| `alice-env-adapter/.../McpClient.java` | MCP protocol client |
| `alice-env-adapter/.../transport/` | Stdio + SSE MCP transports |
| `alice-tool-gateway/.../ToolRegistry.java` | Central tool registry |
| `alice-guardrail/.../GuardrailService.java` | Validation pipeline |
| `alice-memory-vault/.../vault/` | Episodic, Procedural, Semantic vaults |
| `alice-memory-vault/.../router/MemoryRouter.java` | Memory routing and summarization |
| `alice-facade-tui/.../component/` | TUI components (Chat, Input, Header, Footer, Thought) |

## Commit Messages and Pull Requests

- Follow the [Chris Beams](http://chris.beams.io/posts/git-commit/) style for commit messages.
- Every pull request should answer:
  - **What changed?**
  - **Why?**
  - **Breaking changes?**
  - **Related TODO items** (see `TODO-*.md` files in project root)
- Comments should be complete sentences and end with a period.

## Review Checklist

- `./gradlew spotlessCheck` must pass.
- All tests from `./gradlew check` must succeed before merging.
- Add new Spock tests for any new feature or bug fix.
- Update documentation (AGENTS.md, README.md, TODO-*.md) for user-facing changes.
- Verify module-info.java exports/requires are correct if adding cross-module dependencies.

## TODO / Plan Tracking

Active work items are tracked in `TODO-*.md` files under [`todos/`](./todos/):
- [todos/TODO-alice-agent-command.md](./todos/TODO-alice-agent-command.md) — Command layer
- [todos/TODO-alice-facade.md](./todos/TODO-alice-facade.md) — Facade (TUI/CLI)
- [todos/TODO-memory-vault.md](./todos/TODO-memory-vault.md) — Memory subsystem
- [todos/TODO-spec.md](./todos/TODO-spec.md) — Specifications

## Additional Resources

- [project.tree](./project.tree) — Full project structure snapshot
- [TECH_STACK.md](./TECH_STACK.md) — Technology stack details
- [docs/](./docs/) — Design documentation
- [e2e/](./e2e/) — End-to-end tests (Python)
- `CHANGELOG.md` — Release history

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
specs/001-dreaming-engine/plan.md
<!-- SPECKIT END -->
