---
title: "E2E Case — alice-facade-rpc endpoints"
summary: "Hole test specification for alice-facade-rpc module — HTTP health check, error handling, CORS."
read_when:
  - "implementing or modifying hole tests for alice-facade-rpc"
scope:
  - "alice-facade-rpc"
status: "active"
updated: "2026-09-19"
---

# E2E Case — alice-facade-rpc (Hole Test)

## 1. Purpose

Probe the **alice-facade-rpc** module's HTTP endpoint boundary — health check, error handling, and CORS policy.

## 2. Hole Design

```
HTTP GET  /api/v1/health ──► HealthController ──► 200（会话/流式/lastRound）
     ● (WEB-P01)
HTTP GET  /api/v1/unknown ──► 404
     ● (WEB-P02)
HTTP OPTIONS /api/v1/health ──► CORS headers
     ● (WEB-P03)
HTTP POST /api/v1/chat/steer（流式中）──► 202 {"queued":true,"streaming":true}
     ● (WEB-P04)
HTTP POST /api/v1/chat/interrupt ──► 200 {"ok":true}（会话保留）
     ● (WEB-P05)
```

## 3. Hole Tests

### WEB-P01: `GET /api/v1/health` returns 200

| Field | Value |
|-------|-------|
| **Target** | `HealthController` HTTP endpoint |
| **Input** | `GET http://localhost:PORT/api/v1/health` |
| **Expected** | HTTP 200，body 含 `sessionId`/`streaming`/`lastRound` 字段 |
| **Assertion** | `response.status == 200`，`"streaming" in response.json()` |

### WEB-P02: Unknown path returns 404

| Field | Value |
|-------|-------|
| **Input** | `GET http://localhost:PORT/api/v1/nonexistent` |
| **Expected** | HTTP 404 |
| **Assertion** | `response.status == 404` |

### WEB-P03: CORS headers present

| Field | Value |
|-------|-------|
| **Input** | `OPTIONS http://localhost:PORT/api/v1/health` with `Origin` header |
| **Expected** | Response includes `Access-Control-Allow-Origin` header |
| **Assertion** | `"Access-Control-Allow-Origin" in response.headers` |

### WEB-P04: `POST /api/v1/chat/steer` queues during streaming（2026-09-19 新增）

| Field | Value |
|-------|-------|
| **Input** | 流式轮进行中 `POST /api/v1/chat/steer {"sessionId":"…","text":"插一句"}` |
| **Expected** | HTTP 202，body `{"queued":true,"streaming":true}`；随后 SSE 流中可见插话注入后的续跑 |
| **Assertion** | `response.status == 202 and response.json()["queued"]` |

### WEB-P05: `POST /api/v1/chat/interrupt` aborts one round（2026-09-19 新增）

| Field | Value |
|-------|-------|
| **Input** | 流式轮进行中 `POST /api/v1/chat/interrupt {"sessionId":"…"}` |
| **Expected** | HTTP 200 `{"ok":true}`；当前轮终止但**会话保留**（紧接着 `/chat/stream` 可继续） |
| **Assertion** | `response.status == 200` 且随后一轮正常返回 |

> **Note**: These tests require the server to be running. 帧格式唯一真源见 `docs/alice-agent-proto/PROTOCOL.md`；
> 与 `docs/pi-integration/worker-http.md` 的契约同源（同命令/同语义，仅传输不同）。
