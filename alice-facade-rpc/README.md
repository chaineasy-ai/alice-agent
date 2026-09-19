# alice-facade-rpc — HTTP RPC 2.0

**协议适配层**：把协议契约层（`alice-agent-proto`）的命令/事件以 **HTTP + SSE** 暴露给外部调度器、看板与其他 agent。
请求体 = **协议 v1 命令信封（与 stdio 同构）**；响应帧 = `StepEvent` v1（SSE `data:`）。**不依赖 core 实现**。

- **Gradle 工程**：`:alice-facade-rpc`｜**JPMS**：`alice.agent.facade.rpc.main`｜**包**：`org.cland.alice.facade.rpc`
- **设计文档**：[`docs/alice-facade-rpc/DESIGN.md`](../docs/alice-facade-rpc/DESIGN.md)｜**构建/冒烟**：[`docs/alice-facade-rpc/BUILD.md`](../docs/alice-facade-rpc/BUILD.md)
- **协议规范**：[`docs/alice-agent-proto/PROTOCOL.md`](../docs/alice-agent-proto/PROTOCOL.md)（帧唯一真源）

## 路由（v1）

| 方法 | 路由 | 命令信封 `type` | 语义 |
|---|---|---|---|
| POST | `/api/v1/command` | 任意 | 通用入口（执行类走 SSE，短命令收口后 JSON） |
| POST | `/api/v1/session` | `new` / `resume` | 新建/续接会话 |
| POST | `/api/v1/chat/stream` | `prompt` | 一轮任务 → `text/event-stream` |
| POST | `/api/v1/chat/steer` | `steer` | 人工插话（忙时插队）→ 202 |
| POST | `/api/v1/chat/interrupt` | `abort` | 中止当前轮（会话保留）→ 200 |
| GET | `/api/v1/health` | — | 宿主快照（sessionId/roundActive/rounds/usage） |

错误映射（协议 §4）：校验 **400**｜忙 **409**｜宿主未就绪 **503**｜其他 **500**。

## 用法

```java
var host   = new AgentHost(engine);          // alice-agent-runtime
var server = new RpcServer(8080);            // 或 new RpcServer(0) 取随机端口（测试）
server.start(host);
...
server.close();
```

```bash
# 一轮任务（SSE）
curl -N -X POST localhost:8080/api/v1/chat/stream -H 'Content-Type: application/json' \
  -d '{"v":1,"type":"prompt","sessionId":"sess-01","traceId":"t-1","payload":{"message":"ping"}}'

# 插话 / 中止 / 健康
curl -s -X POST localhost:8080/api/v1/chat/steer -d '{"v":1,"type":"steer","sessionId":"sess-01","traceId":"t-2","payload":{"message":"先回这条"}}'
curl -s -X POST localhost:8080/api/v1/chat/interrupt -d '{"v":1,"type":"abort","sessionId":"sess-01","traceId":"t-3"}'
curl -s localhost:8080/api/v1/health
```

## 实现说明

- **MVP 用 JDK 内置 `HttpServer`**（模块 `jdk.httpserver`，零三方依赖）——满足验收「新增协议适配器只依赖 proto + runtime」；
  Quarkus/Mutiny 仅在需要响应式背压/多租户时再引入（接口不变）。
- 命令解析/事件编码**统一走 proto codec**（禁止自定义 JSON 结构）。
- SSE 用虚拟线程阻塞等待收口；客户端断开 → 取消订阅（**不影响宿主轮次**）。
- 安全：默认只绑 `127.0.0.1`；对外暴露请置于网关/鉴权之后（v1 不内置鉴权）。

## 测试

```bash
./gradlew :alice-facade-rpc:test    # 11 例：health / SSE / steer / interrupt / 400 / 404 / 409 / 会话校验
```
