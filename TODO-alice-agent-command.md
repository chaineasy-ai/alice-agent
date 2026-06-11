# TODO-alice-agent-command: 指令模块分发补齐

> 遵循 [GFM Markdown 任务列表规范](./TODO-spec.md)
> 设计文档: [docs/alice-agent-command/DESIGN.md](./docs/alice-agent-command/DESIGN.md)
> 指令源码: [alice-agent-command/src/.../command/](./alice-agent-command/src/main/java/org/cland/alice/agent/command/)
>
> 格式约定：`- [ ]` 待办 | `- [x]` 已完成 | `- [/]` 执行中 | `- [-]` 已取消 | `- [!]` 失败/阻塞
> 行内元数据：`[key:: value]`
> 缩进 4 空格 = 子任务层级

---

# 目标：补齐 Alice Agent 指令模块的缺失分发链路

> 经过检查，`alice-agent-command` 模块已定义所有指令实体类（sealed interface 层级），
> 但 **TUI (`AliceTuiLauncher`)** 和 **CLI (`AliceCliLauncher`)** 的分发路由（`dispatchAgentCommand` / `dispatchCommand`）未覆盖全部指令类型，
> 导致 `/clear`, `/context`, `/compact`, `/feedback`, `/model` 等命令定义后无法实际生效。

## 一、待补齐分发路由 (Missing Dispatchers)

### □ 1.1 AliceTuiLauncher.dispatchAgentCommand() — TUI 分发补齐 [priority:: critical] [tool:: Java] [file:: alice-facade-tui/.../AliceTuiLauncher.java]
- [ ] 添加 `ControlCmd.ClearContextCmd` 分支 → 调用 `handleClearContext()`
    - [ ] 通过 eventBridge 输出 "上下文已清除"
    - [ ] 清空 chat 组件与 thought 组件的可视内容
    - [ ] 联动 `CommandHandler` 的 onClear 回调
- [ ] 添加 `ControlCmd.ViewContextCmd` 分支 → 调用 `handleViewContext()`
    - [ ] 从 Agent 的 Memory/Context 拉取当前状态
    - [ ] 通过 eventBridge 输出 Token 占用统计与消息快照
    - [ ] 格式化输出（Markdown 表格形式）
- [ ] 添加 `ControlCmd.CompactContextCmd` 分支 → 调用 `handleCompactContext()`
    - [ ] 触发 LLM 总结（需等待 Memory 模块提供总结接口）
    - [ ] 通过 eventBridge 输出 "上下文压缩完成" 及释放的 Token 量
- [ ] 添加 `ControlCmd.FeedbackCmd` 分支 → 调用 `handleFeedback()`
    - [ ] 将用户反馈注入当前 Agent 上下文的 `lastFeedback` 字段
    - [ ] 解除 Agent 的 HITL 挂起状态（待 Agent 模块暴露 HumanInTheLoop 接口）
- [ ] 添加 `AlignmentCmd.SwitchModelCmd` 分支 → 调用 `handleModelSwitch()`
    - [ ] 调用 `agent.switchModel(switchModelCmd.modelId())`（待 Agent 暴露接口）
    - [ ] 更新 header 组件显示的模型名
    - [ ] 通过 eventBridge 输出 "模型切换至: xxx"

### □ 1.2 AliceCliLauncher.dispatchCommand() — CLI 分发补齐 [priority:: high] [tool:: Java] [file:: alice-facade-cmd/.../AliceCliLauncher.java]
- [ ] 添加 `ControlCmd.ClearContextCmd` 分支 — 输出上下文清除信息
- [ ] 添加 `ControlCmd.ViewContextCmd` 分支 — 输出上下文查看信息
- [ ] 添加 `ControlCmd.CompactContextCmd` 分支 — 输出上下文压缩信息
- [ ] 将现有 stub 实现升级为实际操作（调用 Agent 核心执行）

### □ 1.3 SlashCommand 枚举补齐 [priority:: high] [tool:: Java] [file:: alice-facade-tui/.../command/SlashCommand.java]
- [ ] 注册 `/context` 命令 → Type.INTERNAL 或新类型 CONTEXT
- [ ] 注册 `/compact` 命令 → Type.INTERNAL
- [ ] 注册 `/feedback` 命令 → Type.INTERNAL
- [ ] 更新 `helpText()` 方法，添加新增命令说明

