---
title: "E2E Case — alice-facade-web endpoints"
summary: "Hole test specification for alice-facade-web module — HTTP health check, error handling, CORS."
read_when:
  - "implementing or modifying hole tests for alice-facade-web"
scope:
  - "alice-agent-command"
  - "alice-facade-web"
status: "active"
updated: "2026-06-19"
---

# E2E Case — alice-facade-web (Hole Test)

## 1. Purpose

Probe the **alice-facade-web** module's HTTP endpoint boundary — health check, error handling, and CORS policy.

## 2. Hole Design

```
HTTP GET /health ──► HealthController ──► 200 + {"status":"UP"}
     ● (WEB-P01)
HTTP GET /unknown ──► 404
     ● (WEB-P02)
HTTP OPTIONS /health ──► CORS headers
     ● (WEB-P03)
```

## 3. Hole Tests

### WEB-P01: `GET /health` returns 200

| Field | Value |
|-------|-------|
| **Target** | `HealthController` HTTP endpoint |
| **Input** | `GET http://localhost:PORT/health` |
| **Expected** | HTTP 200, body contains `"status": "UP"` or similar |
| **Assertion** | `response.status == 200`, `"UP" in response.text` |

### WEB-P02: Unknown path returns 404

| Field | Value |
|-------|-------|
| **Input** | `GET http://localhost:PORT/nonexistent` |
| **Expected** | HTTP 404 |
| **Assertion** | `response.status == 404` |

### WEB-P03: CORS headers present

| Field | Value |
|-------|-------|
| **Input** | `OPTIONS http://localhost:PORT/health` with `Origin` header |
| **Expected** | Response includes `Access-Control-Allow-Origin` header |
| **Assertion** | `"Access-Control-Allow-Origin" in response.headers` |

> **Note**: These tests require the web server to be running. Currently the web facade is minimal (only `HealthController.java`). A lightweight test harness (e.g. `@QuarkusTest` or embedded Undertow) will be needed.
