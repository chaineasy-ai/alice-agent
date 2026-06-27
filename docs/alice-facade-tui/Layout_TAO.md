# TAO 四段式终端布局 v3.1（适配 PPAO 实时执行流）

## 布局结构

```
 🤖 alice-agent v0.60.0 ──────────────────────────  ← Header (ANSI 38;5;242 暗色分割线)
                                                     ← InputBlock (ANSI 48;5;236 深色底)
  debug current program
                                                     ← ThinkBlock (ANSI 48;5;255 亮色底)
  ┈ Step 1 ┈
  The user wants to debug the current program.
  ┈ Step 2 ┈
  Let me explore these directories...
  Hello! Here's a summary of what I found...
                                                     ← ActionBlock (ANSI 48;5;236 深色底, 同InputBlock)
  $ list_dir({path: .}) (timeout 120s)
  $ read_file({path: README.md}) (timeout 120s)
                                                     ← ObserveBlock (ANSI 48;5;234 终端深色底)
  $ list_dir({path: .})
  alice-memory-vault/
  todos/
  Took 0.0s

  $ read_file({path: README.md})
  # Alice Agent — Contributor Quickstart Guide
  ...
  Took 0.0s
 ───────────────────────────────────────────────  ← Separator (ANSI 38;5;242 暗色)
                                                     ← Input (1行空白输入区)
 [💰 $0.041] [📊 125 t/s] [🧠 deepseek-v4-flash]  ← Footer (ANSI 48;5;XXX 实体色块)
```

## 一、四段式区域说明

