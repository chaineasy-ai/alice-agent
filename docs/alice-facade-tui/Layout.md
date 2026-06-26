# JLine 3 三层TUI架构 v2.3 工程设计文档
```markdown
---
title: "TUI Layout - Single-line Cut + Inline Completion"
summary: "基于 JLine 3 实现三层单行分割布局，支持行内自动补全"
read_when:
  - "TUI 布局新增/改造开发"
scope:
  - "alice-facade-tui"
status: "active"
updated: "2026-06-26"
---
# JLine 3 三层TUI架构 v2.3 工程设计文档
## 前言
本版为极致净化重构 v2.3，剔除冗余数学公式、无效符号；全面落地**等宽实体背景色块**、**零噪音输入视口**，兼顾黑客视觉美学与工程稳定落地。
方案完整保留三大底层硬性约束：
1. 绝对边界固定
2. 无画面抖动
3. 低开销增量重绘

重构思路：废弃传统拼接符号、冗余提示文本，统一采用纯净分割线、ANSI实体背景色块、无干扰输入区的现代化终端交互方案。

---

## 🎨 优化后TUI布局设计（v2.3 终极工业版）
### 7.1 沉浸式三看板常态布局（TAO Standard Mode）
#### 进化亮点
1. **内容区全色块化**
   废弃 `[T Thought]` 文本前缀，替换为等宽满背景填充实体矩形标签，消除界面碎屑，视觉信息抓取效率大幅提升。
2. **顶部无噪细线**
   删除会话ID等冗余文本，分割线动态自适应终端宽度延伸至视口最右侧，界面留白透气。
3. **零干扰输入行**
   移除 `>` / `$` 传统提示符、静态占位文本，仅保留光标静默闪烁的纯净输入区域。
4. **底部TAO实体仪表盘**
   指标数据全部纯色背景包裹，形成三块物理隔离独立色块。

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
> 说明：示例中 `THOUGHT` / `ACTION` / 底部仪表盘区域，运行时为 ANSI 背景色全填充纯色块，文字内嵌色块内展示。

### 7.2 `/model` 指令：输入行内嵌补全列表（Inline Completion Mode）
#### 进化亮点
1. **全局高度锁定**
   补全下拉菜单展开时，底部状态栏物理坐标固定，无上下位移。
2. **光标行贴合布局**
   补全列表紧贴输入行下边缘，反显高亮标记当前选中候选项。

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
### 1. 补全菜单边界防御策略
不引入上层业务高度计算，直接通过 JLine3 内置变量硬编码限制内嵌补全菜单最大渲染行数；超出阈值自动内部滚动，从底层锁定渲染边界，彻底规避状态栏被顶出、界面闪烁问题。
```java
// 边界防御核心：补全菜单最大渲染3行，溢出滚动，防止底部状态栏溢出视口
reader.setVariable(LineReader.LIST_MAX, 3);
reader.setVariable(LineReader.MENU_COMPLETE, true);
```

### 2. 零提示符纯净输入实现
调用底层 `LineReader.readLine()` 时传入空提示符字符串，配合 Java 25 原生终端API实现无冗余字符输入视口。
```java
// 移除提示符噪音，仅保留光标
String userInput = reader.readLine(""); 
```

### 3. 并发日志分流渲染机制
1. **日志输出规范**
   禁止直接调用 `System.out.println()`；所有 TAO 业务日志由独立后台线程采集，统一通过 `reader.printAbove(logLine)` 输出。
2. **底层渲染原理**
   `printAbove` 自带原生双缓冲打印逻辑：擦除输入框上方渲染区、追加日志滚动后，自动还原输入视口原始坐标，保证输入上下文不丢失。

### 4. 状态栏与TAO标签实体色块渲染方案
摒弃字符拼接模拟边框，统一使用 **ANSI 256色背景控制码 `48;5;xxxm`** 生成满宽填充矩形色块；支持终端窗口 `WINCH` 缩放信号自适应重绘定位。

#### 4.1 TAO标签色块枚举核心实现
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

    /** 输出带背景色块的完整ANSI文本 */
    public String render() {
        return this.bgAnsi + this.fgAnsi + this.text + "\u001B[0m";
    }
}
```

#### 4.2 底部状态栏动态定位逻辑
状态刷新执行流程：
1. 光标定位：`\u001B[${terminal.getHeight()};1H`，动态跳转至终端最后一行首列，规避固定行号缩放错位；
2. 行清空：下发 `\u001B[K` 清除当前行残留字符；
3. 单行覆盖写入多色块拼接文本流。

ANSI输出示例：
```text
\u001B[48;5;208m\u001B[30m  💰 $0.041  \u001B[0m  \u001B[48;5;35m\u001B[30m  📊 125 t/s  \u001B[0m
```

---

## 💎 设计演进核心总结
1. **色块驱动，去符号化**
   完全移除 `[T]`、`>`、`[Session]` 碎片化标记，统一等宽实体色块+极简分割线，视觉标准对齐工业级原生控制台。
2. **底层变量锁死空间边界**
   不依赖复杂高度计算公式，通过 `LIST_MAX` 原生参数控制渲染范围，降低业务维护成本。
3. **轻量极简实现**
   纯 Java 25 + JLine3 原生API构建，无重型第三方TUI框架依赖；核心渲染代码总量 ≤ 300 行，迭代、缺陷修复成本极低。
```