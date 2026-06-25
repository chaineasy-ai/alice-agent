---
title: "Memory Vault - WAL & Checkpoint"
summary: "Dual-track (WAL + Checkpoint) design for industrial-grade agent memory"
read_when:
  - "implementing or debugging WAL/Checkpoint dual-track memory"
scope:
  - "alice-memory-vault"
status: "active"
updated: "2026-06-23"
---
将传统数据库/流处理的高性能 **双轨制（WAL + Checkpoint）** 引入 AI Agent 的记忆系统，是目前解决 Agent 工业级落地最硬核的解法。

在这一套全新设计的架构中：

* **`RawMessage` 充当 WAL（预写日志）**：只做顺序追加，记录最原始的、未经修剪的客观交互流（包括环境输入、Tool 原始返回、大模型原生 Raw 输出，以及 `<thought>` 标签包裹的推理链）。
* **`Checkpoint` 充当控制快照**：定期或在特定边界（Safe Point）触发，用来冷冻当前的控制流节点、Plan 任务树树状图以及局部变量，同时更新 `Last_Applied_Message_ID`（最后应用的消息指针）。

---

## 1. 双轨制存储与指针对齐拓扑 (ASCII)

在这套架构下，数据在数据库中呈现出"一条流水线（WAL）"与"一个个静止锚点（Checkpoint）"的强对齐关系。

```text
========================================================================================
【 WAL + CHECKPOINT DOUBLE-TRACK TOPOLOGY / 双轨对齐拓扑 】
========================================================================================

 【 轨道一：RawMessage 流水线 (WAL Log - Append Only) 】

        USER                          ASSISTANT                       TOOL
   ┌──────────────┐           ┌─────────────────────┐        ┌──────────────────┐
   │ "修复 divide"│ ────────► │ <thought>分析代码...│ ──────► │ def divide(...)   │
   │              │           │ tool_calls:         │        │     return a/b   │
   └──────┬───────┘           │   read_file("...")  │        └────────┬─────────┘
          │                   └──────────┬──────────┘                 │
          │                              │                            │
          ▼                              ▼                            ▼
   ┌──────────────────────────────────────────────────────────────────────────┐
   │  Msg_ID: 1       Msg_ID: 2         Msg_ID: 3       Msg_ID: 4            │
   │  [USER]          [ASSISTANT]        [TOOL]          [ASSISTANT]          │
   │  content=...     content="<thought>  rawData=       content="已修复..."   │
   │                  ...</thought>"     文件内容         tool_calls:          │
   │                                     或命令输出        write_file(...)     │
   └──────────────────────────────────────────────────────────────────────────┘
                               ▲
                               │
          [Checkpoint: CP_A]   │
          ├─ stateNode = "FINISHED"
          ├─ variables = { iteration, phase, reason, result, ... }
          └─ Last_Applied_ID = 4 ──────────┘

```

### 存储布局

WAL 数据以 JSONL（每行一个 JSON 对象）格式持久化到磁盘：

```
~/.alice/wal/
  ├── <sessionIdHash>/
  │   ├── <sessionId>.wal.jsonl       — WAL 消息（Append-Only）
  │   ├── <sessionId>.checkpoint.json — 最新 Checkpoint（覆盖写入）
  │   └── _seq                        — 消息 ID 序号
```

---

## 2. WAL 消息格式（RawMessage）

每条 WAL 消息遵循 OpenAI Chat Completions 消息对象规范，支持纯文本、推理链、工具调用三种场景。

### 角色定义

| 角色 | 说明 | 内容规范 |
|------|------|----------|
| `user` | 用户输入 | 原始任务描述文本 |
| `assistant` | LLM 回复 | 文本内容 + 可选 `tool_calls`；推理链用 `<thought>...</thought>` 包裹 |
| `tool` | 工具执行结果 | 原始返回数据（文件内容、命令输出、搜索结果等） |
| `compact` | 压缩摘要 | 历史对话的语义摘要 |

### 示例数据

