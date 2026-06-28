---
title: "TUI Layout - v5.0 动态增长布局"
summary: "基于 JLine 4 实现动态增长布局（Header → Main Area [0..N] → QueueMsg [0..1] → Line1 → Input → Line2 → Footer），PPAO 事件流 Observer 模式 + 输入队列 + 滚动静默"
read_when:
  - "TUI 布局新增/改造开发"
scope:
  - "alice-facade-tui"
status: "active"
updated: "2026-06-28"
---

# 动态增长 TUI 架构 v5.0 — 工程设计文档

## 前言

v5.0 在 v4.0（三区对齐）基础上重构为**动态增长布局**：

- **Header** — 固定顶部 1 行
- **Main Area** [0..N] — 动态填满可用空间，内容超出时自动滚动（`scrollToBottom`），最新消息始终可见
- **QueueMsg** [0..1] — 仅队列非空时显示文本，行始终预留
- **Line1** — 分割线
- **Input** — 输入行（JLine 管理），带警告光标
- **Line2** — 分割线
- **Footer** — 底部状态栏

布局序列由 `TuiLayout.recalculate(w, h, contentLines)` 统一计算，`afterContentAdded()` 在每次内容变更后调用 `relayout()` 更新所有组件位置。

---

## 🎨 动态增长布局（v5.0）

### 布局序列

```text
 ┌─ Header ───────────────────────────────────────────┐
 │  🤖 alice-agent v0.60.0 ─────────────────────────── │  ← HeaderComponent (1行, row 0)
 ├─────────────────────────────────────────────────────┤
 │  together debug current program...                  │  ← MessageAreaComponent
 │  ╸ Step 1 ╸                                        │     [0..N] 动态
 │  analyzing the request...                           │     填满可用空间
 │  ⮞ TOOL_CALL: list_dir ({path:.})                  │     超出时自动滚动
 │  ⮞ -rw------- 1 alice alice 111 config.json        │     最新消息在底
 │  ⮞ (Took 0.0s)                                      │
 ├─────────────────────────────────────────────────────┤
 │  📋 2 queued messages                               │  ← QueueMsg [0..1] (仅文本)
 ├─────────────────────────────────────────────────────┤  ← LineComponent 1
 │  █                                                  │  ← InputComponent (1行, JLine 管理)
 ├─────────────────────────────────────────────────────┤  ← LineComponent 2
 │  [💰 $0.041] [📊 125 t/s] [🧠 deepseek-v4-flash]    │  ← FooterComponent (1行)
 └─────────────────────────────────────────────────────┘
```

### 区域坐标公式（H×W 终端）

```
FIXED_ROWS = HEADER(1) + QUEUE(1) + SEP(1) + INPUT(1) + SEP(1) + FOOTER(1) = 6
mainAreaHeight = max(H - 6, 1)   // 始终填满可用空间
```

80×24 终端示例（内容未超出）：

```
row  0:  HeaderComponent           (1行)
row  1-18: MessageAreaComponent    (18行, H-6=18)
row 19:  QueueMsg                  (1行, 空白 or "📋 N queued messages")
row 20:  LineComponent 1           (1行)
row 21:  InputComponent            (1行)
row 22:  LineComponent 2           (1行)
row 23:  FooterComponent           (1行)
```

### TuiLayout 计算逻辑

```java
public void recalculate(int w, int h, int contentLines) {
    int currentRow = 0;

    // ── Header ──
    header.setBounds(currentRow, 0, w, HEADER_HEIGHT);
    currentRow += HEADER_HEIGHT;

    // ── Main Area [0..N] ──
    int fixedBelow = QUEUE_HEIGHT + SEP_HEIGHT + INPUT_HEIGHT + SEP_HEIGHT + FOOTER_HEIGHT;
    int maxAvailable = h - currentRow - fixedBelow;
    int mainHeight = Math.max(maxAvailable, 1);  // 始终填满可用空间
    messageArea.setBounds(currentRow, 0, w, mainHeight);
    messageArea.onResize(oldMsgHeight);
    currentRow += mainHeight;

    // ── QueueMsg [0..1] ──
    queueRow = currentRow;   // 始终预留
    currentRow += QUEUE_HEIGHT;

    // ── Line1 / Input / Line2 / Footer ──
    separator.setBounds(currentRow, 0, w, SEP_HEIGHT);
    currentRow += SEP_HEIGHT;
    input.setBounds(currentRow, 0, w, INPUT_HEIGHT);
    currentRow += INPUT_HEIGHT;
    separator2.setBounds(currentRow, 0, w, SEP_HEIGHT);
    currentRow += SEP_HEIGHT;
    footer.setBounds(currentRow, 0, w, FOOTER_HEIGHT);
}
```

