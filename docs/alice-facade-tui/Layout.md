---
title: "TUI Layout - v4.0 三区对齐布局"
summary: "基于 JLine 4 实现三区对齐布局（Main Area / Input Area / Footer），LineComponent 区域分隔，PPAO 事件流 Observer 模式 + 输入队列"
read_when:
  - "TUI 布局新增/改造开发"
scope:
  - "alice-facade-tui"
status: "active"
updated: "2026-06-27"
---

# 三区对齐 TUI 架构 v4.0 — 工程设计文档

## 前言

v4.0 在 v3.1（TAO 四段式）基础上全面重构为**三区对齐布局**：

- **Main Area** — Header + `MessageAreaComponent`（统一消息流，替代旧 InputBlock / ThinkBlock / ActionBlock / ObserveBlock 四个独立组件）
- **Input Area** — `LineComponent` / 队列状态 / `InputComponent`
- **Footer** — `LineComponent` / `FooterComponent`（费用 + 模型 + 工具）

每个区域之间由 `LineComponent` 渲染 ANSI 暗色水平分割线，形成视觉清晰的三个对齐区块。

---

## 🎨 三区对齐布局（v4.0）

### 6 组件全景

```text
 ┌─ Main Area ──────────────────────────────────────────┐
 │ 🤖 alice-agent v0.60.0 ──────────────────────────── │  ← HeaderComponent (1行, row 0)
 │                                                      │
 │ together debug current program...                    │  ← MessageAreaComponent
 │ ╸ Step 1 ╸                                          │     (统一消息流, 可滚动)
 │ analyzing the request...                             │
 │ ⮞ TOOL_CALL: list_dir ({path:.})                   │  ← ACTION tag + dark bg
 │ ⮞ -rw------- 1 alice alice 111 config.json         │  ← OBSERVE tag + terminal bg
 │ ⮞ (Took 0.0s)                                       │
 ├────────────────────────────────────────────────────── │  ← LineComponent 1
 │ 📋 2 queued messages                                  │  ← 队列状态行 (1行)
 │ █                                                    │  ← InputComponent (1行, JLine 管理)
 ├────────────────────────────────────────────────────── │  ← LineComponent 2
 │ [💰 $0.041] [📊 125 t/s] [🧠 deepseek-v4-flash]      │  ← FooterComponent (1行, 终端最底行)
 └───────────────────────────────────────────────────────┘
```

### 区域坐标公式（H×W 终端）

```
FIXED_ROWS = HEADER(1) + SEP(1) + QUEUE(1) + INPUT(1) + SEP(1) + FOOTER(1) = 6
messageAreaHeight = H - FIXED_ROWS = H - 6
```

80×24 终端示例：

```
row  0:  HeaderComponent           (1行)
row  1-17: MessageAreaComponent    (18行, H-6=18)
row 18:  LineComponent 1           (1行)
row 19:  Queue line                (1行, 空白 or "📋 N queued messages")
row 20:  InputComponent            (1行)
row 21:  LineComponent 2           (1行)
row 22:  FooterComponent           (1行)
```

### TuiLayout 计算逻辑

```java
public void recalculate(int w, int h) {
    int currentRow = 0;

    // ── 1. Main Area ──
    header.setBounds(currentRow, 0, w, HEADER_HEIGHT);
    currentRow += HEADER_HEIGHT;

    int msgH = h - HEADER_HEIGHT - SEP - QUEUE - INPUT - SEP - FOOTER;
    messageArea.setBounds(currentRow, 0, w, msgH);
    currentRow += msgH;

    // ── 2. Input Area ──
    separator.setBounds(currentRow, 0, w, SEP);
    currentRow += SEP + QUEUE;
    input.setBounds(currentRow, 0, w, INPUT);
    currentRow += INPUT;

    // ── separator2 + Footer ──
    separator2.setBounds(currentRow, 0, w, SEP);
    currentRow += SEP;
    footer.setBounds(currentRow, 0, w, FOOTER);
}
```

---

## 🧩 组件详解

### 1. HeaderComponent

- **文件**: `component/HeaderComponent.java`
- **位置**: row 0
- **渲染**: `🤖 alice-agent v0.60.0 ────────────` （ANSI 38;5;242 暗色分隔线延伸至最右侧）
- **API**: `setLabel(text)`, `label()`

### 2. MessageAreaComponent