```json
// 用户输入
{"messageId":1, "role":"user",
 "content":"修复 e2e/smoke/fixtures/math_utils/math_utils.py 中的 divide 函数。当除数为 0 时抛出 ValueError。"}

// LLM 回复（含推理链和工具调用）
{"messageId":2, "role":"assistant",
 "content":"<thought>我需要先读取文件，然后修改 divide 函数。</thought>",
 "toolCalls":[{"id":"call_001","type":"function",
   "function":{"name":"read_file","arguments":"{\"path\":\"e2e/smoke/fixtures/math_utils/math_utils.py\"}"}}]}

// 工具执行结果（原始数据）
{"messageId":3, "role":"tool",
 "content":"\"\"\"Simple math utilities.\"\"\"\n\ndef divide(a: float, b: float) -> float:\n    return a / b",
 "toolCallId":"call_001"}

// LLM 回复（纯文本，任务完成）
{"messageId":4, "role":"assistant",
 "content":"已修复 divide 函数，现在除零时会抛出 ValueError。",
 "toolCalls":[]}
```

### 设计要点

- **Tool 结果保存原始数据**：工具执行结果记录的是实际返回内容（文件内容、命令输出），而非状态摘要字符串。这使 WAL 重放后 Agent 能准确知道之前看到了什么。
- **推理链用 `<thought>` 包裹**：LLM 的 `reasoning_content` 在写入 WAL 前被提取并用 `<thought>...</thought>` 包裹。这样在恢复重放时，Agent 的推理上下文不会丢失。
- **跳过空消息**：完全空的 assistant 消息（无内容 + 无 tool_calls）被跳过，避免 WAL 被无意义记录污染。
- **消息链路完整性**：每个 `assistant.tool_calls` 都有一个对应的 `tool` 响应消息配对，通过 `toolCallId` 关联。

---

## 3. Checkpoint 安全边界与触发时机

Checkpoint 在以下 Safe Point 触发，每次触发会冷冻当前控制流节点、变量快照和消息 ID 指针。

```text
========================================================================================
【 SAFE POINT / CHECKPOINT TRIGGER MAP 】
========================================================================================

  PPAO Phase            Safe Point                  stateNode            Variable Snapshot
 ────────────────────────────────────────────────────────────────────────────────────────
  PERCEIVE       收到用户输入                      "PERCEIVING"          { input_length, iteration }

  ACT (Micro)    工具调用返回                      "ACTING"              { lastTool, toolSuccess, iteration }

  ACT (Micro)    Micro-ReAct 熔断（错误）          "ERROR"               { errorNode, error }

  OBSERVE        每个 Macro ReAct 循环结束        <current_phase>       { iteration, phase, messageCount, ... }

  REFLECT        收到 FINISH action               "FINISHED"            { reason: "finish_action", result, iteration }

  REFLECT        达到最大迭代次数                  "FINISHED"            { reason: "max_iterations", iteration }

  LOOP_BODY      正常结束（shouldFinish=true）     "FINISHED"            { reason: "normal_finish", iteration }

  FATAL          未捕获异常                        "ERROR"               { error, errorNode }
```

### 变量快照内容

每次 Checkpoint 保存的 `variableSnapshot` 包含：

```json
{
  "iteration": 3,           // PPAO 宏观迭代次数
  "phase": "ACTING",        // 当前 PPAO 阶段
  "reason": "finish_action", // 到达当前节点的原因
  "result": "已修复...",     // 最终结果（仅 FINISHED）
  "lastTool": "write_file", // 最近一次工具调用（仅 ACTING）
  "toolSuccess": true,      // 最近工具是否成功（仅 ACTING）
  "error": "...",           // 错误信息（仅 ERROR）
  "messageCount": 42,       // 当前 WAL 消息总数
  "checkpointTime": ...     // 时间戳
}
```

### 幂等性保证

同一 Safe Point 的重复触发（相同 `lastAppliedMessageId` + 相同 `stateNode`）会被跳过。但**不同状态节点之间不幂等**——例如 `ERROR → FINISHED` 的过渡即使消息 ID 相同也会写入不同的 Checkpoint。这确保了崩溃恢复时总能拿到最新的状态节点信息。

### 终点状态

正常完成的 Agent 会话，其最终 Checkpoint 的 `stateNode` 为 `FINISHED`。若为 `ERROR` 则表示会话在中途异常终止（如 Micro-ReAct 熔断、未捕获异常），恢复流程应从此处开始重放脏 WAL。

---

## 4. PromptMelter：双轨上下文 → LLM Prompt 熔炼

`PromptMelter`（代码：`org.cland.alice.memory.wal.PromptMelter`）是 WAL + Checkpoint 双轨数据到 LLM 请求 prompt 的转换器。它从 WAL 中提取最近对话、从 Checkpoint 中提取结构化状态，与静态主干拼接为三段式 prompt，最大化 DeepSeek 的 Disk Prompt Cache 命中率。

### 三段式 Prompt 结构

