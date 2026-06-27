---
title: "TUI Layout - v3.1 TAO 四段式布局"
summary: "基于 JLine 4 实现 TAO 四段式布局，PPAO 事件流 Observer 模式 + 输入队列"
read_when:
  - "TUI 布局新增/改造开发"
scope:
  - "alice-facade-tui"
status: "active"
updated: "2026-06-27"
---

# TAO 四段式 TUI 架构 v3.1 — 工程设计文档

## 前言

本版为 v3.1，在 v2.6（Footer 固定底行）基础上全面重构为 TAO 四段式布局：

- 用四个独立区域组件（InputBlock / ThinkBlock / ActionBlock / ObserveBlock）替代单一 `ThoughtComponent`
- PPAO 事件流通过 Observer 模式（`AgentEventListener`）实时投递
- 输入队列缓存忙碌期间的输入，`📋 N queued messages` 队列状态行
- 区块背景色取代 `TaoTag` 色块标签

---

## 🎨 TAO 四段式布局（v3.1）

### 8 组件全景

```text
 🤖 alice-agent v0.60.0 ──────────────────────────────  ← Header (1行, row 0, ANSI 242 暗色延伸)
  debug current program                                 ← InputBlock (2行, ANSI 48;5;236 深色底)
  ┈ Step 1 ┈                                            ← ThinkBlock (~45% 内容区, ANSI 48;5;255 亮色底)
  The user wants to debug the current program...
  ┈ Step 2 ┈
  Let me explore these directories...
  $ list_dir({path: .}) (timeout 120s)                  ← ActionBlock (2行, ANSI 48;5;236 深色底)
  $ read_file({path: README.md}) (timeout 120s)
  $ list_dir({path: .})                                 ← ObserveBlock (~55% 内容区, ANSI 48;5;234 终端深色底)
  alice-memory-vault/
  todos/
  Took 0.0s
 ─────────────────────────────────────────────────────  ← 分割线 (1行, ANSI 242 暗色)
  📋 2 queued messages                                  ← 队列状态行 (1行, 有消息时显示, 无消息时空白)
  █                                                    ← 输入区 (1行, JLine readLine 管理)
  [💰 $0.041] [📊 125 t/s] [🧠 deepseek-v4-flash]      ← Footer (1行, 终端最底行 H-1)
```

### 区域坐标公式（H×W 终端）

```
FIXED_ROWS = HEADER(1) + INPUT_BLOCK(2) + ACTION_BLOCK(2) + STATUS_HEIGHT(1) = 6
QUEUE_HEIGHT = 1 (always present, blank when empty)
contentHeight = H - 8 - 1 = H - 9

ThinkBlock   ─ floor(contentHeight × 0.45)
ObserveBlock ─ contentHeight - ThinkBlock
```

80×24 终端示例：

```
row  0:  Header
row  1-2:  InputBlock       (2行)
row  3-8:  ThinkBlock       (6行, 45%)
row  9-10: ActionBlock      (2行)
row 11-19: ObserveBlock     (9行, 55%)
row 20:    Separator        (1行)
row 21:    Queue line       (1行, 空白或无消息)
row 22:    Input            (1行)
row 23:    Footer           (1行, 终端最底行)
```

### TuiLayout 计算代码

```java
public void recalculate(int terminalWidth, int terminalHeight) {
    this.terminalWidth = Math.max(terminalWidth, 40);
    this.terminalHeight = Math.max(terminalHeight, FIXED_ROWS + 6);

    int currentRow = 0;

    // 1. Header: row 0
    header.setBounds(currentRow, 0, this.terminalWidth, HEADER_HEIGHT);
    currentRow += HEADER_HEIGHT;

    // 2. InputBlock: 固定 2 行
    inputBlock.setBounds(currentRow, 0, this.terminalWidth, INPUT_BLOCK_HEIGHT);
    currentRow += INPUT_BLOCK_HEIGHT;

    // 3. ThinkBlock: 45% of remaining
    int remaining = terminalHeight - currentRow
        - ACTION_BLOCK_HEIGHT - 1(separator) - QUEUE_HEIGHT - 1(input) - STATUS_HEIGHT;
    thinkBlockHeight = (int) Math.floor(remaining * 0.45);
    thinkBlock.setBounds(currentRow, 0, this.terminalWidth, thinkBlockHeight);
    currentRow += thinkBlockHeight;

    // 4. ActionBlock: 固定 2 行
    actionBlock.setBounds(currentRow, 0, this.terminalWidth, ACTION_BLOCK_HEIGHT);
    currentRow += ACTION_BLOCK_HEIGHT;

    // 5. ObserveBlock: 剩余
    remaining = terminalHeight - currentRow
        - 1(separator) - QUEUE_HEIGHT - 1(input) - STATUS_HEIGHT;
    observeBlock.setBounds(currentRow, 0, this.terminalWidth, remaining);
    currentRow += remaining;

    // 6-9. Separator / Queue / Input / Footer
    separatorRow = currentRow;
    queueRow = separatorRow + 1;
    inputRow = queueRow + 1;
    input.setBounds(inputRow, 0, this.terminalWidth, 1);
    footerRow = terminalHeight - 1;
    footer.setBounds(footerRow, 0, this.terminalWidth, STATUS_HEIGHT);
}
```

