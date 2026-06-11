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
- [ ] 在 `alice-facade-cmd/build.gradle` 添加 JLine 3 依赖
    - [ ] `org.jline:jline-reader:3.27.1`
    - [ ] `org.jline:jline-terminal:3.27.1`
    - [ ] `org.jline:jline-builtins:3.27.1`
- [ ] 更新 `alice-facade-cmd/src/main/java/module-info.java`
    - [ ] 添加 `requires org.jline.reader;`
    - [ ] 添加 `requires org.jline.terminal;`

### □ 1.2 JLineChatSession — 交互式会话引擎 [priority:: critical] [tool:: Java] [file:: alice-facade-cmd/.../JLineChatSession.java]
- [ ] 创建 `JLineChatSession` 类，封装 JLine 3 的 `LineReader`
    - [ ] 初始化 `Terminal`（支持 Windows/Linux/macOS）
    - [ ] 配置 `LineReader`（history file、completer、highlighter、parser）
    - [ ] 设置 `Ctrl+C` 处理 → ControlCmd.InterruptCmd
    - [ ] 设置 `Ctrl+D`（EOF）→ 退出会话
- [ ] 历史记录持久化
    - [ ] `~/.alice/chat_history` 文件存储
    - [ ] 限制最大历史行数（默认 1000）
- [ ] Tab 补全 (Completer)
    - [ ] 斜杠命令补全：`/`, `/run`, `/exec`, `/skill`, `/rules`, `/reload`, `/model`, `/new`, `/feedback`, `/exit`, `/clear`, `/context`, `/compact`, `/help`
    - [ ] 模型 ID 补全（`/model` 后）：`gpt-4o`, `gpt-4o-mini`, `claude-3.5-sonnet`, `gemma4`
    - [ ] 文件路径补全（`/prompt` 后）：基于当前目录的文件路径
    - [ ] 会话历史搜索（Ctrl+R）

### □ 1.3 JLineChatSession — 指令分发集成 [priority:: critical] [tool:: Java]
- [ ] 实现主循环 `run()`:
    - [ ] 打印欢迎信息（版本、可用命令提示）
    - [ ] 循环读取用户输入 `reader.readLine(prompt)`
    - [ ] 输入传给 `AgentCommand.parse()` 解析
    - [ ] 调用 `AliceCliLauncher.dispatchCommand()` 分发
    - [ ] 输出渲染（字符 UI：思考链缩进、工具调用着色、结果摘要）
- [ ] 多行输入支持
    - [ ] 检测未闭合的引号/花括号，自动进入多行模式
    - [ ] ESC 取消当前输入

### □ 1.4 Chat 子命令启动 [priority:: high] [tool:: Java] [file:: alice-facade-cmd/.../config/CommandParser.java]
- [ ] 将 `ChatCommand` 占位实现替换为真实启动:
    - [ ] 创建 `JLineChatSession` 实例
    - [ ] 初始化 Agent 核心
    - [ ] 调用 `chatSession.run()`
    - [ ] 退出后返回 EXIT_SUCCESS

### □ 1.5 CommandParser 补齐 [priority:: high] [tool:: Java]
- [ ] `dispatchCommand()` 补齐缺失的 5 个指令分支:
    - [ ] `ControlCmd.ClearContextCmd` — 清除上下文
    - [ ] `ControlCmd.ViewContextCmd` — 查看上下文
    - [ ] `ControlCmd.CompactContextCmd` — 压缩上下文
    - [ ] `ControlCmd.FeedbackCmd` — 人类反馈（如挂起状态）
    - [ ] `AlignmentCmd.SwitchModelCmd` — 模型切换

---

## 二、TUI 分发链路补齐 (AliceTuiLauncher)

