---
title: "alice-agent-proto PROTOCOL（协议 v1 帧规范）"
summary: "协议契约层的线格式规范：命令信封（入方向）、StepEvent 事件帧（出方向）、错误映射、版本兼容规则与 pi 对接映射；传输层（stdio/HTTP/ACP）共同遵守"
read_when:
  - "实现或对接 transport（stdio JSONL / HTTP+SSE / ACP）"
  - "新增命令类型 / 事件类型 / 协议版本"
  - "排查帧解析、字段缺失、版本不兼容问题"
scope:
  - "alice-agent-proto"
status: "active"
updated: "2026-09-19"
---
# alice-agent-proto PROTOCOL（协议 v1）

> **唯一真源**：本文件定义线格式；Java 侧实现见 `codec/CommandCodec`、`codec/EventCodec`，
> 类型见 `AgentCommand`（入）与 `event.StepEvent`（出）。
> 传输（InProcess / stdio JSONL / HTTP+SSE / ACP）与门面**只准复用本规范，禁止自定义 JSON 结构**。

---

## 1. 分帧（Framing）

| 传输 | 分帧 | 规则 |
|---|---|---|
| stdio（RPC 模式） | JSONL | **只按 `\n` 切**；允许 `\r\n`（剥行尾 `\r`）；**不得**用会按 `U+2028/U+2029` 切分的宽松行读取器（Node `readline` 不合规） |
| HTTP | 请求体/响应体 | 单帧 JSON（`Content-Type: application/json`）；流式用 `text/event-stream`，每帧 `data: {…}\n\n` |
| 会话文件 | JSONL | 每行一帧（回放用） |

编码一律 UTF-8。

## 2. 命令帧（入方向）

统一信封（`codec.CommandEnvelope`）：

```json
{"v":1,"type":"prompt","sessionId":"s-01","traceId":"t-123-1789","ts":"2026-09-19T12:00:00+08:00","payload":{"message":"ping"}}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `v` | int | ✓ | 协议版本（当前 `1`） |
| `type` | string | ✓ | 命令类型（见下表） |
| `sessionId` | string | ✓ | 会话标识 |
| `traceId` | string | ✓ | 链路标识（同一次唤醒/同一轮共享） |
| `ts` | string | — | ISO-8601；缺省取接收时刻 |
| `payload` | object | — | 参数；未知字段忽略 |

### v1 覆盖表（只增不改）

| `type` | 映射 Java 类型 | payload | 语义 |
|---|---|---|---|
| `prompt` | `ExecutionCmd.AcquireGoalCmd` | `{"message":"…"}` | 一轮任务（自主循环） |
| `exec` | `ExecutionCmd.ExecuteRawCmd` | `{"command":"…"}` | 直接执行（不经规划） |
| `steer` | `ControlCmd.SteerCmd` | `{"message":"…"}` | **人工插话**：忙时插队（当前工具跑完后、下一次 LLM 调用前投递） |
| `abort` | `ControlCmd.AbortCmd` | `{}` | 中止当前轮（**会话保留**） |
| `new` | `ControlCmd.ResetSessionCmd` | `{}` | 新建会话（清上下文） |
| `resume` | `ControlCmd.ResumeSessionCmd` | `{"snapshotId":"…"?}` | 显式续接历史会话/快照 |
| `clear` | `ControlCmd.ClearContextCmd` | `{}` | 清上下文（保留 System Prompt/Rules） |
| `context` | `ControlCmd.ViewContextCmd` | `{}` | 查看上下文占用 |
| `compact` | `ControlCmd.CompactContextCmd` | `{}` | 压缩上下文 |
| `feedback` | `ControlCmd.FeedbackCmd` | `{"message":"…"}` | 人类在环反馈（解锁挂起） |

> 未覆盖（v1 编解码暂不支持，按版本纪律后续**追加**）：`CapabilityCmd` / `AlignmentCmd` /
> `RoutineTimeCmd` / `SubAgentCmd` 家族 —— 编码时抛 `CommandValidationException`。

## 3. 事件帧（出方向）

`codec.EventCodec` 序列化的 `event.StepEvent`：

```json
{"v":1,"type":"TOOL_CALL","sessionId":"s-01","traceId":"t-123-1789","seq":2,"ts":"2026-09-19T12:00:01+08:00",
 "payload":{"tool":"bash","toolCallId":"call-1","args":{"command":"ls"}},
 "usage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":0.0}}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `v` | int | ✓ | 协议版本（当前 `1`） |