### 内容变化后重新布局

每次内容变更后，`ScreenManager.afterContentAdded()` 调用 `layout.relayout()`：

```java
private void afterContentAdded() {
    layout.relayout();  // → recalculate(w, h, contentLineCount())
    reader.setVariable(LINE_OFFSET, Math.max(1, layout.footerRow() - layout.inputRow()));
    contentDirty.set(true);
}
```

---

## 🧩 组件详解

### 1. HeaderComponent

- **文件**: `component/HeaderComponent.java`
- **位置**: row 0
- **渲染**: `🤖 alice-agent v0.60.0 ────────────` （ANSI 38;5;242 暗色延伸至最右侧）
- **API**: `setLabel(text)`, `label()`

### 2. MessageAreaComponent

- **文件**: `component/MessageAreaComponent.java`
- **位置**: row 1 ～ `H-6`
- **默认背景**: 无（使用终端默认背景）
- **行为**:
  - 内容未超出可用空间时：全部消息可见
  - 内容超出时：`scrollToBottom()` 确保最新消息在底部，旧消息向上滚动
  - 用户可通过 **Page Up / Page Down** 翻页查看历史消息

| 消息类型 | 前景 | 标识 | API |
|----------|------|------|-----|
| 用户消息 | 默认终端色 | 纯文本缩进 `  ` | `addUserMessage(text)` |
| 思考推理 | `38;5;252` 浅灰 | `╸ Step N ╸` 暗灰标记 | `addThought(text, step, traceId)` |
| 动作执行 | `37` 亮白 | `▮` 前缀 | `addActionLine(desc, traceId)` |
| 观察结果 | `37` 亮白 | 目录 `38;5;222` 亮黄 | `addObservationLine(text, seconds)` |
| 系统消息 | 默认终端色 | 纯文本 | `addSystemMessage(text)` |
| Agent 消息 | 默认终端色 | 剥除 `[FINISH]` | `addAgentMessage(text)` |

- **滚动方法**: `scrollUp()`, `scrollDown()`, `scrollToBottom()`, `pageUp()`, `pageDown()`
- **行上限**: 2000 行

### 3. LineComponent

- **文件**: `component/LineComponent.java`
- **位置**: Queue↔Input 之间（Line1），Input↔Footer 之间（Line2）
- **渲染**: ANSI `38;5;242` 暗色 `─` 填满整行

### 4. QueueMsg

- **位置**: Main Area 下方，Line1 上方
- **渲染**: `writeRow(queueRow, queueLine())` — `queueLine()` 返回 ANSI 格式化文本或空白
- **行为**: 行始终预留（位置不跳动），仅 `queueCount > 0` 时显示文本

### 5. InputComponent

- **文件**: `component/InputComponent.java`
- **位置**: Line1 下方，Line2 上方
- **说明**: 实际终端 I/O 由 JLine 4 `LineReader.readLine()` 管理，本组件仅维护输入缓冲区模型

### 6. FooterComponent

- **文件**: `component/FooterComponent.java`
- **位置**: 最底行
- **渲染**: 三个 ANSI 256 色实体色块 + 工具信息

```text
[48;5;208m💰 $0.041[0m  [48;5;35m📊 125 t/s[0m  [48;5;239m🧠 gpt-4o[0m ── 🔌 Active: mcp
```

- **API**: `setCost(cost)`, `setSpeed(speed)`, `setModel(modelId)`, `setTool(tool)`

---

## 🔄 滚动与历史查看

### 自动滚动

