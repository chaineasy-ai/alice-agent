---
title: "TUI Event System"
summary: "TUI 事件系统 — TuiEvent 密封层次、EventBridge 投递机制、内容状态机、TerminalResize 事件完整说明书"
read_when:
  - "implementing or modifying TUI event handling"
  - "adding new TuiEvent subtypes"
  - "understanding TUI content refresh / render loop"
  - "debugging TUI resize or rendering issues"
scope:
  - alice-facade-tui
status: "active"
updated: "2026-06-26"
---

# TUI 事件系统

## 目录

1. [架构概览](#1-架构概览)
2. [TuiEvent 密封层次](#2-tuievent-密封层次)
3. [EventBridge 投递机制](#3-eventbridge-投递机制)
4. [内容状态机](#4-内容状态机)
5. [渲染循环](#5-渲染循环)
6. [TerminalResize 事件](#6-terminalresize-事件)
7. [事件流时序图](#7-事件流时序图)
8. [添加新事件类型](#8-添加新事件类型)

---

## 1. 架构概览

TUI 事件系统是 Agent 核心与终端界面之间的桥梁，负责将 Agent 的 PPAO 生命周期事件和终端交互事件转换为 UI 更新。核心组件：

```
┌─────────────┐     TuiEvent      ┌──────────────┐    contentDirty    ┌──────────────┐
│  AgentCore  │ ──── emit/ ──────>│ EventBridge  │ ──── true ───────>│  RenderLoop  │
│  Terminal   │     emitSync       │ (Listener[]) │                   │  (100ms)     │
└─────────────┘                    └──────────────┘                   └──────┬───────┘
                                                                           │
                                                                   redrawScrollArea()
                                                                           │
                                                                   ┌───────▼───────┐
                                                                   │  Terminal     │
                                                                   │  (ANSI write) │
                                                                   └───────────────┘
```

- **TuiEvent** — 密封类层次，8 种子类型（含 TerminalResize）
- **EventBridge** — 事件总线，维护 `CopyOnWriteArrayList<Consumer<TuiEvent>>`
- **内容状态机** — `contentDirty` / `needsFullClear` 原子标记驱动物理渲染
- **ScreenManager.setupEventListeners()** — 事件 → UI 组件的唯一映射点

---

## 2. TuiEvent 密封层次

```java
public abstract sealed class TuiEvent {

    // ── Agent 生命周期事件 ──
    public static final class StartThinking extends TuiEvent { String prompt(); }
    public static final class NewThought    extends TuiEvent { String thought(); int step(); }
    public static final class ActionExecuting extends TuiEvent { Action action(); }
    public static final class ObservationResult extends TuiEvent { String summary(); }
    public static final class TaskComplete   extends TuiEvent { String result(); String summary(); }
    public static final class TaskError      extends TuiEvent { String errorMessage(); }

    // ── UI 交互事件 ──
    public static final class ChatMessage    extends TuiEvent { String sender(); String content(); }
    public static final class TokenUpdate    extends TuiEvent { int tokenCount(); String status(); }

    // ── 终端交互事件 ──
    public static final class TerminalResize extends TuiEvent { int width(); int height(); }

    // ── 桥接事件 ──
    public static final class EnvBridgeEvent extends TuiEvent { EnvEvent envEvent(); }
}
```

### 事件分类

| 类别 | 事件 | 投递方式 | 触发方 |
|------|------|----------|--------|
| Agent 生命周期 | `StartThinking`, `NewThought`, `ActionExecuting`, `ObservationResult`, `TaskComplete`, `TaskError` | `emit()` (异步) | Agent 执行器线程 |
| UI 交互 | `ChatMessage` | `emitSync()` (同步) | Agent 线程 / 恢复逻辑 |
| 终端交互 | `TerminalResize` | `emitSync()` (同步) | WINCH handler / 轮询 |
| 桥接 | `EnvBridgeEvent` | `emit()` (异步) | EnvAdapter |

### 选择 emit vs emitSync

```
emit()         → 入队到 alice-event-bridge 单线程 → 异步回调监听器
emitSync()     → 调用线程上直接回调监听器（阻塞调用者）
```

- **emitSync 用于**: 需要在下一次 `redrawScrollArea()` 前完成的 UI 更新（`ChatMessage`, `TerminalResize`）
- **emit 用于**: 可延迟的 UI 更新，减少对 Agent 执行线程的阻塞

---

## 3. EventBridge 投递机制

```java
public class EventBridge {
    List<Consumer<TuiEvent>> listeners;      // CopyOnWriteArrayList
    ExecutorService eventThread;             // 单线程 daemon

    public void emit(TuiEvent event) {
        eventThread.submit(() -> dispatchToListeners(event));
    }

    public void emitSync(TuiEvent event) {
        dispatchToListeners(event);          // 调用线程直接执行
    }

    private void dispatchToListeners(TuiEvent event) {
        for (var listener : listeners) {
            try { listener.accept(event); } catch (Exception e) { /* logged */ }
        }
    }
}
```

### 便利方法

| 方法 | 对应事件 | 投递 |
|------|----------|------|
| `onStartThinking(prompt)` | `StartThinking` | `emit` |
| `onNewThought(thought, step)` | `NewThought` | `emit` |
| `onActionExecuting(action)` | `ActionExecuting` | `emit` |
| `onObserved(summary)` | `ObservationResult` | `emit` |
| `onTaskComplete(result, summary)` | `TaskComplete` | `emit` |
| `onTaskError(errorMessage)` | `TaskError` | `emit` |
| `onChatMessage(sender, content)` | `ChatMessage` | `emitSync` |
| `onTokenUpdate(count, status)` | `TokenUpdate` | `emit` |
| `onTerminalResize(width, height)` | `TerminalResize` | `emitSync` |
| `onEnvEvent(envEvent)` | `EnvBridgeEvent` | `emit` |

---

## 4. 内容状态机

TUI 渲染由两个原子标记驱动：

```
  contentDirty (AtomicBoolean)        needsFullClear (AtomicBoolean)
       │                                      │
       │ true                                 │ true
       ▼                                      ▼
  redrawScrollArea()              ┌─ redrawScrollArea() 先执行
  重绘 Header                    │   \033[2J\033[H 全屏清除
  + Thought                      │─ 仅在 TerminalResize 时置 true
  + Separator1                   │
  + Separator2                   │
  + Footer                       │
       │                          │
       │                          │
       ▼                          │
  contentDirty = false ◄──────────┘
```

### 状态转换

```
IDLE (contentDirty=false)
  │
  │ 任何事件 → listener → contentDirty=true
  ▼
DIRTY (contentDirty=true)
  │
  │ RenderLoop 100ms tick → compareAndSet(true→false) 成功
  ▼
REDRAWING (redrawScrollArea 执行中)
  │  - synchronized(terminalLock)
  │  - if needsFullClear → ANSI_CLEAR_SCREEN
  │  - 逐行绘制 Header/Thought/Separator1/Separator2/Footer
  │
  ▼
IDLE (contentDirty=false)
```

### 标记设置位置

| 标记 | 设置者 | 消费者 |
|------|--------|--------|
| `contentDirty` | EventBridge 监听器 (所有事件), WINCH handler, 轮询, `markContentDirty()` | RenderLoop, `runInputLoop()` |
| `needsFullClear` | TerminalResize 监听器 (由 WINCH 或 Poll 触发) | `redrawScrollArea()` |

---

## 5. 渲染循环

```java
// RenderLoop (alice-tui-render daemon thread, 100ms tick)
while (running) {
    // 1. 内容脏检查 → 增量重绘
    if (contentDirty.compareAndSet(true, false)) {
        redrawScrollArea();
    }

    // 2. 每 500ms 轮询终端尺寸 (WINCH fallback)
    if (++frameCount % 5 == 0) {
        if (terminal.getWidth() != lastPollWidth || terminal.getHeight() != lastPollHeight) {
            eventBridge.onTerminalResize(w, h);
        }
    }

    sleep(100ms);
}
```

### 渲染范围

`redrawScrollArea()` 绘制区域：

```
┌─ row 0:  Header   ── 始终保持
├─ row 1..N: Thought ── 滚动日志区
├─ row N+1: Separator1
│  (Input 由 JLine LineReader 管理，不在此处绘制)
├─ row N+3: Separator2
└─ row N+4: Footer   ── 始终保持
```

输入行 (Input) 由 JLine 内部管理：
- `reader.setVariable(LineReader.LINE_OFFSET, layout.inputRow())` 控制绘制行号
- TerminalResize 时立即更新 LINE_OFFSET，避免输入在旧位置残留

### 终端输出同步

```
terminalLock (Object)
  ├── redrawScrollArea()    ── synchronized(terminalLock)
  ├── restoreLowerArea()    ── synchronized(terminalLock)
  └── runInputLoop() 光标   ── synchronized(terminalLock)
```

所有对 `terminal.writer()` 的写入都在 `terminalLock` 下执行，防止：
- 渲染线程的 ANSI 写入与 JLine `readLine()` 内部光标定位交错
- `restoreLowerArea()` 与 `redrawScrollArea()` 覆盖彼此的输出

---

## 6. TerminalResize 事件

### 检测路径

```
终端 resize 发生
    │
    ├──[路径 A] SIGWINCH 信号 → terminal.handle(WINCH, handler)
    │              handler: eventBridge.onTerminalResize(w, h)
    │
    └──[路径 B] 轮询保底 (每 500ms)
                   terminal.getWidth() / getHeight()
                   → 与 lastPollWidth/lastPollHeight 比较
                   → 不同 → eventBridge.onTerminalResize(w, h)
```

### JLine provider 影响

| Provider | WINCH 支持 | 优先级 |
|----------|-----------|--------|
| `ffm` (Panama FFM) | ✅ 原生 tty，信号可靠 | 1st |
| `exec` (子进程) | ⚠️ 依赖子进程转发 | 2nd |
| `jni` | ✅ 原生 | 3rd |
| `dumb` | ❌ 固定尺寸 | last |

当前配置 (`AliceTuiLauncher`): `ffm,exec,jni,dumb`
- JDK 25 + `--enable-native-access=ALL-UNNAMED` → FFM 可用
- GraalVM / FFM 不可用时自动回退到 exec

### 事件处理流程

```
TerminalResize(w, h) 事件
    │
    │ emitSync → Listener
    ▼
ScreenManager 监听器:
    1. layout.recalculate(w, h)
       ├── Header.setBounds(0, 0, w, 1)
       ├── Thought.setBounds(1, 0, w, h-5)
       │   └── thought.onResize(oldContentHeight)
       │       ├── 变大 → scrollOffset -= delta (揭示上方隐藏内容)
       │       └── 变小 → scrollOffset += delta (锚定底部)
       ├── Input.setBounds(inputRow, 0, w, 1)
       └── Footer.setBounds(footerRow, 0, w, 1)

    2. reader.setVariable(LINE_OFFSET, layout.inputRow())
       → JLine 下次 redisplay 时输入行定位到正确位置

    3. lastPollWidth = w, lastPollHeight = h
       → 避免轮询重复触发

    4. needsFullClear = true
       → 下次 redrawScrollArea 先全屏清除旧像素

    5. contentDirty = true
       → 触发 RenderLoop 重绘
```

### 布局计算公式

```
FIXED_ROWS = 5   (Header + Separator1 + Input + Separator2 + Footer)

contentStartRow  = 1
contentHeight    = terminalHeight - 5
separator1Row    = contentHeight + 1
inputRow         = separator1Row + 1
separator2Row    = inputRow + 1
footerRow        = separator2Row + 1
lastRow          = footerRow
```

---

## 7. 事件流时序图

```
    User          TUI Main       Agent        EventBridge      RenderLoop     Terminal
     │               │              │              │               │              │
     │  "hello"      │              │              │               │              │
     │──────────────>│              │              │               │              │
     │               │ submitTask   │              │               │              │
     │               │─────────────>│              │               │              │
     │               │              │              │               │              │
     │               │   StartThinking             │               │              │
     │               │<─────────────│──emit───────>│               │              │
     │               │              │              │─listener─────>│              │
     │               │              │              │ contentDirty  │              │
     │               │              │              │   = true      │              │
     │               │              │              │               │──redraw─────>│
     │               │              │              │               │              │
     │               │   NewThought │              │               │              │
     │               │<─────────────│──emit───────>│               │              │
     │               │              │              │─listener─────>│              │
     │               │              │              │ contentDirty  │              │
     │               │              │              │               │──redraw─────>│
     │               │              │              │               │              │
     │               │   TaskComplete              │               │              │
     │               │<─────────────│──emit───────>│               │              │
     │               │              │              │─listener─────>│              │
     │               │              │              │ contentDirty  │              │
     │               │              │              │               │──redraw─────>│
     │               │              │              │               │              │
     │  ── resize ──────────────────────────────────────────────────────────────>│
     │               │              │              │               │              │
     │               │      WINCH / Poll           │               │              │
     │               │─────────────────────────────│               │              │
     │               │              │onTerminalResize(w,h)  emitSync              │
     │               │              │<─────────────│               │              │
     │               │              │  listener: recalculate                     │
     │               │              │  LINE_OFFSET update                        │
     │               │              │  needsFullClear=true                       │
     │               │              │  contentDirty=true                         │
     │               │              │              │               │              │
     │               │              │              │               │──redraw─────>│
     │               │              │              │               │ CLEAR_SCREEN │
     │               │              │              │               │ draw all new │
     │               │              │              │               │ positions    │
```

---

## 8. 添加新事件类型

1. **定义事件类** — 在 `TuiEvent.java` 的 `sealed` 层次中添加新的 `static final class`
2. **EventBridge 便利方法** — 添加 `onXxx(...)` → `emit(...)` 或 `emitSync(...)`
3. **监听器处理** — 在 `ScreenManager.setupEventListeners()` 的 switch 中添加 case
4. **设置 contentDirty** — 事件监听器中 `contentDirty.set(true)` 触发渲染

### 示例：TerminalResize

```java
// 1. TuiEvent.java
public static final class TerminalResize extends TuiEvent {
    private final int width;
    private final int height;
    public TerminalResize(int width, int height) { ... }
    public int width() { return width; }
    public int height() { return height; }
}

// 2. EventBridge.java
public void onTerminalResize(int width, int height) {
    emitSync(new TuiEvent.TerminalResize(width, height));
}

// 3. ScreenManager.setupEventListeners()
case TuiEvent.TerminalResize e -> {
    layout.recalculate(e.width(), e.height());
    reader.setVariable(LineReader.LINE_OFFSET, layout.inputRow());
    needsFullClear.set(true);
    contentDirty.set(true);
}
```

### 关键原则

- **同步 vs 异步**: 影响 UI 布局的事件用 `emitSync`，纯内容更新用 `emit`
- **线程安全**: 监听器可能从任意线程回调（信号线程、Agent 线程、EventBridge 线程），操作共享状态需注意
- **幂等性**: `contentDirty.set(true)` 是幂等的，多次设置不会导致重复渲染
- **全屏清除**: 只有 resize 类事件需要 `needsFullClear`，内容更新仅需 `contentDirty`
