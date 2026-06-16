---
title: "alice TUI 斜杠命令参考"
summary: "Alice Agent TUI 模式的全部斜杠命令、布局说明与交互操作完整参考"
read_when:
  - "using Alice Agent in TUI mode"
  - "implementing or modifying TUI slash commands"
  - "understanding TUI layout and interaction"
scope:
  - alice-facade-tui
status: "active"
updated: "2026-06-17"
---
# alice TUI 斜杠命令参考

## 启动方式

TUI 模式通过以下方式启动：

```bash
alice                    # 无参数 → FacadeSelector 自动启动 TUI
# 或
cd alice-bootstrap && ./gradlew run
```

> TUI 模式使用 JLine 3 终端 UI 库，提供三层单线分割布局。不同于 CLI 的 `alice chat`（JLine 交互式控制台）和 `alice run`（一次性任务执行）。

## 界面布局

TUI 采用 JLine 3 **三层单线分割布局**（TAO Standard Mode），通过两条 `-` 分割线将终端垂直划分为三大固定区域。

> **跨平台兼容**: Windows terminal (Code Page 936/GBK) 下 Unicode box-drawing 字符渲染为乱码，因此使用 ASCII `-` 替代 `─`。以下布局示例使用 `-` 展示实际渲染效果。

```
 alice v0.60.0
----------------------------------------------------------------------------------------------------------------
 [T Thought]: 监测到 uncommitted 悬空状态，自动触发双式记账平衡等式校验。
 [A Action ]: 调用本地 Bash 执行器: $ gradle :cland-chainpay:test --tests "AccountLedgerTest"
 [O Observe]: BUILD SUCCESSFUL in 3s (1 test passed)
 [T Thought]: 审计链路闭环。准备向用户输出白盒分析报告。

----------------------------------------------------------------------------------------------------------------
 > /_                                                                            ← 居中输入区域
----------------------------------------------------------------------------------------------------------------
 💰 Cost: $0.041 | 📊 Speed: 125 t/s | 🧠 Model: deepseek-v4-flash • medium | 🔌 Active Tool: cland-pay-mcp
```

### 区域划分

| 区域 | 行数 | 说明 |
|------|------|------|
| **Header** | 1 行 | 标题栏，显示版本号 |
| **上分割线** | 1 行 | `-` 单线 |
| **上方滚动区** | 可变 | TAO 流（Thought/Action/Observation），内容自动向上滚动 |
| **下分割线 (输入上方)** | 1 行 | `-` 单线 |
| **输入区** | 1 行 | `> /_` 居中输入提示符，位置永不偏移 |
| **下分割线 (输入下方)** | 1 行 | `-` 单线 |
| **底部状态栏** | 1 行 | 计费、速率、模型、工具等核心指标 |

> 最后三行永久锁定：上分割线、输入框、下分割线、底部状态栏。日志滚动不影响这三行。

### 组件

| 组件 | 类 | 说明 |
|------|----|------|
| **HeaderComponent** | `header` | 版本号横幅 |
| **ThoughtComponent** | `thought` | TAO 流渲染，支持 `[T]` `[A]` `[O]` 前缀着色 |
| **InputComponent** | `input` | 输入框，支持斜杠命令 |
| **FooterComponent** | `footer` | 状态栏，显示模型 + 计数 + 工具 |

### /model 自动补全

输入 `/model ` 后按 `Tab`，JLine 3 原生 `AUTO_MENU` 在**输入框内**显示模型补全列表：

```
----------------------------------------------------------------------------------------------------------------
 > /model 
    deepseek-v4-flash • medium  (当前使用)
    deepseek-v4-reasoning • deep
    gpt-4o-mini
----------------------------------------------------------------------------------------------------------------
 💰 Cost: $0.041 | 📊 Speed: 125 t/s | 🧠 Model: deepseek-v4-flash • medium | 🔌 Active Tool: cland-pay-mcp
```

- 补全列表在输入框内出现，通过 `Tab` / 上下方向键轮转选中
- 输入框、分割线、底部状态栏**位置完全固定**，无抖动无位移
- 选中后回车确认，补全省略，输入框恢复正常

## 交互操作

### 键盘快捷键

| 快捷键 | 功能 |
|--------|------|
| `Enter` | 提交输入（消息或命令） |
| `Tab` | 自动补全命令 |
| `Ctrl+C` | 取消当前 Agent 执行 |
| `Ctrl+D` | 退出 TUI |
| `PageUp / PageDown` | 滚动面板内容 |
| `F1` | 打开帮助对话框 |
| `Escape` | 关闭当前对话框 / 取消 |

## 斜杠命令

在 `InputLine` 中以 `/` 开头的文本被识别为命令。所有斜杠命令由 TUI 本地处理，不发送给 Agent。

### 会话管理

