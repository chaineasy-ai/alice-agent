# 动态增长终端布局 v5.0（适配统一 MessageArea + 滚动查看历史）

## 布局结构

```
 ┌─ Header ───────────────────────────────────────────┐
 │  🤖 alice-agent v0.60.0 ─────────────────────────── │  ← HeaderComponent (ANSI 38;5;242 暗色分割线)
 │                                                     │  ← MessageAreaComponent (统一滚动消息流)
 │ together debug current program...                   │  [0..N] 动态 — 始终填满可用空间
 │ ╸ Step 1 ╸                                         │     用户消息 (默认终端色, 纯文本)
 │ analyzing the request...                            │     思考推理 (浅灰字 ╸ Step N ╸)
 │ ⮞ TOOL_CALL: list_dir ({path:.})                   │     动作执行 (亮白字 ▮ 前缀)
 │ ⮞ -rw------- 1 alice alice 111 config.json         │     观察结果 (亮白字, 目录亮黄)
 │ ⮞ (Took 0.0s)                                       │
 ├─────────────────────────────────────────────────────┤
 │  📋 2 queued messages                               │  ← QueueMsg [0..1] (ANSI 38;5;242, 行预留)
 ├─────────────────────────────────────────────────────┤  ← LineComponent 1
 │  █                                                  │  ← InputComponent (JLine readLine 管理)
 ├─────────────────────────────────────────────────────┤  ← LineComponent 2
 │  [💰 $0.041] [📊 125 t/s] [🧠 deepseek-v4-flash]    │  ← FooterComponent (ANSI 48;5;XXX 实体色块)
 └─────────────────────────────────────────────────────┘
```

## 一、区域说明

### 1. Header

| 组件 | 文件 | 说明 |
|------|------|------|
| `HeaderComponent` | `component/HeaderComponent.java` | `🤖 alice-agent v0.60.0 ──` 暗色延伸 |

### 2. Main Area [0..N]

| 组件 | 文件 | 说明 |
|------|------|------|
| `MessageAreaComponent` | `component/MessageAreaComponent.java` | 统一消息流，自动滚动，Page Up/Down 翻页 |

所有消息类型按时间序排列，使用终端默认背景：

| 消息类型 | 前景 | 附加标记 | 添加方法 |
|----------|------|----------|----------|
| 用户消息 | 默认终端色 | — | `addUserMessage(text)` |
| 思考推理 | `38;5;252` 浅灰 | `╸ Step N ╸` | `addThought(text, step, traceId)` |
| 动作执行 | `37` 亮白 | `▮` 前缀 | `addActionLine(desc, traceId)` |
| 观察结果 | `37` 亮白 | — | `addObservationLine(text, seconds)` |
| 系统消息 | 默认终端色 | — | `addSystemMessage(text)` |
| Agent 消息 | 默认终端色 | 剥除 `[FINISH]` | `addAgentMessage(text)` |

### 3. QueueMsg [0..1]

| 组件 | 说明 |
|------|------|
| (inline) | `📋 N queued messages` 或空白，行始终预留 |

### 4. Input Area

| 组件 | 文件 | 说明 |
|------|------|------|
| `LineComponent` | `component/LineComponent.java` | ANSI `38;5;242` 暗色 `─` 满行分割线 |
| `InputComponent` | `component/InputComponent.java` | 输入行，实际 I/O 由 JLine 4 管理 |

### 5. Footer

| 组件 | 文件 | 说明 |
|------|------|------|
| `LineComponent` | `component/LineComponent.java` | ANSI `38;5;242` 暗色 `─` 满行分割线 |
| `FooterComponent` | `component/FooterComponent.java` | 三个实体色块 + 工具信息 |

## 二、滚动与历史查看

### 自动滚动

每次消息追加触发 `scrollToBottom()`：`scrollOffset = max(0, contentLines - height)`

- `contentLines <= height` → `scrollOffset = 0`，全部可见
- `contentLines > height` → `scrollOffset = contentLines - height`，尾 N 行可见

### 翻页静默

| 按键 | 动作 | 方法 |
|------|------|------|
| **Page Up** / **Alt+P** | 上翻一页 | `pageUp()` |
| **Page Down** / **Alt+N** | 下翻一页 | `pageDown()` |

使用 JLine 4 Widget 系统绑定，先 `unbind()` 移除默认 history-search，再 `bind()` 到自定义 scroll widget。

## 三、端子布局计算公式（Terminal H × W）

```
固定行数 = HEADER(1) + QUEUE(1) + SEP(1) + INPUT(1) + SEP(1) + FOOTER(1) = 6
消息区高度 = max(H - 6, 1)
```

80×24 终端：
```
row  0:  HeaderComponent      (1行)
row  1-18: MessageAreaComponent (18行)
row 19:  QueueMsg             (1行, 空白 or "📋 2 queued messages")
row 20:  LineComponent 1       (1行)
row 21:  InputComponent        (1行)
row 22:  LineComponent 2       (1行)
row 23:  FooterComponent       (1行)
```

## 四、ANSI 配色速查

| 区域 | 前景 ANSI | 说明 |
|------|-----------|------|
| Header | `38;5;242` | 暗色分割线 `─` |
| MessageArea 用户消息 | 默认 | 纯文本 |
| MessageArea 思考推理 | `38;5;252` 浅灰 | `╸ Step N ╸` 暗灰标记 |
| MessageArea 动作 | `37` 亮白 | `▮` 前缀 |
| MessageArea 观察 | `37` 亮白 | — |
| MessageArea Timing | `38;5;246` | 暗灰 `(Took X.Xs)` |
| LineComponent | `38;5;242` | 暗色 `─` 满行 |
| Queue line | `38;5;242` | 暗灰 `📋 N queued` |
| Footer 费用 | `48;5;239` 暗灰底 + `37` | 统一暗灰色块 |
| Footer 速率 | `48;5;239` 暗灰底 + `37` | 统一暗灰色块 |
| Footer 模型 | `48;5;239` 暗灰底 + `37` | 统一暗灰色块 |

## 五、关键代码入口

| 文件 | 职责 |
|------|------|
| `component/MessageAreaComponent.java` | 统一消息流 + 滚动 |
| `component/LineComponent.java` | 区域分割线 |
| `component/FooterComponent.java` | 底部状态栏 |
| `layout/TuiLayout.java` | 动态增长布局计算 (`recalculate(w,h,contentLines)`, `relayout()`) |
| `ScreenManager.java` | 事件路由 + 全屏渲染 + 滚动绑定 (`setupScrollBindings()`) |
| `AliceTuiLauncher.java` | PPAO consumer 注册 + EventBridge 接线 |

## 六、v4.0 → v5.0 迁移对照

| v4.0 (三区对齐) | v5.0 (动态增长) | 说明 |
|-----------------|-----------------|------|
| 固定 `messageAreaHeight = H - 6` | `mainHeight = max(maxAvailable, 1)` | 动态填满可用空间 |
| Queue 在 Separator 下方 | Queue 在 Separator 上方 | 序列变更: Header → Main → Queue → Line1 → Input → Line2 → Footer |
| 无滚动绑定 | Page Up/Down + Alt+P/Alt+N | JLine Widget 系统绑定 |
| 主缓冲 | 交替屏幕缓冲 `\033[?1049h/l` | 防止终端滚动缓冲区污染 |
| `recalculate(w, h)` | `recalculate(w, h, contentLines)` + `relayout()` | 内容变更后自动重布局 |
