---
title: "层② 常驻 worker HTTP API 契约"
summary: "把 stdio RPC 收口成稳定 HTTP 面：/health /prompt /steer /abort /new_session /shutdown 的请求响应契约、轮次语义、自愈与调度集成"
read_when:
  - "实现 agent 的常驻 HTTP 对接层（worker）"
  - "写调度器侧客户端（健康检查/自愈拉起/轮次驱动）"
scope:
  - "docs"
  - "alice-core-agent"
  - "alice-facade-tui"
status: "active"
updated: "2026-09-19"
---
# 层② 常驻 worker HTTP API 契约

> 参考实现：`scripts/pi_worker.py`（服务端）、`src/infra/pi_client.py`（客户端）。
> 默认监听 `127.0.0.1:8094`（端口用环境变量 `SOUL_PI_WORKER_PORT` 可覆盖，**不要用 argv 覆盖端口**——
> 启动器/客户端都以环境变量为准，两边不一致会出现"健康检查查 8094、实际在 8095"的假故障）。

## 1. 端点总表

| 方法 | 路径 | 用途 | 幂等 |
|---|---|---|---|
| GET | `/health` | 健康 + 当前会话 + 最后一轮摘要 | ✓（可高频轮询，调度器 5s 一次） |
| POST | `/prompt` | 推一条事件并**等这一轮结束** | ✗（一轮一锁，见 §3） |
| POST | `/steer` | 人工插话（不占轮锁，忙时插队） | ✗ |
| POST | `/abort` | 中止当前轮（会话保留） | ✓ |
| POST | `/new_session` | 清上下文（进程不重启） | ✓ |
| POST | `/shutdown` | 优雅停机（关 agent 子进程 + 关 HTTP） | ✓ |

## 2. 契约明细

### 2.1 `GET /health`

```bash
curl -s 127.0.0.1:8094/health | python3 -m json.tool
```
```json
{
  "ok": true, "alive": true, "pi_pid": 54370,
  "session_id": "souler-20260919-112811",
  "streaming": false, "thinking": "low", "model": "",
  "uptime_s": 28297.6, "last_round_s": 1.19, "events": 600,
  "stderr_tail": "",
  "last_round": {"mode": "event", "seconds": 5.2, "tools": ["bash"],
                 "skills": [], "req": "…", "tokens": 496485, "cost": 0.0021,
                 "timeout": false, "ts": "09:41:42"}
}
```
要点：
- `alive` = **agent 子进程**是否存活（HTTP 服务活着 ≠ agent 活着；agent 死了下一次 prompt 会自动重拉）；
- `stderr_tail` = agent 子进程 stderr 末尾若干行（"进程在但已废"的第一诊断入口）；
- `events` = 事件环形缓冲条数（默认 600；满了滚旧）。

### 2.2 `POST /prompt`（一轮驱动，同步等结果）

```json
// 请求
{"message": "…事件文本…", "timeout": 60, "events": false,
 "trace_id": "t123-1789…", "subject": "uid或会话id", "mode": "event|dossier|tick"}
```
```json
// 响应
{"ok": true, "text": "…最终文本…", "seconds": 5.2,
 "session_id": "souler-…", "pi_pid": 54370, "streaming": false,
 "thoughts": "…思考摘要…", "stderr_tail": "", "timeout": false,
 "tools": ["bash"], "usage": {"input": 1, "output": 2, "totalTokens": 3, "cost": 0.002},
 "events": [ …仅 events=true 时带… ]}
```
语义：
- **同步**：调用方阻塞到「一轮结束」或超时；单轮预算常见 60s（首轮灌上下文 120s）；
- **超时**：服务端发 `abort`（**会话保留**），响应 `timeout=true`，调用方按"这轮没完成"处理；
- **忙**：拿不到轮锁（1s 内）直接返回 `{"ok": false, "error": "busy"}`，调用方自己决定重试/改投 `/steer`；
- `trace_id/subject/mode` 会随本轮所有埋点（工具/技能/轮次）落库，供全链路回放。

### 2.3 `POST /steer`（插话）