| 命令 | 功能 | 示例 |
|------|------|------|
| `/new` | 重置会话，清除上下文 | `/new` |
| `/clear` | 清空 UI 显示内容（不清除上下文） | `/clear` |
| `/exit` | 保存会话后退出 TUI | `/exit` |

### 模型与工具

| 命令 | 功能 | 示例 |
|------|------|------|
| `/model <id>` | 切换当前使用的 LLM 模型 | `/model claude-3.5-sonnet` |
| `/tools` | 列出 Agent 已加载的所有工具 | `/tools` |
| `/tools --detail` | 列出工具详情（含参数 Schema） | `/tools --detail` |

### 提示词与执行

| 命令 | 功能 | 示例 |
|------|------|------|
| `/prompt <file>` | 加载外部文件作为系统提示词 | `/prompt ./system_v2.txt` |
| `/exec <command>` | 执行 Shell 命令，输出作为上下文喂给 Agent | `/exec git log -n 5` |

### 历史与信息

| 命令 | 功能 | 示例 |
|------|------|------|
| `/history` | 展示最近执行记录快照 | `/history` |
| `/help` | 列出所有斜杠命令 | `/help` |

### 子 Agent 管理

| 命令 | 功能 | 示例 |
|------|------|------|
| `/sub-agent spawn --goal <g>` | 生成子 Agent | `/sub-agent spawn --goal "analyze logs"` |
| `/sub-agent list` | 列出所有子 Agent | `/sub-agent list` |
| `/sub-agent connect --name <n> --acp-endpoint <url>` | 连接外部 ACP Agent | `/sub-agent connect --name w1 --acp-endpoint http://...` |
| `/sub-agent cancel <id>` | 取消子 Agent | `/sub-agent cancel abc-123` |
| `/sub-agent results <id>` | 获取子 Agent 结果 | `/sub-agent results def-456` |
| `/sub-agent send <id> --message <m>` | 向子 Agent 发送消息 | `/sub-agent send agent1 --message hello` |
| `/sub-agent prompt <id> --text <t>` | 提示外部 ACP Agent | `/sub-agent prompt ext-agent --text 'do something'` |

## 命令分类

TUI 斜杠命令按处理方式分为四类（对应 `SlashCommand.Type` 枚举）：

| 类型 | 说明 | 命令 |
|------|------|------|
| **INTERNAL** | 仅操作 UI/会话状态 | `/new`, `/clear`, `/context`, `/compact`, `/feedback`, `/exit`, `/help` |
| **IO** | 读取文件或历史记录 | `/prompt`, `/history` |
| **SYSTEM** | 执行 Shell 命令并将输出喂给 Agent | `/exec` |
| **CONFIG** | 修改运行时模型/工具/定时任务/子 Agent | `/model`, `/tools`, `/routine`, `/sub-agent` |

> 所有斜杠命令由 TUI 的 `CommandHandler` 本地拦截处理，不直接提交给 Agent。其中 `INTERNAL` 类型直接执行（如 `/help` 打印帮助），其他类型先转换为 `AgentCommand` 再通过回调派发给 Agent 核心执行。

## 与 CLI 命令的区别

| 维度 | TUI 斜杠命令 | CLI 子命令 |
|------|-------------|-----------|
| 启动方式 | `alice`（无参数） | `alice <subcommand>` |
| 交互模式 | 持久化终端 UI 面板 | 一次性执行后退出 |
| 命令格式 | `/xxx` 前缀 | `--xxx` 标志参数 |
| 渲染引擎 | Lanterna（像素级 UI） | JLine / picocli（文本流） |
| 处理方式 | TUI 本地拦截处理 | AliceCliLauncher 分发执行 |

## 状态机

```
       ┌─────────┐          ┌──────────┐          ┌────────────┐
──────▶│  IDLE   │────┐────▶│ INPUTING │────┐────▶│  RUNNING   │
       │ (空闲)  │    │     │ (输入中) │    │     │ (思考执行) │
       └────▲────┘    │     └──────────┘    │     └─────┬──────┘
            │         │                     │           │
            │         ▼                     ▼           │
            │    ┌─────────┐          ┌──────────┐      │
            └────┤  ERROR  │◄─────────┤ INTERVENE│◄─────┘
                 │ (报错)  │          │ (人工干预)│
                 └─────────┘          └──────────┘
```

## 线程模型

- **UI 线程** — 负责界面渲染循环（Lanterna `screen.readInput()`）
- **Agent 线程** — 独立执行，不阻塞 UI 交互
- **事件桥接** — `EventBridge` 监听 Agent 事件，异步推送到 UI 更新

## 参考

- [DESIGN.md](./DESIGN.md) — TUI 模块设计文档
- [Layout.md](./Layout.md) — 布局详细说明
- [QUICK_START.md](./QUICK_START.md) — 快速入门
