---
title: "E2E Case — alice-bootstrap endpoints"
summary: "Hole test specification for alice-bootstrap module — FacadeSelector routing, AppBootstrapper lifecycle, IFacadeLauncher interface contract."
read_when:
  - "implementing or modifying hole tests for alice-bootstrap"
scope:
  - "alice-agent-command"
  - "alice-bootstrap"
status: "active"
updated: "2026-06-19"
---

# E2E Case — alice-bootstrap (Hole Test)

## 1. Purpose

Probe the **alice-bootstrap** module's public API boundary — facade selection, bootstrapper flow, and launcher interface contract.

## 2. Hole Design

```
String[] args ──► FacadeSelector.select() ──► IFacadeLauncher
                     ● (BTS-P01)
                         ─── "--tui" → AliceTuiLauncher
                         ─── default → AliceCliLauncher
raw args ──► AppBootstrapper.bootstrap() ──► void (no throw)
                ● (BTS-P02)
IFacadeLauncher ──► launch(String[]) ──► void
                     ● (BTS-P03)  ─── CLI + TUI both implement
```

## 3. Hole Tests

### BTS-P01: `FacadeSelector.select()` routing

| Field | Value |
|-------|-------|
| **Input** | `["--tui"]` and `["run", "hello"]` (no --tui) |
| **Expected** | `--tui` → AliceTuiLauncher instance, other → AliceCliLauncher instance |
| **Assertion** | `selector.select(["--tui"]) instanceof AliceTuiLauncher` |

### BTS-P02: `AppBootstrapper.bootstrap()` lifecycle

| Field | Value |
|-------|-------|
| **Input** | Valid args: `["run", "hello"]` |
| **Expected** | Bootstrap completes without exception, raw args passed to facade |
| **Assertion** | `bootstrap()` returns normally, no crash |

### BTS-P03: `IFacadeLauncher.launch()` interface contract

| Field | Value |
|-------|-------|
| **Input** | Verify both `AliceCliLauncher` and `AliceTuiLauncher` implement `IFacadeLauncher` |
| **Expected** | Both have `launch(String[])` method with identical signature |
| **Assertion** | `AliceCliLauncher implements IFacadeLauncher` |