---

## 🔌 PPAO 事件流 — Observer 模式

### 接口定义 (`alice-core-agent`)

```java
public interface AgentEventListener {
    default void onThought(String reasoningContent) {}
    default void onAction(String target, Map<String, Object> params) {}
    default void onObserve(String rawData, String summary, long elapsedMs) {}
}
```

### AgentExecutor 分发点

| 阶段 | AgentExecutor 方法 | 触发方法 | 监听器回调 |
|------|-------------------|----------|-----------|
| Reason | `dispatchLlmInference()` | `fireOnThought(reasoning)` | `onThought(String)` |
| Dispatch | `dispatchToolCall()` | `fireOnAction(target, params)` | `onAction(target, params)` |
| Observe | `dispatchToolCall()` (工具返回后) | `fireOnObserve(rawData, summary, elapsedMs)` | `onObserve(...)` |

### TuiAgentListener 实现 (`alice-facade-tui`)

```java
public class TuiAgentListener implements AgentEventListener {
    private final EventBridge eventBridge;
    private final AtomicInteger thoughtStep;
    private final AtomicReference<String> lastAction;
    private final AtomicLong actionStartNanos;

    @Override
    public void onThought(String reasoningContent) {
        eventBridge.onNewThought(reasoningContent, thoughtStep.incrementAndGet());
        // → ScreenManager: ThinkBlock.addThought()
    }

    @Override
    public void onAction(String target, Map<String, Object> params) {
        lastAction.set(target + "(" + params + ")");
        actionStartNanos.set(System.nanoTime());
        eventBridge.onActionExecuting(ac);
        // → ScreenManager: ActionBlock.addCommand()
    }

    @Override
    public void onObserve(String rawData, String summary, long elapsedMs) {
        double seconds = elapsedMs > 0 ? elapsedMs/1000.0 : nanos since actionStartNanos;
        String content = "$ " + lastAction.get() + "\n" + rawData;
        eventBridge.onObserved(content, seconds);
        // → ScreenManager: ObserveBlock.addOutput() + addTiming(seconds)
    }
}
```

### 注册

```java
// AliceTuiLauncher.hookAgentEvents()
agent.getExecutor().addListener(new TuiAgentListener(eventBridge));
```

---

## 📋 输入队列

### 队列生命周期

```
用户输入 (Agent 忙碌)
       ↓
  ScreenManager.inputQueue.addLast(text)    ← 静默入队
  layout.setQueueCount(queue.size())        ← 📋 N queued messages 显示
       ↓
Agent 任务完成 (TaskComplete / TaskError)
       ↓
  dispatchNextFromQueue()
  queue.pollFirst() → 自动提交
       ↓
Agent 开始执行新任务
```

### 实现要点

- `inputQueue` 为 `ArrayDeque<String>` FIFO 队列
- `layout.setQueueCount(n)` 更新队列计数
- `layout.queueLine()` 返回 ANSI 格式化队列行文本（空队列返回 `""`）
- `queueRow` 位于 `separatorRow + 1`，在 `fullRedraw()` / `redrawScrollArea()` / `restoreLowerArea()` 中同步渲染
- 队列行在 `cursorLine(queueRow)` 写入 `queueLine()` + `ANSI_CLEAR_LINE`

---

## 🎨 配色速查

