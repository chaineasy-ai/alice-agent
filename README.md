# Alice Agent

![GitHub Stars](https://img.shields.io/github/stars/chaineasy-ai/alice-agent.svg?style=flat-square)
![GitHub Forks](https://img.shields.io/github/forks/chaineasy-ai/alice-agent.svg?style=flat-square)
![License](https://img.shields.io/github/license/chaineasy-ai/alice-agent.svg)
![Last Commit](https://img.shields.io/github/last-commit/chaineasy-ai/alice-agent.svg)

[![Star History Chart](https://api.star-history.com/svg?repos=chaineasy-ai/alice-agent&type=Date)](https://star-history.com/#chaineasy-ai/alice-agent&Date)

A modular Java agent framework built with Gradle and Spock.

## Project Structure

See [project.tree](./project.tree) for the full project structure (generated via `struct` CLI).

## Tech Stack

See [TECH_STACK.md](./TECH_STACK.md) for details.

## Quick Start

```bash
# Build the entire project
./gradlew clean build

# Run tests
./gradlew check
```

## Generate Project Tree

```bash
struct -i ".git" -i ".gradle" -i "build" -i ".idea" -i ".lazybones" \
  -i "*.class" -i "*.jar" -i ".vscode" -i ".bevel" -i "docs" \
  -i "test" -i "resources" -i "logs" -i "specs" -i "e2e" -i "todos" \
  -i "bin" -i "dtcw" -i "*.bat" -i ".github" -i ".pi" -i ".specify" \
  -i ".gitignore" -i ".treerc" \
  -o project.tree
```

## Format Code

```bash
./gradlew spotlessApply
```

## Run the Application

### CLI Frontend
```bash
./gradlew :alice-facade-cmd:run
```

### TUI Frontend (default)
```bash
./gradlew :alice-bootstrap:run
```

## Build Distribution

### Packaged Distribution
```bash
./gradlew assembleDist
```
Distribution archives are in `alice-bootstrap/build/distributions`.

### Unpacked Application
```bash
./gradlew installDist
```
Application binaries are at `alice-bootstrap/build/install/alice-agent/bin/`.

### GraalVM Native Image

Build native executable:
```bash
./gradlew :alice-bootstrap:nativeCompile
```

Run native executable directly (fastest startup):
```bash
./gradlew :alice-bootstrap:nativeRun
```

Native binary is located in `alice-bootstrap/build/native/nativeCompile/`.

## Additional Information

- [AGENTS.md](./AGENTS.md) — Contributor quickstart guide
- [project.tree](./project.tree) — Full project structure
- [Spock Framework](https://spockframework.org/)
- [Gradle Build Tool](https://gradle.org/)