```json
// 请求
{"message": "【人工插话·优先遵从】…"}
// 响应
{"ok": true, "queued": true, "streaming": true}
```
语义：**不抢轮锁**。走 agent 的 steer 通道：
- agent 正在流式 → 带 `streamingBehavior=steer` 插队（当前工具跑完、下一次 LLM 调用前投递）；
- agent 空闲 → 等价一条新 prompt（排队执行）。
投递成功/失败都要落埋点（`human/steer`）并打日志。

### 2.4 `POST /abort` / `POST /new_session` / `POST /shutdown`

```json
{"ok": true}                              // /abort
{"ok": true, "success": true}             // /new_session（等待 response(command=new_session) 回执）
{"ok": true, "bye": true}                 // /shutdown（先关 agent 再停 HTTP，幂等）
```
`/shutdown` 用于"换会话/换参数"的优雅停旧：**先发它、轮询端口释放、再起新进程**；
不要直接 `kill -9`（agent 子进程可能残留）。

## 3. 轮次与并发语义（实现关键）

```
prompt()                             _reader() 线程（agent stdout JSONL）
  │ ① 取轮锁（同时只一轮）              │
  │ ② 重置本轮累加器（tools/usage/skills）
  │ ③ 写 stdin（单一写者锁） ─────────▶ │
  │ ④ 等 agent_end（或 agent_settled） ◀│ 消息流/工具事件/usage 持续累加
  │ ⑤ 超时 → 写 {"type":"abort"}       │
  └ ⑥ 汇总：text/tools/usage/skills → 响应 + 埋点 + last_round
```

- **单一写者**：所有 stdin 写入（prompt/steer/abort/new_session）经同一把写锁串行；
- **一轮一锁**：保证"响应里的 text/tools/usage 一定属于这一轮"；
- **事件环形缓冲**：应对"响应回了但要看细节"（可选 `events=true` 带回尾部）；
- **单轮一次 abort**：超时只中止当轮，会话继续可用。

## 4. 客户端行为（调度器侧）

| 客户端动作 | 行为 |
|---|---|
| `up()` | 探活 `/health`（短超时） |
| `ensure_up()` | 探活失败则 **detached 拉起** worker（`SOUL_PI_WORKER_AUTOSTART=0` 可关）；等 `/health` 就绪（默认 20s） |
| `ask(message, timeout, trace_id, subject, mode)` | POST `/prompt`（同步一轮） |
| `steer(message)` | POST `/steer` |
| `abort()` / `new_session()` | 对应端点 |
| 会话名继承 | **拉起新 worker 时主动剥离 `SOUL_PI_SESSION_ID`**（防"每次重拉都续接旧会话"，PIT-042 修复②） |

## 5. 调度集成（层③ 的最小形态）

- **唤醒口**：调度器监听本机 HTTP（参考 `:8093`），业务侧 `POST /wake {"steer": "…"}` 立即唤醒；
- **收件箱（可选但推荐）**：插话先落 DB `inbox(status='pending')`，调度器每轮消费后交给 `/steer`，
  再置 `delivered`——**签收权在最后一跳**（谁真正投给 agent 谁写 delivered，中间跳不得提前签收，PIT-049）；
- **人工插话优先级**：调度器每轮先派插话，再处理业务候选（人の指令优先）；
- **自愈**：调度器心跳 + 看门狗（定时探活，停摆则拉起）；worker 端口被占时**和平退出**。

## 6. 验收清单（curl 版）

```bash
curl -s 127.0.0.1:8094/health | python3 -m json.tool            # 会话名/agent pid/alive/stderr_tail
curl -s -X POST 127.0.0.1:8094/prompt -H 'Content-Type: application/json' \
     -d '{"message":"ping（只回复 pong，不要用工具）","timeout":60,"mode":"selfcheck"}' | python3 -m json.tool
curl -s -X POST 127.0.0.1:8094/steer -H 'Content-Type: application/json' \
     -d '{"message":"【人工插话】…"}'                             # 流式中应返回 queued/streaming=true
curl -s -X POST 127.0.0.1:8094/shutdown; sleep 2; ss -ltn | grep 8094   # 应无输出（已释放）
```
