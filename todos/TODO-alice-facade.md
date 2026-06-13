---
title: "TODO - Alice Facade"
summary: "Task board for facade modules: alice-facade-cmd and alice-facade-tui"
read_when:
  - "tracking or updating facade layer tasks"
  - "working on CLI or TUI frontend implementation"
scope:
  - "alice-facade-cmd"
  - "alice-facade-tui"
status: "active"
updated: "2026-06-13"
---
# TODO-alice-facade: 门面模块加固与 JLine 3 交互式 CLI

> 遵循 [GFM Markdown 任务列表规范](./TODO-spec.md)
> 设计文档:
>   [docs/alice-facade-cmd/DESIGN.md](./docs/alice-facade-cmd/DESIGN.md)
>   [docs/alice-facade-tui/DESIGN.md](./docs/alice-facade-tui/DESIGN.md)
>   [docs/alice-agent-command/DESIGN.md](./docs/alice-agent-command/DESIGN.md)
>
> 格式约定：`- [ ]` 待办 | `- [x]` 已完成 | `- [/]` 执行中 | `- [-]` 已取消 | `- [!]` 失败/阻塞
> 行内元数据：`[key:: value]`
> 缩进 4 空格 = 子任务层级

---

# 目标：使用 JLine 3 重构 CLI 交互式模式 + 补齐 TUI/CLI 分发链路

## 背景

1. **CLI (`alice-facade-cmd`)**: 当前仅支持一次性 `run` 子命令（picocli 解析），缺少**交互式 chat 模式**。
   `chat` 子命令的状态为 `"not yet implemented"`。需使用 JLine 3 实现行编辑、命令历史、Tab 补全等能力。

2. **TUI (`alice-facade-tui`)**: 已依赖 JLine 3 但**未实际使用**（当前仅用 Lanterna）。
   需整理 JLine 3 的用途或移除未使用的依赖。

3. **指令分发**: `AliceTuiLauncher.dispatchAgentCommand()` 和 `AliceCliLauncher.dispatchCommand()`
   均未完整覆盖 `AgentCommand` 密封接口的全部子类型（缺少 `ClearContextCmd`、`ViewContextCmd`、`CompactContextCmd`、`FeedbackCmd`、`SwitchModelCmd` 的分发）。

---

## 一、CLI 交互式 Chat 模式（JLine 3）

### □ 1.1 JLine 3 依赖注入 [priority:: high] [tool:: gradle] [file:: alice-facade-cmd/build.gradle]
- [x] 在 `alice-facade-cmd/build.gradle` 添加 JLine 3 依赖
    - [x] `org.jline:jline-reader:3.27.1`
    - [x] `org.jline:jline-terminal:3.27.1`
    - [x] `org.jline:jline-builtins:3.27.1`
- [x] 更新 `alice-facade-cmd/src/main/java/module-info.java`
    - [x] 添加 `requires org.jline.reader;`
    - [x] 添加 `requires org.jline.terminal;`
    - [x] 添加 `requires org.jline.utils;`

### □ 1.2 JLineChatSession — 交互式会话引擎 [priority:: critical] [tool:: Java] [file:: alice-facade-cmd/.../JLineChatSession.java]
- [x] 创建 `JLineChatSession` 类，封装 JLine 3 的 `LineReader`
    - [x] 初始化 `Terminal`（支持 Windows/Linux/macOS）
    - [x] 配置 `LineReader`（history file、completer、highlighter、parser）
    - [x] 设置 `Ctrl+C` 处理 → 信号处理（中断当前输入）
    - [x] 设置 `Ctrl+D`（EOF）→ 退出会话
- [x] 历史记录持久化
    - [x] `~/.alice/chat_history` 文件存储
    - [x] 限制最大历史行数（默认 1000）
- [x] Tab 补全 (Completer)
    - [x] 斜杠命令补全：`/run`, `/exec`, `/skill`, `/rules`, `/reload`, `/model`, `/new`, `/feedback`, `/exit`, `/clear`, `/context`, `/compact`, `/help`
    - [x] 模型 ID 补全（`/model` 后）：`gpt-4o`, `gpt-4o-mini`, `claude-3.5-sonnet`, `gemma4`, `o3-mini`
    - [x] 文件路径补全（`/prompt` 后）：基于当前目录的文件路径
    - [x] 会话历史搜索（Ctrl+R，JLine 默认支持）

### □ 1.3 JLineChatSession — 指令分发集成 [priority:: critical] [tool:: Java]
- [x] 实现主循环 `run()`:
    - [x] 打印欢迎信息（版本、可用命令提示）
    - [x] 循环读取用户输入 `reader.readLine(prompt)`
    - [x] 输入传给 `AgentCommand.parse()` 解析
    - [x] 调用 `AliceCliLauncher.dispatchCommand(AgentCommand)` 分发（避免重复解析）
    - [x] 输出渲染（字符 UI：思考提示、结果摘要）
- [x] 多行输入支持
    - [x] 检测未闭合的引号/花括号，自动进入多行模式
    - [x] Ctrl+C 取消当前输入

### □ 1.4 Chat 子命令启动 [priority:: high] [tool:: Java] [file:: alice-facade-cmd/.../config/CommandParser.java]
- [x] 将 `ChatCommand` 占位实现替换为真实启动:
    - [x] 创建 `JLineChatSession` 实例并调用 `run()`
    - [x] RunConfig 新增 `chat` 字段标识 chat 模式
    - [x] ExecutionCoordinator 检测 chat 模式并跳转到 chat 会话
    - [x] 退出后返回 EXIT_SUCCESS

