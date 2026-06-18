---
title: "CLI Facade Build"
summary: "Build and run notes for the CLI facade (alice-facade-cmd)"
read_when:
  - "running CLI mode"
  - "building CLI specifically"
  - "debugging CLI launch issues"
scope:
  - "alice-facade-cmd"
status: "active"
updated: "2026-06-19"
---

# CLI Facade — Build & Run

## Run CLI Mode

```bash
# Run directly (Gradle application plugin)
./gradlew :alice-facade-cmd:run

# Or via bootstrap with auto-detect
./gradlew :alice-bootstrap:run

# With explicit flag
./gradlew :alice-bootstrap:run --args="--facade cli"
```

## Build CLI JAR

```bash
./gradlew :alice-facade-cmd:jar
# → alice-facade-cmd/build/libs/alice-facade-cmd-0.1.0.jar
```

## CLI Commands

```bash
alice run "your task"
alice run "task" --model gpt-4o --verbose
alice chat
alice tools
alice tools --detail
alice config
alice config get default.model
alice config set openai.api_key sk-...
alice sub-agent spawn --goal "analyze logs"
alice sub-agent list
```

## SPI Registration

The CLI facade registers itself for `ServiceLoader` discovery:

- **File**: `META-INF/services/org.cland.alice.agent.spi.AliceFacade`
- **Class**: `org.cland.alice.facade.cmd.AliceCliFacade`
- **Name**: `"cli"`
