---
title: "alice-facade-cmd — CLI Facade Documentation"
summary: "Index of documentation for the Alice Agent CLI facade module — commands, design, build, e2e"
read_when:
  - "implementing or modifying CLI facade"
  - "debugging CLI invocation or argument parsing"
  - "running or writing CLI smoke tests"
scope:
  - "alice-facade-cmd"
status: "active"
updated: "2026-06-26"
---

# alice-facade-cmd Documentation Index

## Overview

`alice-facade-cmd` is the **command-line interface facade** of Alice Agent. It provides:

- `picocli`-based argument parsing for subcommands (`run`, `chat`, `tools`, `config`, `routine`, `sub-agent`)
- `JLine 3` interactive session (`alice chat`) with slash commands, history search, and Tab completion
- Config management via `~/.alice/config.json`
- E2E smoke test infrastructure for agent behavior validation

## Documents

| Document | Description |
|----------|-------------|
| [cmd.md](./cmd.md) | Full CLI command reference — all subcommands, flags, options, exit codes, examples |
| [DESIGN.md](./DESIGN.md) | Architecture design — entity/sequence/flow diagrams, data flow, state machine, use cases |
| [BUILD.md](./BUILD.md) | Build & run notes — how to run CLI mode, build JAR, SPI registration |
| [e2e/](./e2e/) | E2E smoke test cases and runner for CLI facade behavior |

## Quick Links

- **Run**: `./gradlew :alice-facade-cmd:run`
- **Build**: `./gradlew :alice-facade-cmd:jar`
- **Run smoke tests**: `python3 -m e2e.smoke.runner`
