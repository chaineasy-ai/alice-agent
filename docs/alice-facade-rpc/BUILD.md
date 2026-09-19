---
title: "alice-facade-rpc Build & Run（HTTP RPC 2.0）"
summary: "构建与运行 alice-facade-rpc：JDK HttpServer（零三方依赖）承载协议信封，5 路由 + SSE；依赖 proto+runtime，不碰 core"
read_when:
  - "运行或调试 RPC 门面"
  - "构建/部署 HTTP RPC 2.0"
scope:
  - "alice-facade-rpc"
status: "active"
updated: "2026-09-19"
---
# alice-facade-rpc — Build & Run

> **协议适配层**（不是 Web UI）。请求体 = 协议 v1 命令信封（与 stdio 同构）；设计见 [DESIGN.md](./DESIGN.md)。

## 运行（MVP：JDK HttpServer）

```java
// 组合根（bootstrap / 自定义 main）：
var host   = new AgentHost(engine);     // alice-agent-runtime
var server = new RpcServer(8080);       // new RpcServer(0) = 随机端口（测试）
server.start(host);
Runtime.getRuntime().addShutdownHook(new Thread(server::close));
```

- 依赖仅 `alice-agent-proto` + `alice-agent-runtime`（**不依赖 core**）；
- 零三方依赖：JDK 内置 `jdk.httpserver` + 虚拟线程；响应式升级（Quarkus/Mutiny）留作后续。

```bash
# 测试（含端口随机的端到端 HTTP 用例）
./gradlew :alice-facade-rpc:test
```

## 端点（v1）

| 方法 | 路由 | 信封 `type` | 响应 |
|---|---|---|---|
| POST | `/api/v1/command` | 任意 | 执行类 → SSE；短命令 → JSON |
| POST | `/api/v1/session` | `new` / `resume` | `{"ok":true}` |
| POST | `/api/v1/chat/stream` | `prompt` | `text/event-stream`（StepEvent v1） |
| POST | `/api/v1/chat/steer` | `steer` | `202 {"ok":true,"queued":true}` |
| POST | `/api/v1/chat/interrupt` | `abort` | `200 {"ok":true}` |
| GET | `/api/v1/health` | — | 宿主快照 JSON |

## 冒烟

```bash
BASE=http://127.0.0.1:8080/api/v1
curl -s $BASE/health

# 一轮任务（SSE：data: {StepEvent v1}，以 DONE 收口）
curl -N -X POST $BASE/chat/stream -H 'Content-Type: application/json' \
  -d '{"v":1,"type":"prompt","sessionId":"sess-01","traceId":"t-1","payload":{"message":"ping"}}'

# 人工插话 / 中止
curl -s -X POST $BASE/chat/steer -H 'Content-Type: application/json' \
  -d '{"v":1,"type":"steer","sessionId":"sess-01","traceId":"t-2","payload":{"message":"先回这条"}}'
curl -s -X POST $BASE/chat/interrupt -H 'Content-Type: application/json' \
  -d '{"v":1,"type":"abort","sessionId":"sess-01","traceId":"t-3"}'
```

## 依赖（依赖倒置）

- **`alice-agent-proto`**：命令/事件/端口/codec（唯一契约真源）
- **`alice-agent-runtime`**：`AgentCommandDispatcher` 实现（`AgentHost`）与 `HostHealth`
- **禁止依赖 `alice-core-agent`**：core 只被 runtime 依赖