`MessageAreaComponent.appendLine()` 调用 `scrollToBottom()`，确保每次新消息追加后视口停留在最新内容：

```java
private void appendLine(String content, String bgCode) {
    logLines.add(new MessageLine(safeContent, bgCode));
    scrollToBottom();   // scrollOffset = max(0, contentLines - height)
    markDirty();
}
```

当 `contentLines <= height` 时，`scrollOffset = 0`，全部消息可见。
当 `contentLines > height` 时，`scrollOffset = contentLines - height`，仅显示尾部 `height` 行。

### 翻页静默（Page Up / Page Down）

使用 JLine 4 Widget 系统绑定按键，在 `readLine()` 期间捕获翻页键：

| 按键 | 动作 | 方法 | 实现 |
|------|------|------|------|
| **Page Up** (`key_ppage`) | 上翻一页 | `pageUp()` | `scrollOffset = max(0, scrollOffset - (height-1))` |
| **Page Down** (`key_npage`) | 下翻一页 | `pageDown()` | `scrollOffset = min(maxOffset, scrollOffset + (height-1))` |
| **Alt+P** | 上翻一页（备选） | `pageUp()` | 不冲突 JLine 默认绑定的备选键 |
| **Alt+N** | 下翻一页（备选） | `pageDown()` | 不冲突 JLine 默认绑定的备选键 |

绑定实现：

```java
var mainMap = reader.getKeyMaps().get(LineReader.MAIN);
String pageUpSeq = KeyMap.key(terminal, InfoCmp.Capability.key_ppage);
mainMap.unbind(pageUpSeq);  // 先移除 JLine 默认 history-search-backward
mainMap.bind(pageUpWidget, pageUpSeq);
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

### TuiAgentListener (v5.0 统一路由)

所有 PPAO 事件统一路由到 `MessageAreaComponent`：

```java
@Override public void onThought(String reasoningContent) {
    eventBridge.onNewThought(reasoningContent, thoughtStep.incrementAndGet(), traceId);
    // → layout.messageArea().addThought(reasoningContent, step, traceId)
    // → afterContentAdded() → layout.relayout()
}

@Override public void onAction(String target, Map<String, Object> params) {
    eventBridge.onActionExecuting(Action.builder().type(TOOL_CALL).target(desc).build(), traceId);
    // → layout.messageArea().addActionLine(desc, traceId)
    // → afterContentAdded() → layout.relayout()
}
```

### 事件路由映射

| 事件 | 去向 | 路由方法 |
|------|------|----------|
| `StartThinking` | MessageArea | `runInputLoop()` → `addUserMessage()` |
| `NewThought` | MessageArea | `messageArea.addThought(content, step, traceId)` |
| `ActionExecuting` | MessageArea | `messageArea.addActionLine(desc, traceId)` |
| `ObservationResult` | MessageArea | `messageArea.addObservationLine(summary, elapsedSec)` |
| `ChatMessage(...)` | MessageArea | `messageArea.addUser/System/Agent Message()` |
| `TaskComplete` | MessageArea | `messageArea.addAgentMessage(result)` |
| `TaskError` | MessageArea | `messageArea.addSystemMessage("错误: " + msg)` |

所有事件在内容追加后调用 `afterContentAdded()` → `layout.relayout()` → 重新计算所有组件位置。

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
- `queueRow` 位于 `messageAreaStartRow + messageAreaHeight`

---

## 🎨 配色速查

| 区域 | 前景 ANSI | 说明 |
|------|-----------|------|
| Header | `38;5;242` | 暗色延伸 `─` |
| MessageArea 用户消息 | 默认 | 纯文本 |
| MessageArea 思考推理 | `38;5;252` 浅灰 | `╸ Step N ╸` 暗灰标记 |
| MessageArea 动作 | `37` 亮白 | `▮` 前缀 |
| MessageArea 观察 | `37` 亮白 | 目录行 `38;5;222` 亮黄 |
| MessageArea Timing | `38;5;246` | 暗灰 `(Took X.Xs)` |
| LineComponent | `38;5;242` | 暗色 `─` 满行 |
| Queue line | `38;5;242` | 暗灰 `📋 N queued` |
| Footer 费用 | `48;5;208` 橙黄底 + `37` 白字 | 实体色块 |
| Footer 速率 | `48;5;35` 绿底 + `37` 白字 | 实体色块 |
| Footer 模型 | `48;5;239` 暗灰底 + `37` 白字 | 实体色块 |

---

## 🛠 架构组件一览

| 组件类 | 文件 | 职责 |
|--------|------|------|
| `HeaderComponent` | `component/HeaderComponent.java` | 顶部标题栏 |
| `MessageAreaComponent` | `component/MessageAreaComponent.java` | 统一消息流，可滚动 |
| `LineComponent` | `component/LineComponent.java` | 区域分割线 |
| `InputComponent` | `component/InputComponent.java` | 输入缓冲区模型 |
| `FooterComponent` | `component/FooterComponent.java` | 底部状态栏 |
| `TuiLayout` | `layout/TuiLayout.java` | 动态增长布局计算 |
| `ScreenManager` | `ScreenManager.java` | 全屏渲染，输入循环，事件路由，队列管理，滚动绑定 |
| `EventBridge` | `bridge/EventBridge.java` | 事件总线 |
| `TuiEvent` | `bridge/TuiEvent.java` | 密封事件类型 |
| `TuiAgentListener` | `TuiAgentListener.java` | Observer 实现，PPAO → EventBridge 转发 |

---

## 🧵 线程模型

```
┌──────────────────────┐    ┌─────────────────────────┐
│   AgentExecutor      │    │   EventBridge            │
│   (Vert.x eventloop) │    │   (sync dispatch)        │
│  fireOnThought() ────┼───→│  emit(NewThought)        │
│  fireOnAction()  ────┼───→│  emit(ActionExecuting)   │
│  fireOnObserve() ────┼───→│  emit(ObservationResult) │
└──────────────────────┘    └─────────┬────────────────┘
                                      │ dispatchToListeners()
                                      ↓
