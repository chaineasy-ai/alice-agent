# JLine 3 三层TUI架构 v2.6 工程设计文档
```markdown
---
title: "TUI Layout - v2.6 Expert"
summary: "基于 JLine 4 实现四层布局，Footer 在终端最底行，输入区位于 Footer 上方"
read_when:
  - "TUI 布局新增/改造开发"
scope:
  - "alice-facade-tui"
status: "active"
updated: "2026-06-27"
---
# JLine 4 四层TUI架构 v2.6 工程设计文档
## 前言
本版为 v2.6 稳定版，修复首次输入光标定位、渲染线程与 JLine readLine() 的终端输出竞态。

布局核心变更：Footer 移至终端最底行 (H-1)，输入区在其上方 (H-3)，由两条分割线包裹。
JLine 的 `reader.readLine()` 在输入行渲染，`LINE_OFFSET=2` 保留下方分隔线和 Footer。

方案完整保留三大底层硬性约束：
1. 绝对边界固定
2. 无画面抖动
3. 低开销增量重绘

---

## 🎨 优化后TUI布局设计（v2.6）
### 7.1 沉浸式四区域常态布局
#### 布局效果示意
```text
 🤖 alice-agent v0.60.0 ────────────────────────────────────────────────────────────────────
  THOUGHT  监测到 uncommitted 悬空状态，自动触发双式记账平衡等式校验。
  THOUGHT  校验断言 ∑Debit = ∑Credit 失败，潜在风险指向 Double-Entry 借贷不平衡。
  ACTION   调用本地 Bash 执行器: $ gradle :cland-chainpay:test --tests "AccountLedgerTest"
  OBSERVE  BUILD SUCCESSFUL in 3s (1 test passed)
  THOUGHT  单元测试断言通过，判定悬空流水为 Clearing 延迟，账目底层等式实质守恒。
 ───────────────────────────────────────────────────────────────────────────────────────────
  █
 ───────────────────────────────────────────────────────────────────────────────────────────
  █████████████  █████████████  ███████████████████████ ── 🔌 Active: cland-pay-mcp
```
> 分区规则（自顶向下）：Header(1) → 上方滚动区 → 分割线(1) → 输入区(1) → 分割线(1) → Footer(1)

### 7.2 `/model` 指令：输入行内嵌补全列表（Inline Completion Mode）
#### 进化亮点
1. **全局高度锁定**
   补全下拉菜单展开时，底部 Footer 物理坐标固定，无上下位移。
2. **光标行贴合布局**
   补全列表紧贴输入行下方（由 LINE_OFFSET=2 保护的分割线之上）。

#### 补全布局效果示意
```text
 ───────────────────────────────────────────────────────────────────────────────────────────
  /model █
    deepseek-v4-flash • medium       (Current)
  █ deepseek-v4-reasoning • deep 
    gpt-4o-mini
 ───────────────────────────────────────────────────────────────────────────────────────────
  █████████████  █████████████  ███████████████████████ ── 🔌 Active: cland-pay-mcp
