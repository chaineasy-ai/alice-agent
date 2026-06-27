# 三区对齐终端布局 v4.0（适配统一 MessageArea 消息流）

## 布局结构

```
 ┌─ Main Area ──────────────────────────────────────────┐
 │ 🤖 alice-agent v0.60.0 ──────────────────────────── │  ← HeaderComponent (ANSI 38;5;242 暗色分割线)
 │                                                      │  ← MessageAreaComponent (统一滚动消息流)
 │ together debug current program...                    │     用户消息 (纯文本, 无背景)
 │ ╸ Step 1 ╸                                          │     思考推理 (浅灰字, 无背景)
 │ analyzing the request...                             │
 │ ⮞ TOOL_CALL: list_dir ({path:.})                   │     动作执行 (亮白字 ▮, 无背景)
 │ ⮞ -rw------- 1 alice alice 111 config.json         │     观察结果 (亮白字, 无背景)
 │ ⮞ (Took 0.0s)                                       │
 ├────────────────────────────────────────────────────── │  ← LineComponent 1 (ANSI 38;5;242 暗色)
 │ 📋 2 queued messages                                 │  ← 队列状态行 (ANSI 38;5;242 暗色, 有消息时显示)
 │ █                                                    │  ← InputComponent (JLine readLine 管理)
 ├────────────────────────────────────────────────────── │  ← LineComponent 2 (ANSI 38;5;242 暗色)
 │ [💰 $0.041] [📊 125 t/s] [🧠 deepseek-v4-flash]    │  ← FooterComponent (ANSI 48;5;XXX 实体色块)
 └───────────────────────────────────────────────────────┘
```

## 一、三区说明

### 1. Main Area（主内容区）

| 组件 | 文件 | 背景色 | 说明 |
|------|------|--------|------|
| `HeaderComponent` | `component/HeaderComponent.java` | — | `🤖 alice-agent v0.60.0 ──` 暗色延伸 |
| `MessageAreaComponent` | `component/MessageAreaComponent.java` | 动态按消息类型 | 统一消息流，可滚动 |

所有消息类型在 `MessageAreaComponent` 中按时间序排列，均无背景色（使用终端默认背景），仅通过字体颜色和前缀区分：

| 消息类型 | 前景 | 附加标记 | 添加方法 |
|----------|------|----------|----------|
| 用户消息 | 默认终端色 | — | `addUserMessage(text)` |
| 思考推理 | `38;5;252` 浅灰 | `╸ Step N ╸` | `addThought(text, step, traceId)` |
| 动作执行 | `37` 亮白 | `▮` 前缀 | `addActionLine(desc, traceId)` |
| 观察结果 | `37` 亮白 | — | `addObservationLine(text, seconds)` |
| 系统消息 | 默认终端色 | — | `addSystemMessage(text)` |
| Agent 消息 | 默认终端色 | 剥除 `[FINISH]` | `addAgentMessage(text)` |

### 2. Input Area（输入区）

| 组件 | 文件 | 说明 |
|------|------|------|
| `LineComponent` | `component/LineComponent.java` | ANSI `38;5;242` 暗色 `─` 满行分割线 |
| 队列状态 | (inline) | `📋 N queued messages` 或空白 |
| `InputComponent` | `component/InputComponent.java` | 输入行，实际 I/O 由 JLine 3 管理 |

### 3. Footer（底部状态栏）

| 组件 | 文件 | 说明 |
|------|------|------|
| `LineComponent` | `component/LineComponent.java` | ANSI `38;5;242` 暗色 `─` 满行分割线 |
| `FooterComponent` | `component/FooterComponent.java` | 三个实体色块 + 工具信息 |

Footer 渲染格式：
```text
[48;5;239m💰 $0.041  [48;5;239m📊 125 t/s  [48;5;239m🧠 gpt-4o  ── 🔌 Active: cland-pay-mcp
```

---

## 二、PPAO 实时执行流（v4.0 统一路由）

```
AgentExecutor Micro-ReAct 循环：
┌─────────────────────────────────────────────────────┐
│ dispatchLlmInference → emitPPAO(thought)           │
│   → MessageArea.addThought("reasoning...", step)    │
│                                                     │
│ dispatchToolCall  → emitPPAO(action)                │
│   → MessageArea.addActionLine("$ cmd (timeout)")    │
│   → [工具执行...]                                    │
│   → emitPPAO(observe)                               │
│   → MessageArea.addObservationLine("rawData", sec)  │
│                                                     │
│ dispatchLlmInference → emitPPAO(thought)            │
│   → MessageArea.addThought("more...", step)         │
│   → [最终响应] → TaskComplete                       │
│   → MessageArea.addAgentMessage("final answer")     │
└─────────────────────────────────────────────────────┘
```

## 三、事件路由映射（v4.0）