- **文件**: `component/MessageAreaComponent.java`
- **位置**: row 1 ～ `H-6`
- **渲染**: 统一消息流，每种消息类型保留独立视觉风格：

| 消息类型 | 背景 | 标识 | API |
|----------|------|------|-----|
| 用户消息 | `48;5;236` 深灰 | 纯文本缩进 | `addUserMessage(text)` |
| 思考推理 | `48;5;255` 亮白 | `╸ Step N ╸` 暗灰标记 | `addThought(text, step, traceId)` |
| 动作执行 | `48;5;236` 深灰 | `ACTION` 橙黄色块 | `addActionLine(desc, traceId)` |
| 观察结果 | `48;5;234` 终端深色 | `OBSERVE` 绿色色块 | `addObservationLine(text, seconds)` |
| 系统消息 | `48;5;255` 亮白 | 纯文本 | `addSystemMessage(text)` |
| Agent 消息 | `48;5;255` 亮白 | 剥除 `[FINISH]` 标记 | `addAgentMessage(text)` |

- **滚动**: `scrollUp/Down/ToBottom/PageUp/Down`
- **行上限**: 2000 行

### 3. LineComponent

- **文件**: `component/LineComponent.java`
- **位置**: Main↔Input 之间（row `H-5`），Input↔Footer 之间（row `H-1`）
- **渲染**: ANSI `38;5;242` 暗色 `─` 填满整行

### 4. InputComponent

- **文件**: `component/InputComponent.java`
- **位置**: row `H-3`
- **说明**: 实际终端 I/O 由 JLine 3 `LineReader.readLine()` 管理，本组件仅维护输入缓冲区模型

### 5. FooterComponent

- **文件**: `component/FooterComponent.java`
- **位置**: 最底行
- **渲染**: 三个 ANSI 256 色实体色块 + 工具信息

```text
[48;5;208m💰 $0.041[0m  [48;5;35m📊 125 t/s[0m  [48;5;239m🧠 gpt-4o[0m ── 🔌 Active: mcp
```

- **API**: `setCost(cost)`, `setSpeed(speed)`, `setModel(modelId)`, `setTool(tool)`

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

### TuiAgentListener (v4.0 统一路由)

所有 PPAO 事件统一路由到 `MessageAreaComponent`：

```java
@Override public void onThought(String reasoningContent) {
    eventBridge.onNewThought(reasoningContent, thoughtStep.incrementAndGet(), traceId);
    // → ScreenManager setupEventListeners:
    //   layout.messageArea().addThought(reasoningContent, step, traceId)
}

@Override public void onAction(String target, Map<String, Object> params) {
    String desc = target + "(" + params + ")";
    eventBridge.onActionExecuting(Action.builder().type(TOOL_CALL).target(desc).build(), traceId);
    // → layout.messageArea().addActionLine(desc, traceId)
}

@Override public void onObserve(String rawData, String summary, long elapsedMs) {
    double seconds = elapsedMs > 0 ? elapsedMs/1000.0 : nanos since start;
    eventBridge.onObserved(rawData, seconds, traceId);
    // → layout.messageArea().addObservationLine(rawData, seconds)
}
```

### 事件路由映射 (v4.0)

| 事件 | 去向 | 路由方法 |
|------|------|----------|
| `StartThinking` | MessageArea | 由 `runInputLoop()` 直接写入 `addUserMessage()` |
| `NewThought` | MessageArea | `messageArea.addThought(content, step, traceId)` |
| `ActionExecuting` | MessageArea | `messageArea.addActionLine(desc, traceId)` |
| `ObservationResult` | MessageArea | `messageArea.addObservationLine(summary, elapsedSec)` |
| `ChatMessage(User)` | MessageArea | `messageArea.addUserMessage(content)` |
| `ChatMessage(System)` | MessageArea | `messageArea.addSystemMessage(content)` |
| `ChatMessage(Agent)` | MessageArea | `messageArea.addAgentMessage(content)` |
| `TaskComplete` | MessageArea | `messageArea.addAgentMessage(result)` |
| `TaskError` | MessageArea | `messageArea.addSystemMessage("错误: " + msg)` |

---

## 📋 输入队列

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

- `inputQueue` 为 `ArrayDeque<String>` FIFO 队列
- `layout.setQueueCount(n)` 更新队列计数
- `queueLine()` 返回 ANSI 格式化队列行文本（空队列返回 `""`）
- `queueRow` 位于 `separatorRow + 1`