```

---

## 🛠 架构与工程实现重设计（Tactical Blueprint）
### 1. 首次输入光标定位策略
JLine 的首次 `reader.readLine()` 调用会初始化显示层并覆盖手动光标定位。

修复方案：
1. 在 synchronized 块前调用 `terminal.getHeight()` 创建终端 I/O 同步点，确保终端完成处理所有先前输出
2. 使用原始 ANSI `\033[%d;1H` 定位光标（不更新 JLine 内部跟踪），然后 `\033[2K` 清行后 `flush()`
3. 在 `reader.setVariable(LINE_OFFSET, 2)` 前调用 `reader.getVariable(LINE_OFFSET)` 预热 JLine 变量系统
4. `LINE_OFFSET=2` 确保 JLine 首次初始化时计算显示位置为 `terminalHeight - 1 - 2 = H-3 = inputRow`

```java
int inputRow = layout.inputRow();
int termH = terminal.getHeight();
reader.getVariable(LineReader.LINE_OFFSET);

synchronized (terminalLock) {
    terminal.writer().write(String.format("\033[%d;1H", inputRow + 1));
    terminal.writer().write("\033[2K");
    terminal.writer().flush();
}

reader.setVariable(LineReader.LINE_OFFSET, 2);
inputActive.set(true);
String line = reader.readLine(layout.input().prompt());
```

### 2. 渲染线程与 readLine() 输出竞态防御
渲染线程的 `redrawScrollArea()` 与主线程的 `reader.readLine()` 同时写入终端输出流时，
会导致光标跳跃、内容错乱。

防御策略：
- 新增 `inputActive` 原子标记，渲染循环在 `inputActive=true` 时跳过终端写入，将重绘标记记录到 `pendingRedraw`
- 主线程在 `readLine()` 返回后在 `terminalLock` 下处理 deferred 重绘

```java
// 渲染循环
if (inputActive.get()) {
    if (contentDirty.get()) {
        pendingRedraw.set(true);
        contentDirty.set(false);
    }
} else if (contentDirty.compareAndSet(true, false)) {
    redrawScrollArea();
}

// 输入循环
inputActive.set(true);
try {
    line = reader.readLine(emptyPrompt);
} finally {
    inputActive.set(false);
}
if (pendingRedraw.compareAndSet(true, false)) {
    contentDirty.set(true);
    redrawScrollArea();
}
```

### 3. 补全菜单边界防御策略
不引入上层业务高度计算，直接通过 JLine 内置变量硬编码限制内嵌补全菜单最大渲染行数；
超出阈值自动内部滚动，从底层锁定渲染边界，彻底规避 Footer 被顶出、界面闪烁问题。
```java
reader.setVariable(LineReader.LIST_MAX, 3);
```

### 4. 零提示符纯净输入实现
```java
String userInput = reader.readLine("");
```

### 5. 并发日志分流渲染机制
1. **日志输出规范**
   禁止直接调用 `System.out.println()`；所有 TAO 业务日志由独立后台线程采集，统一通过 `reader.printAbove(logLine)` 输出。
2. **底层渲染原理**
   `printAbove` 自带原生双缓冲打印逻辑：擦除输入框上方渲染区、追加日志滚动后，自动还原输入视口原始坐标，保证输入上下文不丢失。

### 6. 状态栏与TAO标签实体色块渲染方案
摒弃字符拼接模拟边框，统一使用 **ANSI 256色背景控制码 `48;5;xxxm`** 生成满宽填充矩形色块；
支持终端窗口 `WINCH` 缩放信号自适应重绘定位。

#### 6.1 TAO标签色块枚举核心实现
```java
public enum TaoTag {
    THOUGHT(" THOUGHT ", "\u001B[48;5;239m", "\u001B[37m"),  // 暗灰底 白色文字
    ACTION( " ACTION  ", "\u001B[48;5;214m", "\u001B[30m"),  // 橙黄底 黑色文字
    OBSERVE(" OBSERVE ", "\u001B[48;5;35m",  "\u001B[30m");  // 绿色底 黑色文字

    private final String text;
    private final String bgAnsi;
    private final String fgAnsi;

    TaoTag(String text, String bgAnsi, String fgAnsi) {
        this.text = text;
        this.bgAnsi = bgAnsi;
        this.fgAnsi = fgAnsi;
    }

    public String render() {
        return this.bgAnsi + this.fgAnsi + this.text + "\u001B[0m";
    }
}
```

#### 6.2 底部 Footer 定位逻辑
Footer 固定在终端最底行 (H-1)。重绘流程：
1. `cursorLine(H-1)` 定位到终端最后一行
2. `\033[K` 清除当前行残留字符
3. 单行覆盖写入多色块拼接文本流

ANSI输出示例：
```text
\u001B[48;5;208m\u001B[30m  💰 $0.041  \u001B[0m  \u001B[48;5;35m\u001B[30m  📊 125 t/s  \u001B[0m
```

### 7. `restoreLowerArea()` 恢复被补全菜单覆盖的区域
JLine 的 AUTO_MENU 补全菜单在输入行上方渲染，可能覆盖分割线和 Footer。
每次 `readLine()` 返回后无条件恢复覆盖的区域。

v2.6 恢复顺序：
1. `cursorLine(separator2Row)` — 重绘输入区下方的分割线
2. `cursorLine(footerRow)` — 重绘 Footer

### 8. 键盘快捷键
- **F5 / Ctrl+C**: 中断当前任务（`InterruptCmd`）
- **Ctrl+D**: 退出 TUI
- **Page Up / Page Down**: 滚动日志区
- **Up / Down**: 输入历史浏览

---

## 💎 设计演进核心总结
1. **色块驱动，去符号化**
   完全移除 `[T]`、`>`、`[Session]` 碎片化标记，统一等宽实体色块+极简分割线，视觉标准对齐工业级原生控制台。
2. **底层变量锁死空间边界**
   不依赖复杂高度计算公式，通过 `LIST_MAX` 原生参数控制渲染范围，降低业务维护成本。
3. **终端 I/O 同步保障首次定位**
   `terminal.getHeight()` 作为终端 I/O 同步点，确保首次 readLine 前光标已正确到位。
4. **输入活跃标记防止渲染竞态**
   `inputActive` / `pendingRedraw` 双标记系统，渲染循环在输入期间不写入终端，deferred 重绘在输入处理后安全执行。
```