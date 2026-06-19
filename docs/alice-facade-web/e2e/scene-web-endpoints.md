---
title: "Hole Scene — alice-facade-web HTTP endpoints"
summary: "Module-level hole tests probing HealthController HTTP endpoint — health check, 404, CORS."
read_when:
  - "running or debugging hole tests for alice-facade-web"
scope:
  - "alice-facade-web"
status: "active"
updated: "2026-06-19"
---

# Hole Scene — alice-facade-web HTTP Endpoints

## 1. Scene Overview

3 hole probes into the `alice-facade-web` module.

**Case doc**: `docs/alice-agent-command/e2e/case-web.md`

## 2. Probe Map

```
┌─────────────────────────────────────┐
│         alice-facade-web            │
│                                     │
│  WEB-P01  GET /health → 200        │
│  WEB-P02  GET /unknown → 404       │
│  WEB-P03  OPTIONS /health → CORS   │
└─────────────────────────────────────┘
```

## 3. Prerequisites

The web server must be running. Start with:
```bash
# TBD: This module currently has no main class or boot script.
# Once the web server bootstrap is available, run:
# ./gradlew :alice-facade-web:run
```

## 4. How to Run

```bash
python docs/alice-facade-web/e2e/hole_test_web.py
```