┌──────────────────────────────────────────────────────┐
│  ScreenManager (EventBridge listener)                 │
│  → messageArea.addThought() → afterContentAdded()    │
│  → messageArea.addActionLine() → afterContentAdded() │
│  → contentDirty.set(true)                            │
└──────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────┐
│  Render Thread (renderLoop)                          │
│  polling contentDirty → redrawScrollArea()           │
│  (synchronized on terminalLock)                      │
└──────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────┐
│  Main Thread (runInputLoop)                          │
│  → reader.readLine() (Page Up/Down 静默在此捕获)      │
│  → enqueue / submitTask                              │
│  → restoreLowerArea() after readLine()               │
└──────────────────────────────────────────────────────┘
```

---

## 💎 设计演进总结

| 版本 | 布局 | 滚动 | 组件数 |
|------|------|------|--------|
| v2.6 | 单滚动区 + TaoTag | auto-scroll | 3 |
| v3.0 | 四段式 TAO 区域组件 | 各区域独立滚动 | 7 |
| v3.1 | 四段式 + 队列行 | 同上 | 8 |
| v4.0 | 三区对齐 (Main/Input/Footer) | auto-scroll | 6 |
| **v5.0** | **动态增长 (Header/→ Main/[0..N]/→ Queue/→ Line1/→ Input/→ Line2/→ Footer)** | **auto-scroll + Page Up/Down 翻页** | **6** |

v5.0 关键变化：
1. **布局序列变更**: `Header → Main Area [0..N] → QueueMsg [0..1] → Line1 → Input → Line2 → Footer`
2. **动态增长**: Main Area 高度 = `max(terminalHeight - 6, 1)` 始终填满可用空间
3. **滚动静默**: Page Up / Page Down / Alt+P / Alt+N 通过 JLine Widget 系统绑定，覆盖默认 history-search
4. **交替屏幕缓冲**: 启动时 `\033[?1049h` 进入交替缓冲，关闭时 `\033[?1049l` 恢复主缓冲，防止终端滚动缓冲区捕获渲染历史
5. **内容变化自动重布局**: `afterContentAdded()` → `layout.relayout()` 实时调整所有组件位置