| 事件 | 来源 | 去向 | 路由方法 |
|------|------|------|----------|
| `StartThinking` | `runInputLoop()` → `eventBridge.onStartThinking()` | MessageArea | 由 `runInputLoop()` 直接写入 `addUserMessage()` |
| `NewThought` | PPAO consumer "thought" | MessageArea | `messageArea.addThought(content, step, traceId)` |
| `ActionExecuting` | PPAO consumer "action" | MessageArea | `messageArea.addActionLine(desc, traceId)` |
| `ObservationResult` | PPAO consumer "observe" (含 `$ <cmd>` 前缀) | MessageArea | `messageArea.addObservationLine(content, sec)` |
| `ChatMessage(User)` | WAL 恢复 / 系统消息 | MessageArea | `messageArea.addUserMessage(content)` |
| `ChatMessage(System)` | 系统通知 / 错误反馈 | MessageArea | `messageArea.addSystemMessage(content)` |
| `ChatMessage(Agent)` | WAL 恢复 | MessageArea | `messageArea.addAgentMessage(content)` |
| `TaskComplete` | `agent.ask()` 返回 | MessageArea | `messageArea.addAgentMessage(result)` |
| `TaskError` | 异常捕获 | MessageArea | `messageArea.addSystemMessage("错误: ...")` |

## 四、端子布局计算公式（Terminal H × W）

```
FIXED_ROWS = HEADER(1) + SEP(1) + QUEUE(1) + INPUT(1) + SEP(1) + FOOTER(1) = 6
消息区高度 = H - 6
```

对于 80×24 终端：
```
row  0:  HeaderComponent      (1行)
row  1-17: MessageAreaComponent (18行)
row 18:  LineComponent 1       (1行)
row 19:  Queue line            (1行, 空白 or "📋 2 queued messages")
row 20:  InputComponent        (1行)
row 21:  LineComponent 2       (1行)
row 22:  FooterComponent       (1行)
```

## 五、ANSI 配色速查

| 区域 | 前景 ANSI | 背景 ANSI | 说明 |
|------|-----------|-----------|------|
| Header | `38;5;242` | — | 暗色分割线 `─` |
| MessageArea 用户消息 | 默认 | — | 纯文本，无背景 |
| MessageArea 思考推理 | `38;5;252` 浅灰 | — | `╸ Step N ╸` 浅灰标记 |
| MessageArea 动作 | `37` 亮白 | — | `▮` 前缀 |
| MessageArea 观察 | `37` 亮白 | — | — |
| MessageArea Timing | `38;5;246` | — | 暗灰 `(Took X.Xs)` |
| LineComponent | `38;5;242` | — | 暗色 `─` 满行 |
| Queue line | `38;5;242` | — | 暗灰 `📋 N queued` |
| Footer 费用 | `37` | `48;5;239` 暗灰 | 统一暗灰色块 |
| Footer 速率 | `37` | `48;5;239` 暗灰 | 统一暗灰色块 |
| Footer 模型 | `37` | `48;5;239` 暗灰 | 统一暗灰色块 |
| Footer 工具 | `38;5;242` | — | 暗灰 `── 🔌` |

## 六、关键代码入口

| 文件 | 职责 |
|------|------|
| `component/MessageAreaComponent.java` | 统一消息流 — `addUserMessage()` / `addThought()` / `addActionLine()` / `addObservationLine()` / `addSystemMessage()` / `addAgentMessage()` |
| `component/LineComponent.java` | 区域分割线 |
| `component/FooterComponent.java` | 底部状态栏 |
| `layout/TuiLayout.java` | 三区对齐布局计算 |
| `ScreenManager.java` | 事件路由 + 全屏渲染 |
| `AliceTuiLauncher.java` | PPAO consumer 注册 + EventBridge 接线 |
| `TuiAgentListener.java` | Observer 实现，PPAO → EventBridge 转发 |

## 七、v3.1 → v4.0 迁移对照

| v3.1 (TAO 四段式) | v4.0 (三区对齐) | 说明 |
|--------------------|-----------------|------|
| `InputBlockComponent` + `showSession()` | `MessageAreaComponent.addUserMessage()` | 用户消息直接进入统一消息流 |
| `ThinkBlockComponent.addThought()` | `MessageAreaComponent.addThought()` | 方法签名一致 |
| `ActionBlockComponent.addCommand()` | `MessageAreaComponent.addActionLine()` | 方法签名一致 |
| `ObserveBlockComponent.addOutput()` + `addTiming()` | `MessageAreaComponent.addObservationLine()` | 合并为一个方法 |
| `InputBlockComponent.clear()` / `ThinkBlockComponent.clear()` / 等 | `MessageAreaComponent.clear()` | 统一清空 |
| inline `writeRow(separatorLine)` + `writeRow(separator2Line)` | `separator.renderTo()` + `separator2.renderTo()` | LineComponent 参与脏标记管线 |
| 7 组件 (Header+IB+TB+AB+OB+Input+Footer) | 6 组件 (Header+MA+Sep+Input+Sep2+Footer) | 精简 1 个组件 |
