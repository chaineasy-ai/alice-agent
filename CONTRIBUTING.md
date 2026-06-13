---
title: "Contributing to Alice Agent"
summary: "Contribution guidelines, development environment, and pull request process"
read_when:
  - "preparing a pull request"
  - "setting up development environment"
  - "understanding code style, testing, and commit conventions"
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
updated: "2026-06-13"
---
# Contributing to Alice Agent

This doc is intended for contributors to **Alice Agent** (hopefully that's you!)

## Development Environment

- **Java 25+** is required to run Gradle, compile the project, and run all tests.
- **Gradle 9.5** (wrapper provided via `./gradlew`).
- **Git** with proper commit message conventions.

## Build

```bash
./gradlew clean build
```

This will compile all modules, run all tests, and apply automatic code formatting.

## Code Formatting

Code is autoformatted using the **Spotless** plugin with **Google Java Format 1.28.0**. Formatting runs automatically during compilation — you don't need to run it manually unless you want to check before committing:

```bash
./gradlew spotlessApply
```

## Testing

This project uses **Spock 2.4** (Groovy 4.0.30) with **JUnit Platform** as the test engine.

### Run all tests
```bash
./gradlew check
```

### Run all tests (verbose)
```bash
./gradlew test
```

### Run tests for a specific module
```bash
./gradlew :alice-core-agent:test
```

### Run a single test class or wildcard
```bash
./gradlew :alice-core-agent:test --tests "org.cland.alice.core.agent.AgentSpec"
./gradlew :alice-core-agent:test --tests "org.cland.alice.core.agent.*"
```

Spock test reports (HTML) are generated to `build/reports/tests/` and also via the spock-reports extension configured in each module.

## Module Dependencies Overview

Module dependency graph (simplified):

```
alice-bootstrap
  ├── alice-facade-tui  ──┬── alice-core-agent ──┬── alice-core-planner
  │                        │                      ├── alice-guardrail
  │                        │                      ├── alice-tool-gateway
  │                        │                      ├── alice-memory-vault
  │                        │                      └── alice-env-adapter
  ├── alice-facade-cmd  ───┤
  ├── alice-model          │
  └── alice-agent-command  │
                           └── alice-model
```

- `alice-agent-command` is a lightweight library-only module (no transitive runtime deps on tool-gateway).
- All modules use JPMS (`module-info.java`) — adding cross-module dependencies requires updating exports/requires in module descriptors.
- `alice-env-adapter` depends on `alice-tool-gateway` for tool abstraction and uses Gson for MCP JSON-RPC.

## Running the Application

### TUI frontend (default)
```bash
./gradlew :alice-bootstrap:run
```

### CLI frontend
```bash
./gradlew :alice-facade-cmd:run
```

### Running the installed distribution
```bash
./gradlew installDist
./alice-bootstrap/build/install/alice-agent/bin/alice-agent
```

## Commit Messages

We follow the [Chris Beams](http://chris.beams.io/posts/git-commit/) guide to writing git commit messages:

- Separate subject from body with a blank line
- Limit subject line to 50 characters
- Capitalize the subject line
- Do not end the subject line with a period
- Use the imperative mood in the subject line
- Wrap the body at 72 characters
- Use the body to explain *what* and *why* (not *how*)

### Commit message structure:
```
<module>: <short description>

More detailed explanation wrapping at 72 characters.

Closes #123
Related-to: TODO-memory-vault.md
```

### Recommended prefixes:
| Prefix | Module |
|--------|--------|
| `bootstrap` | alice-bootstrap |
| `core-agent` | alice-core-agent |
| `planner` | alice-core-planner |
| `model` | alice-model |
| `env-adapter` | alice-env-adapter |
| `tool-gateway` | alice-tool-gateway |
| `guardrail` | alice-guardrail |
| `memory-vault` | alice-memory-vault |
| `agent-command` | alice-agent-command |
| `facade-cmd` | alice-facade-cmd |
| `facade-tui` | alice-facade-tui |
| `build` | Gradle/build config |
| `docs` | Documentation only |

## Pull Request Checklist

Before submitting a PR, ensure:

- [ ] `./gradlew spotlessCheck` passes (code formatting is clean)
- [ ] `./gradlew check` passes (all tests succeed)
- [ ] New Spock (or Groovy) tests are added for any new feature or bug fix
- [ ] `module-info.java` exports/requires are updated if cross-module API surface changed
- [ ] Documentation (AGENTS.md, module-internal README, etc.) is updated for user-facing changes
- [ ] Relevant `TODO-*.md` files under [`todos/`](./todos/) are updated (mark completed items, add new ones)
- [ ] `CHANGELOG.md` is updated under the appropriate version header
- [ ] No new public API surface is changed without discussion

## Pull Request Description

Every pull request should answer:

- **What changed?** — Summary of the changes
- **Why?** — Motivation and context
- **Breaking changes?** — Yes/No, with migration notes if applicable
- **Related issues/TODOs** — Links to relevant TODO files or issue numbers

## Code Style

- Use **Java 25** language features where appropriate (records, sealed classes, pattern matching, etc.)
- Follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) (enforced by Spotless)
- Prefer sealed interfaces over enums for extensible type hierarchies (e.g., `AgentCommand`)
- Use SLF4J for all logging — `LoggerFactory.getLogger(getClass())`
- Follow JPMS best practices: keep `public` API minimal, use `exports` precisely

## Getting Help

- See the [AGENTS.md](./AGENTS.md) for a quickstart overview
- See [project.tree](./project.tree) for full project structure
- See [docs/](./docs/) for design documentation
- See [`todos/`](./todos/) for work-in-progress and planned features