| 区域 | 背景 ANSI | 前景 ANSI | 说明 |
|------|-----------|-----------|------|
| Header | — | `38;5;242` | 暗色延伸 `─` |
| InputBlock | `48;5;236` | `37` | 深灰底 |
| ThinkBlock | `48;5;255` | `30` | 亮白底 |
| ThinkBlock Step | `48;5;255` | `38;5;242` | 暗灰 `┈ Step N ┈` |
| ActionBlock | `48;5;236` | `37` | 同 InputBlock 深灰底 |
| ActionBlock `$` | `48;5;236` | `38;5;39` | 亮蓝命令提示符 |
| ActionBlock timeout | `48;5;236` | `38;5;147` | 淡蓝超时标签 |
| ObserveBlock | `48;5;234` | `37` | 终端深色底 |
| ObserveBlock dir | `48;5;234` | `38;5;222` | 亮黄 drwx 行 |
| ObserveBlock timing | `48;5;234` | `38;5;246` | 暗灰 Took X.XXs |
| Queue line | — | `38;5;242` | 暗灰 `📋 N queued` |
| Footer 费用 | `48;5;208` | `30` | 橙黄底 |
| Footer 速率 | `48;5;35` | `30` | 绿底 |
| Footer 模型 | `48;5;239` | `37` | 暗灰底 |

---

## 🛠 架构组件一览

| 组件类 | 文件 | 职责 |
|--------|------|------|
| `InputBlockComponent` | `component/InputBlockComponent.java` | 顶部深色块，展示用户最新输入，无会话前缀 |
| `ThinkBlockComponent` | `component/ThinkBlockComponent.java` | 中间推理区，亮底白字，`┈ Step N ┈` 步骤标记 |
| `ActionBlockComponent` | `component/ActionBlockComponent.java` | 动作命令区，深底，`$ cmd (timeout 120s)` |
| `ObserveBlockComponent` | `component/ObserveBlockComponent.java` | 观察输出区，终端深底，`Took X.XXs` |
| `TuiLayout` | `layout/TuiLayout.java` | 8 组件布局计算，队列状态行渲染 |
| `ScreenManager` | `ScreenManager.java` | 全屏渲染，输入循环，事件路由，队列管理 |
| `EventBridge` | `bridge/EventBridge.java` | 事件总线，异步/同步投递 |
| `TuiEvent` | `bridge/TuiEvent.java` | 密封事件类型 |
| `TuiAgentListener` | `TuiAgentListener.java` | Observer 实现，PPAO → EventBridge 转发 |
| `AgentEventListener` | (alice-core-agent) `executor/AgentEventListener.java` | Observer 接口 |
| `AgentExecutor` | (alice-core-agent) | `addListener()` 注册，PPAO 三点分发 |

---

## 🧵 线程模型

```
┌──────────────────────┐    ┌─────────────────────────┐
│   AgentExecutor      │    │   EventBridge            │
│   (Vert.x eventloop) │    │   (single-thread exec)   │
│                      │    │                          │
│  fireOnThought() ────┼───→│  emit(NewThought)        │
│  fireOnAction()  ────┼───→│  emit(ActionExecuting)   │
│  fireOnObserve() ────┼───→│  emit(ObservationResult) │
└──────────────────────┘    └─────────┬────────────────┘
                                      │ dispatchToListeners()
                                      ↓
┌──────────────────────────────────────────────┐
│  ScreenManager  (EventBridge listener)        │
│  → layout.thinkBlock().addThought()           │
│  → layout.actionBlock().addCommand()          │
│  → layout.observeBlock().addOutput()          │
│  → contentDirty.set(true)                     │
└──────────────────────────────────────────────┘
┌──────────────────────────────────────────────┐
│  Render Thread  (renderLoop)                  │
│  polling contentDirty → redrawScrollArea()    │
│  → cursorLine() + ANSI writes                 │
└──────────────────────────────────────────────┘
┌──────────────────────────────────────────────┐
│  Main Thread  (runInputLoop)                  │
│  → reader.readLine()                          │
│  → enqueue / submitTask                       │
│  → restoreLowerArea() after readLine()        │
└──────────────────────────────────────────────┘
```

---

## 💎 设计演进总结

| 版本 | 布局 | 事件 | 输入 |
|------|------|------|------|
| v2.6 | 单滚动区 + TaoTag 色块 | 轮询 StepResult | 阻塞时错误提示 |
| v3.0 | 四段式 TAO 区域组件 | `Consumer<PPAOEvent>` | 同上 |
| **v3.1** | 四段式 + 队列行 (8 组件) | `AgentEventListener` Observer 模式 | FIFO 输入队列自动提交 |

关键转变：
1. **去 TaoTag** — 区块背景色自身提供视觉区分
2. **Observer 模式** — 类型安全接口，多监听器支持
3. **实际耗时传递** — `ObservationResult.elapsedSec` 替代 `addTiming(0.0)`
4. **输入队列** — 忙碌时静默入队，完成后自动提交