| `type` | enum | ✓ | 见下表 |
| `sessionId` | string | ✓ | 会话标识 |
| `traceId` | string | ✓ | 链路标识 |
| `seq` | long | ✓ | 同一 `sessionId` 内**单调递增**（从 0 起），用于排序/断点续读 |
| `ts` | string | ✓ | ISO-8601 |
| `payload` | object | — | 按 `type` 定义；未知字段忽略 |
| `usage` | object | — | token/费用（见 §3.2） |

### 3.1 事件类型语义（`StepEventType`）

| type | 含义 | payload 建议字段 |
|---|---|---|
| `THOUGHT` | 推理/思考增量（可流式多发） | `text` |
| `TOOL_CALL` | 工具调用开始 | `tool`、`toolCallId`、`args` |
| `TOOL_RESULT` | 工具调用结束 | `tool`、`toolCallId`、`isError`、`result` |
| `OBSERVE` | 观察结果（环境/记忆/仲裁） | `summary` |
| `SUMMARY` | 面向用户的文本（可流式多发） | `text` |
| `DONE` | 一轮**完全落定**（无重试/压缩/排队续跑） | — |
| `ERROR` | 错误 | `message`、`stage` |

> `DONE` 对应 pi 的 **`agent_settled`**（不是 `agent_end`）——只有"真的不会再自动继续"才发 `DONE`。

### 3.2 usage 口径

```json
{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":0.0}
```
按**轮**累计（不是取最后一条增量；流式期间供应商可能给 0）；`cost` 单位美元。

## 4. 错误与状态映射

| 情形 | Java | HTTP | stdio |
|---|---|---|---|
| 字段缺失/非法/版本不支持 | `CommandValidationException` | `400` | 错误帧 `{"v":1,"type":"ERROR","payload":{"message":"…"}}`，命令不执行 |
| 同一会话一轮进行中 | `DispatcherBusyException` | `409` | 同上（调用方可改走 `steer`） |
| 轮内执行失败 | `StepEventType.ERROR` 帧 | `200`（流内 ERROR 帧） | ERROR 帧 |
| 宿主未就绪 | — | `503` | 进程退出/错误帧 |

## 5. 兼容规则（IDL 纪律）

1. **只增不改不删**字段；`type` 枚举只加不删不改名；
2. 消费者**必须忽略未知字段**（codec 已默认 `FAIL_ON_UNKNOWN_PROPERTIES=false`）；
3. 帧必须带 `v`；破坏性变更 **升 v2** 并保留 v1 解析 ≥1 个版本；
4. 新命令/事件先进本规范（PROTOCOL.md）+ `codec`，再进各 transport；
5. 端到端串联靠 `traceId`（同轮全部帧共享）+ `seq`（同会话排序）。

## 6. 与 pi 的映射（对接 pi 生态）

| 本协议 | pi（RPC / 事件） |
|---|---|
| `prompt` | `{"type":"prompt","message":…}`（忙时带 `streamingBehavior:"steer"`） |
| `steer` | `{"type":"steer","message":…}` |
| `abort` | `{"type":"abort"}`（会话保留 ✓） |
| `new` / `resume` | `new_session` / `switch_session` |
| `THOUGHT` | `message_update.thinking_delta` |
| `SUMMARY` | `message_update.text_delta` |
| `TOOL_CALL` | `tool_execution_start` |
| `TOOL_RESULT` | `tool_execution_end`（`isError` → `ERROR`） |
| `DONE` | `agent_settled` |
| `usage` | `usage.{input,output,cacheRead,cacheWrite,totalTokens}` + `cost.total` |

## 7. 示例

**stdio（一行命令 → 多行事件）**
```text
> {"v":1,"type":"prompt","sessionId":"s-01","traceId":"t-1","payload":{"message":"ping"}}
< {"v":1,"type":"THOUGHT","sessionId":"s-01","traceId":"t-1","seq":0,"ts":"…","payload":{"text":"…"}}
< {"v":1,"type":"SUMMARY","sessionId":"s-01","traceId":"t-1","seq":1,"ts":"…","payload":{"text":"pong"}}
< {"v":1,"type":"DONE","sessionId":"s-01","traceId":"t-1","seq":2,"ts":"…","payload":{},"usage":{"input":10,"output":1,"cacheRead":0,"cacheWrite":0,"totalTokens":11,"cost":0.0001}}
```

**HTTP（SSE 流式）**
```bash
curl -N -X POST localhost:8080/api/v1/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"s-01","prompt":"ping","traceId":"t-1"}'
# data: {"v":1,"type":"SUMMARY",…}
# data: {"v":1,"type":"DONE",…}
```

## 8. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-19 | v1 初版：命令信封 10 类、StepEvent 7 类、错误映射、兼容规则、pi 映射 |
