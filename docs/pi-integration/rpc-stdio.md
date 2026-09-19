---
title: "层① stdio RPC 协议（pi JSONL）"
summary: "pi RPC 模式的完整帧规范：启动参数、framing 规则、命令帧、事件帧、usage 累计与轮结束判定，附 alice-agent 对接要点"
read_when:
  - "实现或对接 stdio JSONL 的 agent RPC 接口"
  - "排查 RPC 事件流/帧解析问题"
scope:
  - "docs"
  - "alice-core-agent"
status: "active"
updated: "2026-09-19"
---
# 层① stdio RPC 协议（pi JSONL）

> pi 官方规范：`docs/rpc.md`（本机 `/mnt/data/npm/global/lib/node_modules/@earendil-works/pi-coding-agent/docs/rpc.md`）。
> 本文是"对接视角"的提炼，命令/事件全量表以官方文档为准。

## 1. 启动

```bash
pi --mode rpc [options]
# 我们的实际用法（经 pi-agent 角色包装 + 显式会话目录/会话名/技能）：
pi-agent souler --mode rpc --thinking low \
    --session-dir /mnt/data/soul/logs/pi_sessions \
    --session-id souler-20260919-112811 \
    --skill /home/alice/.agents/skills/soul-chat-ops
```

| 参数 | 说明 |
|---|---|
| `--mode rpc` | 进入 RPC 模式（stdin 命令 / stdout 事件） |
| `--thinking <low\|medium\|high>` | 思考等级 |
| `--session-dir <path>` | 会话文件目录（不设置则落默认目录） |
| `--session-id <name>` | 会话名；**不存在则创建**（我们一律配显式新名字） |
| `--skill <path>` | 挂载技能目录（可重复；渐进式披露，见 README §5 坑 8） |
| `--model <provider/id>` | 模型（多 provider 同名时必须带 provider 前缀） |
| `--no-session` | 禁用落盘（调试用，会丢工作记录 ✗） |

启动后 stderr 常出现一行 `Warning: No project session found with id '…'; creating a new session with that id.`
——**这是新会话 id 首次使用的正常提示**，不是故障。

## 2. Framing（帧格式）

- **严格 JSONL**：一行一个 JSON 对象，**只以 LF（`\n`）分帧**；
- 允许 `\r\n` 输入（剥掉行尾 `\r`）；
- **不要用"宽松行读取器"**：`U+2028/U+2029` 在 JSON 字符串里合法，Node `readline` 会误切分 ⇒ 不建议照搬；
- 命令可带 `id` 字段做请求/响应关联（响应会原样带回）。

## 3. 命令帧（stdin）

响应统一形如：

```json
{"id": "req-1", "type": "response", "command": "prompt", "success": true, "data": {...}}
```

`success: true` = 命令已受理/入队/立即执行；`false` = 受理前被拒。
**受理后的运行期失败不通过第二个 response 报告**，而是走事件流与消息流。

### 核心命令（常驻驱动只需这 5 个）

| 命令 | 帧 | 语义 |
|---|---|---|
| `prompt` | `{"id":"req-1","type":"prompt","message":"…"}` | 推一条用户消息；可带 `images:[{type,data,mimeType}]` |
| `steer` | `{"type":"steer","message":"…"}` | 忙时插队：当前助手回合的工具调用跑完后、下一次 LLM 调用前投递 |
| `follow_up` | `{"type":"follow_up","message":"…"}` | 排队到"agent 完全停下"后再投递 |
| `abort` | `{"type":"abort"}` | 中止当前操作并等待会话回到 idle（**会话保留**） |
| `new_session` | `{"type":"new_session"}` | 开新会话（进程不重启；可带 `parentSession`） |

**流式期间发 `prompt` 必须带 `streamingBehavior`**，否则报错：

```json
{"type":"prompt","message":"New instruction","streamingBehavior":"steer"}
```
- `"steer"`：当前工具调用结束后插入（推荐，等价人工插话）；
- `"followUp"`：等 agent 完全停下再投递。

### 其余命令（按需）