```text
========================================================================================
【 DOUBLE-TRACK PROMPT MELTER / 双轨上下文大熔炉 】
========================================================================================

大模型单次请求上下文窗口 (Context Window)
┌──────────────────────────────────────────────────────────────────────────────────────┐
│ 【 1. 静态主干区：100% 命中缓存 】                                                    │
│ ──────────────────────────────────────────────────────────────────────────────────── │
│  · System Prompt + Static SOP + Tool Schemas（由调用方传入）                          │
│                                                                                      │
│  🔒 规则：长度卡死在 1024 Token 以上。在全局并发中绝对静止。                             │
│  💰 效果：DEEPSEEK HARD DISK PROMPT CACHE HIT (1折清仓价)                            │
└──────────────────────────────────────────────────────────────────────────────────────┘
                                           ▲
                                           │ 【 严格的动静隔离物理分界线 】
                                           ▼
┌──────────────────────────────────────────────────────────────────────────────────────┐
│ 【 2. 快照状态区：高频变动，极轻量 】                                                 │
│ ──────────────────────────────────────────────────────────────────────────────────── │
│  · 由 `buildSnapshotState()` 生成：                                                   │
│    [State] ACTING                                                                    │
│      iteration = 3                                                                   │
│      lastTool = read_file                                                            │
│      toolSuccess = true                                                              │
│  💡 把原本散落在 RawMessage 里的几千行垃圾报错，浓缩成了结构化的快照状态变量！         │
└──────────────────────────────────────────────────────────────────────────────────────┘
                                           ▲
                                           │
                                           ▼
┌──────────────────────────────────────────────────────────────────────────────────────┐
│ 【 3. 极短消息尾部区：缓存滑块 】                                                    │
│ ──────────────────────────────────────────────────────────────────────────────────── │
│  · 由 `buildShortTail()` 生成：最近 2 轮纯文本对话（剥离 tool_calls）                  │
│    user: 修复 divide 函数                                                           │
│    assistant: 已修复，现在除零会抛 ValueError                                        │
│                                                                                      │
│  🛹 动态：这一块每轮都在变。但由于前面的垃圾信息全被【快照化】抹平了，这里只剩下区区   │
│           不到 200 Token，哪怕每次都 Miss，Prefill 的耗时和金钱开销也可以忽略不计。     │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

### MeltedPrompt 返回结构

```java
record MeltedPrompt(
    String staticTrunk,      // 静态主干（System Prompt + SOP + Tool Schemas）
    String snapshotState,    // 快照状态区（结构化变量摘要）
    String shortTail,        // 极短消息尾部（最近对话）
    int staticTokens,        // token 估算
    int snapshotTokens,
    int tailTokens,
    long lastAppliedId,      // 最后应用的消息 ID → 用作 Prompt Cache Key
    int totalMessages        // WAL 总消息数
) {
    String fullPrompt();     // 三段拼接
    String cacheKey();       // 缓存 key: "prompt:{lastAppliedId}"
}
```

### 核心方法

| 方法 | 输入 | 输出 |
|------|------|------|
| `melt(sessionId, staticTrunk)` | 会话 ID + 系统 prompt 主干 | `MeltedPrompt` |
| `buildSnapshotState(checkpoint)` | Checkpoint 对象 | `[State] node\n  key = val\n...` 文本 |
| `buildShortTail(messages, rounds)` | WAL 消息列表 + 轮数 | 最近 N 轮纯文本对话（≤400 字符） |

### 设计要点

- **快照状态区**替代了原本散落在 WAL 中的大量 tool 返回数据。Agent 不需要每次请求都重放全部历史消息，只需知道当前状态节点和关键变量即可。
- **尾部只保留纯文本**：`tool_calls`、`tool` 返回、`compact` 摘要都被剥离，只保留 `user` 和 `assistant` 的纯文本消息。这确保了尾部区域的轻量和可缓存性。
- **cacheKey = `prompt:{lastAppliedId}`**：当 `lastAppliedId` 不变时（相同 Checkpoint），静态主干区 + 快照状态区不变，可 100% 命中 DeepSeek Disk Prompt Cache。

---

## 5. 灾难恢复状态机：断点重放与自愈 (ASCII)

当服务器由于各种原因正常或异常重启时，系统一键复活并原地重做（Redo）的状态机逻辑如下：

```text
========================================================================================
【 CRASH RECOVERY & STATE REPLAY STATE MACHINE 】
========================================================================================

  [ 💥 SERVICE REBOOT / SHUTDOWN OVER ]
           │
           v
  ┌──────────────────────────────┐
  │ 1. LOAD LATEST CHECKPOINT    │ ──► 从数据库捞出最新的一条快照记录
  └──────────────┬───────────────┘     得知崩溃前最后的安全底线 (例如: node="ACTING")
                 │                     同时拿到锚点指针：Last_Applied_ID = 102
                 v
  ┌──────────────────────────────┐
  │ 2. FETCH DIRTY WAL SEGMENTS  │ ──► 拿着 ID = 102 去消息表检索所有 `Msg_ID > 102` 的记录
  └──────────────┬───────────────┘     (发现 103[Tool报错]、104[思考] 属于快照未记录的脏数据)
                 │
                 v
  ┌──────────────────────────────┐
  │ 3. REPLAY & REDO LOOP        │ ──► 在 Java 内存中顺序重放 103 和 104 的状态变更：
  └──────────────┬───────────────┘     · 修正内存中的错误计数
                 │                     · 重新推演控制流应该走向哪个 Node
                 │                     · 重放后的消息包含完整的 tool 原始数据和 reasoning 链
                 v
  ┌──────────────────────────────┐
  │ 4. REHYDRATION COMPLETE      │ ──► 内存状态完美追齐到崩溃前的一瞬。
  └──────────────┬───────────────┘     此时生成一个全新的 [CP_B]，将 Last_Applied_ID 顶到 104
                 │
                 v
    [ 🚀 OPEN GATE FOR PLANNER ]   ──► Planner 睁开眼，控制流完全对齐，直接向下破局