### □ 1.5 **已通过 commit 54c104c 完成** [priority:: high] [tool:: Java]
- [x] `dispatchCommand()` 补齐缺失的 5 个指令分支:
    - [x] `ControlCmd.ClearContextCmd` — 清除上下文
    - [x] `ControlCmd.ViewContextCmd` — 查看上下文
    - [x] `ControlCmd.CompactContextCmd` — 压缩上下文
    - [x] `ControlCmd.FeedbackCmd` — 人类反馈（如挂起状态）
    - [x] `AlignmentCmd.SwitchModelCmd` — 模型切换

---

## 二、**已通过 commit 54c104c 完成** — TUI 分发链路补齐 (AliceTuiLauncher)

### □ 2.1 AliceTuiLauncher.dispatchAgentCommand() 补齐 [priority:: critical] [tool:: Java] [file:: alice-facade-tui/.../AliceTuiLauncher.java]
- [x] 在 switch 表达式中添加缺失分支（ClearContextCmd, ViewContextCmd, CompactContextCmd, FeedbackCmd, SwitchModelCmd）
- [x] 完整实现 handleClearContext(), handleViewContext(), handleCompactContext(), handleFeedback(), handleModelSwitch() 方法

### □ 2.2 TUI JLine 3 依赖清理 [priority:: low] [tool:: gradle] [status:: done]
- [x] 评估 TUI 中 JLine 3 的使用必要：确认 TUI 完全基于 Lanterna，零 JLine 使用
    - [x] 从 `build.gradle` 注释掉 JLine 3 依赖（保留注释说明可恢复）
    - [x] 从 `module-info.java` 注释掉 `requires` 声明（保留注释说明可恢复）

---

## 三、**已通过 commit 54c104c 完成** — Agent 核心接口补齐（供 Facade 调用）

### □ 3.1 Agent 核心上下文接口 [priority:: high] [tool:: Java] [file:: alice-core-agent/.../Agent.java]
- [x] 新增 `getActiveContext()` — Markdown 表格格式上下文状态
- [x] 新增 `clearMemory()` — 清空短期记忆
- [x] 新增 `compactContext()` — 写入长期记忆作为 Checkpoint 替代
- [x] 新增 `switchModel(String modelId)` — 动态切换 LLM
- [x] 新增 `injectFeedback(String feedback)` + `feedback()` — HITL 反馈

---

## 四、**已通过 commit 54c104c 完成** — CommandHandler / SlashCommand 补齐

### □ 4.1 SlashCommand 命令注册 [priority:: high] [tool:: Java] [file:: alice-facade-tui/.../command/SlashCommand.java]
- [x] 注册 `/context` → Type.INTERNAL
- [x] 注册 `/compact` → Type.INTERNAL
- [x] 注册 `/feedback` → Type.INTERNAL
- [x] 更新 `helpText()` 包含新增命令

### □ 4.2 CommandHandler 执行分支 [priority:: high] [tool:: Java] [file:: alice-facade-tui/.../command/CommandHandler.java]
- [x] `handleInternal()` 增加:
    - [x] `/context` → 转化为 ViewContextCmd 并派发
    - [x] `/compact` → 转化为 CompactContextCmd 并派发
    - [x] `/feedback` → 转化为 FeedbackCmd 并派发（带 args 校验）

---

## 五、测试 (Testing)

### □ 5.1 单元测试 [priority:: high]
- [ ] `JLineChatSession` 单元测试（Mock Terminal）
    - [ ] 斜杠命令解析测试
    - [ ] 非斜杠输入自然语言测试
    - [ ] Ctrl+C / Ctrl+D 处理测试
- [ ] `AliceTuiLauncher.dispatchAgentCommand()` 新分支测试
    - [ ] 6 种新指令类型分发测试
- [ ] `SlashCommand.parse()` / `CommandHandler.execute()` 新命令测试
    - [ ] `/context` 全流程
    - [ ] `/compact` 全流程
- [ ] `Agent` 核心新接口测试
    - [ ] getActiveContext()
    - [ ] clearMemory()
    - [ ] compactContext()

### □ 5.2 集成测试 [priority:: medium]
- [ ] CLI chat 模式 E2E：输入 → Agent 执行 → 输出
- [ ] CLI 命令历史持久化与恢复
- [ ] CLI Tab 补全交互
- [ ] TUI `/context` 执行查看上下文
- [ ] TUI `/clear` 清空内容

---

## 六、文档 (Docs)

### □ 6.1 文档同步 [priority:: medium]
- [ ] 更新 `docs/alice-facade-cmd/DESIGN.md` 添加 JLine 3 交互式 chat 设计
    - [ ] chat 模式时序图
    - [ ] 命令补全设计说明
- [ ] 更新 `README.md` 添加 chat 模式使用说明
- [ ] 更新 `CHANGELOG.md` 记录 CLI/TUI 加固变更
- [ ] 更新 `TODO-spec.md` 主看板状态

---

## 状态汇总

| 状态 | 计数 | 说明 |
|------|------|------|
| `- [x]` 已完成 | 31 | JLine Chat 模式 (1.1-1.4), 分发补齐 (1.5/2.1/3.1/4.1/4.2), TUI JLine 清理 (2.2) |
| `- [/]` 执行中 | 3 | 测试 (5.1/5.2), 文档 (6.1) |
| `- [ ]` 待执行 | 5 | 子任务细节 |
| `- [!]` 失败/阻塞 | 0 | — |
| **总计** | **39** | — |

> 最后更新：2026-06-12
>
> **当前状态**:
> - `alice-facade-cmd` 已有 picocli 单次任务模式 + JLine 3 交互式 chat
> - `alice-facade-tui` 已有 Lanterna TUI，指令分发已补齐 ✓, JLine 未使用依赖已清理 ✓
> - `alice-agent-command` 指令实体已全，分发链路已覆盖 ✓