---

## 🎨 配色速查

| 区域 | 背景 ANSI | 前景 ANSI | 说明 |
|------|-----------|-----------|------|
| Header | — | `38;5;242` | 暗色延伸 `─` |
| MessageArea 用户消息 | `48;5;236` | `37` | 深灰底 |
| MessageArea 思考/Agent/系统 | `48;5;255` | `30` | 亮白底 |
| MessageArea 动作 | `48;5;236` | `37` | 深灰底 + `ACTION` 橙黄色块 |
| MessageArea 观察 | `48;5;234` | `37` | 终端深色底 + `OBSERVE` 绿色色块 |
| MessageArea Step 标记 | `48;5;255` | `38;5;242` | 暗灰 `╸ Step N ╸` |
| MessageArea Timing | `48;5;255` | `38;5;246` | 暗灰 `(Took X.Xs)` |
| LineComponent | — | `38;5;242` | 暗色 `─` 满行 |
| Queue line | — | `38;5;242` | 暗灰 `📋 N queued` |
| Footer 费用 | `48;5;208` | `30` | 橙黄底 |
| Footer 速率 | `48;5;35` | `30` | 绿底 |
| Footer 模型 | `48;5;239` | `37` | 暗灰底 |

---

## 🛠 架构组件一览

| 组件类 | 文件 | 职责 |
|--------|------|------|
| `HeaderComponent` | `component/HeaderComponent.java` | 顶部标题栏 |
| `MessageAreaComponent` | `component/MessageAreaComponent.java` | **统一消息流**（替代旧 4 组件） |
| `LineComponent` | `component/LineComponent.java` | 区域分割线 |
| `InputComponent` | `component/InputComponent.java` | 输入缓冲区模型 |
| `FooterComponent` | `component/FooterComponent.java` | 底部状态栏（费用+速率+模型+工具） |
| `TuiLayout` | `layout/TuiLayout.java` | 6 组件三区对齐布局计算 |
| `ScreenManager` | `ScreenManager.java` | 全屏渲染，输入循环，事件路由，队列管理 |
| `EventBridge` | `bridge/EventBridge.java` | 事件总线，异步/同步投递 |
| `TuiEvent` | `bridge/TuiEvent.java` | 密封事件类型 |
| `TuiAgentListener` | `TuiAgentListener.java` | Observer 实现，PPAO → EventBridge 转发 |

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
│  → layout.messageArea().addThought()          │
│  → layout.messageArea().addActionLine()       │
│  → layout.messageArea().addObservationLine()  │
│  → contentDirty.set(true)                     │
└──────────────────────────────────────────────┘
┌──────────────────────────────────────────────┐
│  Render Thread  (renderLoop)                  │
│  polling contentDirty → redrawScrollArea()    │
│  → header.renderTo(writer)                    │
│  → messageArea.renderTo(writer)               │
│  → separator.renderTo(writer)                 │
│  → writeRow(queueRow, queueLine())            │
│  → input.renderTo(writer)                     │
│  → separator2.renderTo(writer)                │
│  → footer.renderTo(writer)                    │
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

| 版本 | 布局 | 事件 | 输入 | 组件数 |
|------|------|------|------|--------|
| v2.6 | 单滚动区 + TaoTag 色块 | 轮询 StepResult | 阻塞时错误提示 | 3 |
| v3.0 | 四段式 TAO 区域组件 | `Consumer<PPAOEvent>` | 同上 | 7 |
| v3.1 | 四段式 + 队列行 (8 组件) | `AgentEventListener` Observer 模式 | FIFO 输入队列 | 8 |
| **v4.0** | **三区对齐 (Main/Input/Footer)** | **统一 MessageArea 路由** | **同上** | **6** |

v4.0 关键转变：
1. **四段式 → 三区对齐** — InputBlock/ThinkBlock/ActionBlock/ObserveBlock → 统一 `MessageAreaComponent`
2. **LineComponent 分割线** — 取代 inline `writeRow()`，参与脏标记管线
3. **区域视觉对齐** — Header / MessageArea / Line / Queue+Input / Line / Footer 三区清晰分隔
4. **简化渲染管线** — `redrawScrollArea()` 按 3 个逻辑区顺序渲染