### □ 1.4 CommandHandler 命令执行补齐 [priority:: high] [tool:: Java] [file:: alice-facade-tui/.../command/CommandHandler.java]
- [ ] `handleInternal()` 增加 `/context` 分支 → 转化为 `ViewContextCmd` 并派发
- [ ] `handleInternal()` 增加 `/compact` 分支 → 转化为 `CompactContextCmd` 并派发
- [ ] `handleInternal()` 增加 `/feedback` 分支 → 转化为 `FeedbackCmd` 并派发

## 二、Agent 核心接口补齐 (Backend API)

### □ 2.1 Agent 上下文管理与查询接口 [priority:: high] [tool:: Java] [file:: alice-core-agent/.../Agent.java]
- [ ] 新增 `getActiveContext()` 方法
    - [ ] 返回当前全量 Context 状态（Token 占用、消息滑动窗口、变量快照）
    - [ ] 支持 `/context` 命令查询
- [ ] 新增 `clearMemory()` 方法
    - [ ] 清空短期记忆（保留 System Prompt / Rules）
    - [ ] 重置 Token 计数器
    - [ ] 支持 `/clear` 命令执行
- [ ] 新增 `compactContext()` 方法
    - [ ] 将历史对话写入 WAL（如果启用了 WAL）
    - [ ] 提炼历史为 Summary 事实快照
    - [ ] 释放 Context Window
    - [ ] 支持 `/compact` 命令执行
- [ ] 新增 `switchModel(modelId)` 方法
    - [ ] 动态切换 LLM 引擎
    - [ ] 支持 `/model` 命令执行
- [ ] 新增 `injectFeedback(feedback)` / `feedback()` 方法
    - [ ] 注入人类反馈到 Context
    - [ ] 支持 `/feedback` 命令执行

### □ 2.2 Agent 核心 HumanInTheLoop 支持 [priority:: medium] [tool:: Java] [file:: alice-core-agent/.../executor/AgentExecutor.java]
- [ ] 新增 `suspendForHuman()` 方法
    - [ ] Agent 执行中需要反馈时挂起等待
    - [ ] 通过 EventBridge 或 Future 暴露挂起状态
- [ ] 新增 `resumeWithFeedback(String feedback)` 方法
    - [ ] 注入人类反馈后继续执行
    - [ ] 在 Context 中标记反馈状态

## 三、测试 (Testing)

### □ 3.1 单元测试 [priority:: high]
- [ ] `AliceTuiLauncher.dispatchAgentCommand()` 覆盖 6 种新指令测试
    - [ ] ClearContextCmd 分发测试
    - [ ] ViewContextCmd 分发测试
    - [ ] CompactContextCmd 分发测试
    - [ ] FeedbackCmd 分发测试
    - [ ] SwitchModelCmd 分发测试
- [ ] `CommandHandler` 新命令分支测试
    - [ ] `/context` 命令解析与派发测试
    - [ ] `/compact` 命令解析与派发测试
- [ ] `SlashCommand.parse()` 新增命令解析测试
    - [ ] `/context` 解析为 ViewContextCmd
    - [ ] `/compact` 解析为 CompactContextCmd

### □ 3.2 集成测试 [priority:: medium]
- [ ] TUI 交互流程：输入 `/context` → 查看上下文状态
- [ ] TUI 交互流程：输入 `/clear` → 清除上下文
- [ ] CLI 交互流程：执行 `--dispatch /context` → 查看上下文

## 四、文档 (Docs)

### □ 4.1 文档同步 [priority:: medium]
- [ ] 更新 `docs/alice-agent-command/DESIGN.md` 补齐 `/context`、`/compact`、`/feedback` 的时序图
- [ ] 更新 `CHANGELOG.md` 记录指令模块补齐变更
- [ ] 更新 `TODO-spec.md` 主看板中 alice-agent-command 域的状态

---

## 状态汇总

| 状态 | 计数 | 说明 |
|------|------|------|
| `- [x]` 已完成 | 0 | — |
| `- [/]` 执行中 | 0 | — |
| `- [ ]` 待执行 | 24 | 待填充 |
| `- [!]` 失败/阻塞 | 0 | — |
| **总计** | **24** | — |

> 最后更新：2026-06-11
>
> **背景**: `git log` 显示最近一次 commit 是 `b650078 feat(alice-agent-command): 新增 /clear, /context, /compact 三条上下文管理指令`
> 但提交内容仅为指令实体类（sealed record），分发链路尚未补齐。
> 当前 `AliceTuiLauncher` 的 switch 表达式未覆盖 5 种子类型，导致编译警告与实际功能缺失。