```

### 恢复的关键保证

由于 WAL 中保存了 **tool 原始返回数据**和 **LLM 推理链（`<thought>`）**，重放后 Agent 不仅能恢复控制流位置，还能恢复：

- 之前读取的文件内容（不必重新读取）
- 之前执行命令的输出（不必重新执行）
- 之前的推理思路（不必重新思考）

这彻底解决了"一拍脑袋重新猜"导致的工具重复调用和状态丢失问题。

---

## 6. 消息链路校验

WAL Appender 提供链路完整性校验 `validateLinkage(sessionId)`：

```java
LinkageValidation result = appender.validateLinkage(sessionId);
result.isComplete();                 // true 表示无缺失配对
result.missingToolCallIds();         // 缺失配对的 tool_call_id 列表
```

校验规则：遍历所有 `assistant` 消息中的 `tool_calls`，确保每个 `tool_call_id` 在 `tool` 消息中有对应的 `toolCallId` 配对。

---

## 7. 代码入口

| 组件 | 类路径 | 职责 |
|------|--------|------|
| WalSession | `org.cland.alice.memory.wal.WalSession` | 统一门面，封装 WAL + Checkpoint + Recovery + Melter |
| WalAppender | `org.cland.alice.memory.wal.WalAppender` | WAL 消息追加器，含链路校验 |
| CheckpointManager | `org.cland.alice.memory.wal.CheckpointManager` | Checkpoint 生成与幂等性管理 |
| RecoveryEngine | `org.cland.alice.memory.wal.RecoveryEngine` | 灾难恢复与状态重放 |
| PromptMelter | `org.cland.alice.memory.wal.PromptMelter` | 双轨 Prompt 熔炼 |
| FileWalStore | `org.cland.alice.memory.wal.FileWalStore` | JSONL 文件存储实现 |
| AgentExecutor | `org.cland.alice.core.agent.executor.AgentExecutor` | PPAO 循环中自动触发 WAL/Checkpoint |

---

### 设计总结

通过将传统软件的 **WAL + Checkpoint 双轨制** 拍进 `alice-memory-vault`：

1. **彻底解放 IO**：运行时无脑顺序 Append 消息日志（WAL），不卡顿；只在关键的安全节点（Safe Point）异步更新轻量的控制快照。
2. **逻辑原地复活**：重启时，快照提供底座（Base），WAL 提供差量（Delta），在内存中一揉，Agent 瞬间在崩溃点复活。由于 WAL 保存了完整的 tool 原始数据和 LLM 推理链，恢复后无需重新读取文件或重新思考。
3. **消息链路可审计**：每条 assistant.tool_calls 都能找到对应的 tool 响应，链路完整性可自动校验。
4. **完美顺应 DeepSeek**：大段的历史消息在被快照变量化（State Mutation）之后，从 Prompt 中彻底隐退。上下文里留下的是雷打不动的黄金静态前缀，让你的算力账单和响应延迟直接进入微秒级时代。