### 1. InputBlock（顶部输入内容区）
- **背景色**: ANSI `48;5;236` (≈ #2f3742)
- **文字色**: `38;5;37` 亮白
- **展示内容**: 用户最新输入文本（纯文本，无 `✓` 或 `session started` 前缀）
- **高度**: 固定 2 行
- **代码**: `InputBlockComponent`

### 2. ThinkBlock（中间思考推理区）
- **背景色**: ANSI `48;5;255` (≈ #f6f8fa)
- **文字色**: `30m` 深黑
- **展示内容**: Agent 推理过程，每段前标注 `┈ Step N ┈` (ANSI `38;5;242` 暗色)
- **高度**: 比例分配 ~45% 内容区
- **代码**: `ThinkBlockComponent`

### 3. ActionBlock（中下动作命令区）
- **背景色**: ANSI `48;5;236` (≈ #2f3742，同 InputBlock)
- **命令提示符**: `$` 亮蓝 `38;5;39` (≈ #61dafb)
- **超时标签**: `(timeout 120s)` 淡蓝 `38;5;147` (≈ #a0a0ff)
- **格式**: `$ <command> (timeout 120s)`
- **高度**: 固定 2 行
- **代码**: `ActionBlockComponent`

### 4. ObserveBlock（底部观察输出区）
- **背景色**: ANSI `48;5;234` (≈ #1e2329)
- **文字色**: `37m` 亮白
- **目录模式行**: `drwxr-xr-x` 风格亮黄 `38;5;222` (≈ #e6c07b)
- **耗时统计**: `Took X.XXs` 暗灰 `38;5;246` (≈ #999)
- **扩展提示**: `... (N earlier lines, ctrl+o to expand)` 暗灰 `38;5;242`
- **每对 action+observe 自包含**: PPAO consumer 将 action 命令前缀 `$ <cmd>` 与工具 `rawData` 配对写入
- **高度**: 比例分配 ~55% 内容区
- **代码**: `ObserveBlockComponent`

## 二、PPAO 实时执行流

```
AgentExecutor Micro-ReAct 循环：
┌─────────────────────────────────────────────────────┐
│ dispatchLlmInference → emitPPAO(thought)           │
│   → ThinkBlock.addThought("reasoning...", step)     │
│                                                     │
│ dispatchToolCall  → emitPPAO(action)                │
│   → ActionBlock.addCommand("$ cmd (timeout 120s)")  │
│   → [工具执行...]                                    │
│   → emitPPAO(observe)                               │
│   → ObserveBlock.addOutput("rawData")              │
│   → ObserveBlock.addTiming(seconds)                 │
│                                                     │
│ dispatchLlmInference → emitPPAO(thought)            │
│   → ThinkBlock.addThought("more reasoning...", step)│
│   → [最终响应] → TaskComplete                       │
│   → ThinkBlock.addAgentMessage("final answer")      │
└─────────────────────────────────────────────────────┘
```

## 三、事件路由映射

| 事件 | 来源 | 去向 | 路由方法 |
|------|------|------|----------|
| `StartThinking` | `runInputLoop()` → `eventBridge.onStartThinking()` | InputBlock | 已由 `runInputLoop()` 直接写入，handler 不重复 |
| `NewThought` | PPAO consumer `"thought"` | ThinkBlock | `thinkBlock.addThought(content, step)` |
| `ActionExecuting` | PPAO consumer `"action"` | ActionBlock | `actionBlock.addCommand(desc)` |
| `ObservationResult` | PPAO consumer `"observe"` (含 `$ <cmd>` 前缀) | ObserveBlock | `observeBlock.addOutput(content)` + `addTiming(sec)` |
| `ChatMessage(User)` | WAL 恢复 / 系统消息 | ThinkBlock | `thinkBlock.addUserMessage(content)` |
| `ChatMessage(System)` | 系统通知 / 错误反馈 | ThinkBlock | `thinkBlock.addSystemMessage(content)` |
| `ChatMessage(Agent)` | WAL 恢复 | ThinkBlock | `thinkBlock.addAgentMessage(content)` |
| `TaskComplete` | `agent.ask()` 返回 | ThinkBlock | `thinkBlock.addAgentMessage(result)` |
| `TaskError` | 异常捕获 | ThinkBlock | `thinkBlock.addSystemMessage("错误: ...")` |

## 四、端子布局计算公式（Terminal H × W）

```
FIXED_ROWS = HEADER(1) + INPUT_BLOCK(2) + ACTION_BLOCK(2) + FOOTER(1) = 6
SEPARATOR(1) + INPUT(1) = 2 行额外固定
内容区高度 = H - 8

ThinkBlock   = floor(内容区高度 × 0.45)
ObserveBlock = 内容区高度 - ThinkBlock
```

对于 80×24 终端：
```
row  0: Header
row  1-2:  InputBlock      (2行)
row  3-9:  ThinkBlock      (7行, 45%)
row 10-11: ActionBlock     (2行)
row 12-20: ObserveBlock    (9行, 55%)
row 21:    Separator       (1行)
row 22:    Input           (1行)
row 23:    Footer          (1行)
```

## 五、ANSI 配色速查

| 区域 | 背景 ANSI | 前景 ANSI | 说明 |
|------|-----------|-----------|------|
| Header | — | `38;5;242` | 暗色分割线 `─` |
| InputBlock | `48;5;236` | `37` | 深灰底 #2f3742 |
| ThinkBlock | `48;5;255` | `30` | 亮白底 #f6f8fa |
| ThinkBlock Step | `48;5;255` | `38;5;242` | 暗灰步骤标记 |
| ActionBlock | `48;5;236` | `37` | 深灰底同 Input |
| ActionBlock `$` | `48;5;236` | `38;5;39` | 亮蓝 #61dafb |
| ActionBlock timeout | `48;5;236` | `38;5;147` | 淡蓝 #a0a0ff |
| ObserveBlock | `48;5;234` | `37` | 终端深色底 #1e2329 |
| ObserveBlock dir | `48;5;234` | `38;5;222` | 亮黄 drwx 行 |
| ObserveBlock timing | `48;5;234` | `38;5;246` | 暗灰 Took |
| Footer 费用 | `48;5;208` | `30` | 橙黄底 |
| Footer 速率 | `48;5;35` | `30` | 绿底 |
| Footer 模型 | `48;5;239` | `37` | 暗灰底 |
| Separator | — | `38;5;242` | 暗灰 `─` 满行 |

## 六、关键代码入口

| 文件 | 职责 |
|------|------|
| `component/InputBlockComponent.java` | 输入内容区 — `showUserInput(text)` |
| `component/ThinkBlockComponent.java` | 思考区 — `addThought(text, step)` |
| `component/ActionBlockComponent.java` | 动作区 — `addCommand(desc)` |
| `component/ObserveBlockComponent.java` | 观察区 — `addOutput(text)` / `addTiming(sec)` |
| `layout/TuiLayout.java` | 四段式布局计算 |
| `ScreenManager.java` | TAO 事件路由 + 全屏渲染 |
| `AliceTuiLauncher.java` | PPAO consumer 注册 + EventBridge 接线 |
| `AgentExecutor.java` | `PPAOEvent` record + `emitPPAO()` 发射点 |