- 状态：`get_state` / `get_messages`；
- 模型：`set_model` / `cycle_model` / `get_available_models`；
- 思考：`set_thinking_level` / `cycle_thinking_level` / `get_available_thinking_levels`；
- 队列：`clear_queue` / `set_steering_mode` / `set_follow_up_mode`；
- 压缩：`compact` / `set_auto_compaction`；重试：`set_auto_retry` / `abort_retry`；
- Bash：`bash` / `abort_bash`（调试用；业务工具应走工具网关）；
- 会话：`get_session_stats` / `switch_session` / `fork` / `clone` / `get_entries` / `get_tree` /
  `get_last_assistant_text` / `set_session_name` / `export_html`；
- 命令发现：`get_commands`。

## 4. 事件帧（stdout）

### 事件类型全表

| 事件 | 说明 |
|---|---|
| `agent_start` | 开始处理一轮 |
| `agent_end` | 一次**底层**运行结束（`willRetry=true` 时后面还会自动重试） |
| `agent_settled` | **完全落定**：无重试、无压缩重试、无排队续跑 |
| `turn_start` / `turn_end` | 一个 turn（助手回复 + 工具调用及结果）始末 |
| `message_start` / `message_update` / `message_end` | 消息流式：`message_update` 是增量（delta），`message_end` 才是权威全文 |
| `tool_execution_start` / `_update` / `_end` | 工具执行（用 `toolCallId` 关联） |
| `bash_execution_update` | RPC 直连 bash 的输出块（带命令 `id`） |
| `queue_update` | 待投递 steer/followUp 队列变化 |
| `compaction_start` / `compaction_end` | 上下文压缩（自动/手动） |
| `auto_retry_start` / `auto_retry_end`、`summarization_retry_*` | 自动重试族 |
| `extension_error` | 扩展抛错 |

### 关键帧样例

```json
{"type":"tool_execution_start","toolCallId":"call_abc","toolName":"bash","args":{"command":"ls -la"}}
{"type":"tool_execution_end","toolCallId":"call_abc","toolName":"bash",
 "result":{"content":[{"type":"text","text":"…"}]},"isError":false}
{"type":"message_update","usage":{…},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Hello "}}
{"type":"agent_end","messages":[…],"willRetry":false}
```

## 5. 轮结束判定与 usage 累计（两个必踩点）

1. **"一轮结束"的判据**：参考实现当初用 `agent_end`；但官方语义里 `agent_end` 之后**可能仍有**
   重试 / 压缩重试 / 排队续跑。要"真正结束"应等 **`agent_settled`**（并保留 `agent_end` 兼容旧行为）。
   Alice 实现建议：对外 `prompt` 的完成 = `agent_settled`；`agent_end` 用于流式状态提示。
2. **usage 累计**：`message_update` / `message_end` / `turn_end` / `agent_end` 的 `message.usage`
   都带用量（`input/output/cacheRead/cacheWrite/totalTokens` + `cost.{input,output,…,total}`）。
   实现上应**按轮重置累加**，不要直接取最后一条（供应商流式期间可能给 0）。

## 6. 会话文件格式（落盘，供回放/排查）

`--session-dir` 下的 `<UTC ISO 时间戳>_<会话名>.jsonl`，逐行 JSON，常见行型：
`session`（元信息：id/version/cwd）→ `model_change` / `thinking_level_change` → `message`（user/assistant，
含 thinking 块与 usage）→ `toolResult`。**首轮消息进来后才创建文件**（0 轮时没有文件属正常）。
完整规范：pi `docs/session-format.md`。

## 7. 对接要点（Alice 实现清单）

- JSONL 解析：见 README §4.4（手动按 `\n` 切分，不用 `readLine` 宽松语义）；
- 事件 → 你们的 `EventStream`（thought/action/observe 语义可按 `message_update`/`tool_execution_*` 映射）；
- 命令 → `AgentCommand`：`prompt→ExecutionCmd`、`steer→ControlCmd(SteerCmd)`、`abort/new_session→ControlCmd`、
  `set_model→AlignmentCmd`；
- `usage` 按轮累计后送 `/health.last_round` 与埋点（`worker/round`）；
- 忙时语义（`streamingBehavior`）必须在 executor 层实现，不要只在 facade 排队。
