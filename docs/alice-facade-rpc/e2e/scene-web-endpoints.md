---
title: "Hole Scene — alice-facade-rpc HTTP endpoints"
summary: "Module-level hole tests probing the RPC facade endpoints — health, 404, CORS, steer, interrupt."
read_when:
  - "running or debugging hole tests for alice-facade-rpc"
scope:
  - "alice-facade-rpc"
status: "active"
updated: "2026-09-19"
---

# Hole Scene — alice-facade-rpc HTTP Endpoints

## 1. Scene Overview

5 hole probes into the `alice-facade-rpc` module（2026-09-19 补齐 steer/interrupt，路由统一 `/api/v1/`）。

**Case doc**: `docs/alice-facade-rpc/e2e/case-web.md`

## 2. Probe Map

```
┌──────────────────────────────────────────────┐
│              alice-facade-rpc                │
│                                              │
│  WEB-P01  GET  /api/v1/health → 200          │
│  WEB-P02  GET  /api/v1/unknown → 404         │
│  WEB-P03  OPTIONS /api/v1/health → CORS      │
│  WEB-P04  POST /api/v1/chat/steer → 202      │
│  WEB-P05  POST /api/v1/chat/interrupt → 200  │
└──────────────────────────────────────────────┘
```

## 3. Prerequisites

The web server must be running. Start with:
```bash
# TBD: This module currently has no main class or boot script.
# Once the web server bootstrap is available, run:
# ./gradlew :alice-facade-rpc:run
```

## 4. How to Run

```bash
python docs/alice-facade-rpc/e2e/hole_test_web.py
```