### □ 2.1 AliceTuiLauncher.dispatchAgentCommand() 补齐 [priority:: critical] [tool:: Java] [file:: alice-facade-tui/.../AliceTuiLauncher.java]
- [ ] 在 switch 表达式中添加缺失分支：
    - [ ] `ControlCmd.ClearContextCmd` → handleClearContext()
        - [ ] 调用 `agent.clearMemory()`（待实现）
        - [ ] 清空 chat/thought UI 内容
        - [ ] eventBridge 输出 "上下文已清除"
    - [ ] `ControlCmd.ViewContextCmd` → handleViewContext()
        - [ ] 调用 `agent.getActiveContext()`
        - [ ] 格式化输出 Token 占用、消息计数、变量快照
        - [ ] eventBridge 输出查看结果
    - [ ] `ControlCmd.CompactContextCmd` → handleCompactContext()
        - [ ] 调用 `agent.compactContext()`
        - [ ] eventBridge 输出压缩结果
    - [ ] `ControlCmd.FeedbackCmd` → handleFeedback()
        - [ ] 注入反馈到当前 Agent 上下文的 `lastFeedback`
        - [ ] 解除 HITL 挂起（如适用）
    - [ ] `AlignmentCmd.SwitchModelCmd` → handleModelSwitch()
        - [ ] 更新 header 组件模型名
        - [ ] 调用 `agent.switchModel()`
        - [ ] eventBridge 输出 "模型切换至: xxx"

### □ 2.2 TUI JLine 3 依赖清理 [priority:: low] [tool:: gradle]
- [ ] 评估 TUI 中 JLine 3 的使用必要：
    - [ ] 如果 TUI 完全基于 Lanterna，移除 `jline-reader` 和 `jline-builtins` 依赖
    - [ ] 如果保留（如 InputComponent 高级输入），更新 `module-info.java` 注释说明用途
- [ ] 从 `alice-facade-tui/build.gradle` 移除未使用的 JLine 3 依赖

---

## 三、Agent 核心接口补齐（供 Facade 调用）

### □ 3.1 Agent 核心上下文接口 [priority:: high] [tool:: Java] [file:: alice-core-agent/.../Agent.java]
- [ ] 新增 `getActiveContext()` 方法
    - [ ] 返回 AgentContext 当前状态快照（Token 占用、消息计数、变量）
    - [ ] 支持 `/context` 查询
- [ ] 新增 `clearMemory()` 方法
    - [ ] 清空短期记忆（保留 System Prompt / Rules）
    - [ ] 重置 Token 计数器
    - [ ] 如果启用了 WAL，写入 clear 事件
    - [ ] 支持 `/clear` 执行
- [ ] 新增 `compactContext()` 方法
    - [ ] 触发 LLM 总结历史对话
    - [ ] 替换长历史为 Summary 快照
    - [ ] 支持 `/compact` 执行
- [ ] 新增 `switchModel(String modelId)` 方法
    - [ ] 动态切换 LLM Provider
    - [ ] 支持 `/model` 执行
- [ ] 新增 `injectFeedback(String feedback)` + `feedback()` 方法
    - [ ] 注入 HITL 反馈到上下文
    - [ ] 支持 `/feedback` 执行

---

## 四、CommandHandler / SlashCommand 补齐

### □ 4.1 SlashCommand 命令注册 [priority:: high] [tool:: Java] [file:: alice-facade-tui/.../command/SlashCommand.java]
- [ ] 注册 `/context` → Type.INTERNAL
- [ ] 注册 `/compact` → Type.INTERNAL
- [ ] 注册 `/feedback` → Type.INTERNAL
- [ ] 更新 `helpText()` 包含新增命令

### □ 4.2 CommandHandler 执行分支 [priority:: high] [tool:: Java] [file:: alice-facade-tui/.../command/CommandHandler.java]
- [ ] `handleInternal()` 增加:
    - [ ] `/context` → 转化为 ViewContextCmd 并派发
    - [ ] `/compact` → 转化为 CompactContextCmd 并派发
    - [ ] `/feedback` → 转化为 FeedbackCmd 并派发

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
| `- [x]` 已完成 | 0 | — |
| `- [/]` 执行中 | 0 | — |
| `- [ ]` 待执行 | 37 | 待填充 |
| `- [!]` 失败/阻塞 | 0 | — |
| **总计** | **37** | — |

> 最后更新：2026-06-11
>
> **当前状态**:
> - `alice-facade-cmd` 已有 picocli 单次任务模式，缺交互式 chat
> - `alice-facade-tui` 已有 Lanterna TUI 但指令分发不全
> - `alice-facade-tui` 已依赖 JLine 3 但未使用（需评估清理）
> - `alice-agent-command` 指令实体已全，分发链路未覆盖
