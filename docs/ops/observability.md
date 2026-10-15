---
title: "Alice Agent 可观测手册（M3-2）"
summary: "健康检查 /api/v1/health、基础指标 /api/v1/metrics、JSON Lines 结构化日志（info.log/error.log）与字段规范"
read_when:
  - "对接监控/采集或排查运行指标、日志格式时"
  - "了解 /health 与 /metrics 返回字段、日志目录与轮转策略时"
scope:
  - "alice-agent"
status: "active"
updated: "2026-10-16"
---
# Alice Agent 可观测手册（#173 M3-2）

> 出口：**`/health` + 结构化日志 + 基础指标**。

## 1. 健康检查 `/health`

RPC 形态（`--facade rpc`）提供：

```bash
curl -s http://127.0.0.1:8080/api/v1/health
```

返回宿主快照（`HostHealth`）：`sessionId` / `roundActive` / `rounds` / `lastRoundMs` / `lastUsage` / `startedAt`。

## 2. 基础指标 `/metrics`

```bash
curl -s http://127.0.0.1:8080/api/v1/metrics
```

```jsonc
{
  "ok": true,
  "sessionId": "…",
  "uptimeMs": 12614,          // 宿主运行时长
  "roundActive": false,
  "rounds": 0,                // 累计轮数
  "lastRoundMs": 0,           // 最近一轮耗时
  "tokensLastRound": 0,       // 最近一轮 token 用量
  "heapUsedBytes": 25676824,  // JVM 堆已用
  "heapMaxBytes": 4131389440, // JVM 堆上限
  "threads": 13               // 活跃线程数
}
```

> 指标由 `alice-agent-runtime` 的 `Metrics.collect(HostHealth)` 汇总（宿主字段 + JVM MXBean）。

## 3. 结构化日志（JSON Lines）

- **目录**：默认 `~/.alice/logs/`，可用环境变量 `ALICE_LOG_DIR` 覆盖；
- **文件命名**：对齐 `spec` 系统日志规范 —— **`info.log`**（INFO 及以上）、**`error.log`**（仅 ERROR）；
- **格式**：每行一个合法 JSON 对象（JSON Lines），SRE 采集器可直接透传解析；
- **轮转**：按天 + **50MB** 滚动，保留 **14 天**，单类总量上限 500MB。

**字段规范**（编码器 `JsonLogEncoder`）：

| 字段 | 说明 |
|---|---|
| `timestamp` | ISO8601（含时区偏移，毫秒） |
| `level` | 日志级别（INFO/WARN/ERROR/…） |
| `app` | 固定 `alice-agent` |
| `trace_id` | 链路 ID（来自 MDC；无则 `-`） |
| `thread` | 线程名 |
| `logger` | 日志器名 |
| `message` | 已格式化消息（转义为单行） |
| `exception` | 异常堆栈（存在异常时） |

**样例**：

```json
{"timestamp":"2026-10-16T06:27:23.665+08:00","level":"INFO","app":"alice-agent","trace_id":"-","thread":"main","logger":"org.cland.alice.agent.FacadeSelector","message":"Facade selected: rpc"}
```

- **控制台**保留人类可读 pattern（交互体验）；**文件**走结构化 JSON。
- 采集：`ALICE_LOG_DIR` 指向的 `info.log`/`error.log` 作为采集目标；`error.log` 可配全文异常告警。

## 4. 配置位置速查

| 项 | 位置 |
|---|---|
| 日志目录 | `~/.alice/logs/`（`ALICE_LOG_DIR` 可覆盖） |
| 健康端点 | `GET /api/v1/health` |
| 指标端点 | `GET /api/v1/metrics` |

## 5. 暴露面（发布前）

RPC 形态对外暴露的 HTTP 路由（协议面 `/api/v1`）：`GET /health` · `GET /metrics` · `POST /session` ·
`POST /chat/stream` · `POST /chat/steer` · `POST /chat/interrupt` · `POST /command`。

- **`/metrics` 为新增暴露面**：仅返回运行指标（轮次/耗时/用量 + JVM 堆/线程/运行时长），**不含敏感业务数据**；
- **发布前跑**：`./scripts/check.sh --stage all`（含 `exposure` 门禁 = `scripts/scan-exposure.sh --fail-on-high`，HIGH 未清零不得发布）；
- 暴露面扫描为**只读、不联网**，规则集见 `scripts/scan-exposure.sh` 头部。
