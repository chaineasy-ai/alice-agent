# Testing Guide — Alice Agent

This document describes how to run, write, and organize tests in the **Alice Agent**
multi-module Gradle project.

## Prerequisites

- **JDK 25+** (Temurin or equivalent).
- **Gradle 9.5** — use the provided wrapper (`./gradlew`).

## Test Framework

- **Spock 2.4** (Groovy 4.0.30) running on the JUnit Platform Launcher.
- Each module keeps its own tests under `src/test/groovy`.
- Tests follow the **Red → Green → Refactor** cycle.

## Running Tests

### Run the entire test suite
```bash
./gradlew check
```

### Run a single module's tests
```bash
./gradlew :alice-core-agent:test --tests "org.cland.alice.core.agent.*"
```

### Run a single test class
```bash
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec"
```

### Re-run only failed tests
```bash
./gradlew check --rerun-tasks
```

### Clean and build everything (includes tests and verification)
```bash
./gradlew clean build
```

## Formatting Checks

Formatting is validated as part of the review checklist. Run:
```bash
./gradlew spotlessCheck   # verify formatting
./gradlew spotlessApply   # auto-format
```
Formatting is also applied automatically on compile.

## End-to-End Tests

End-to-end tests live under [`e2e/`](./e2e/) and are written in **Python**. They
exercise the assembled distribution across the TUI and CLI frontends.

```bash
# Build the distribution first
./gradlew installDist

# Application binaries
# alice-bootstrap/build/install/alice/bin/
```

## Writing New Tests

- Add **Spock** (Groovy) tests for every new feature or bug fix.
- Place tests mirroring the production package structure under `src/test/groovy`.
- Follow Red → Green → Refactor.
- Cover edge cases explicitly:
  - **null safety**
  - **empty collections**
  - **timeouts**

### Example Spock specification
```groovy
package org.cland.alice.example

import spock.lang.Specification

class ExampleSpec extends Specification {

    def "handles an empty input collection"() {
        given:
        def subject = new ExampleService()

        when:
        def result = subject.process([])

        then:
        result.isEmpty()
    }

    def "returns null-safe result for null input"() {
        when:
        def result = new ExampleService().process(null)

        then:
        result == null
    }
}
```

## Review Checklist

- `./gradlew spotlessCheck` must pass.
- All tests from `./gradlew check` must succeed before merging.
- New Spock tests accompany any new feature or bug fix.
- Documentation (`AGENTS.md`, `README.md`, `TEST.md`, `todos/TODO-*.md`) is updated
  for user-facing changes.
- `module-info.java` exports/requires are correct when cross-module dependencies
  are added.

## Related Documentation

- [AGENTS.md](./AGENTS.md) — Contributor quickstart guide
- [README.md](./README.md) — Project overview and commands
- [TECH_STACK.md](./TECH_STACK.md) — Technology stack details
- [Spock Framework](https://spockframework.org/)
