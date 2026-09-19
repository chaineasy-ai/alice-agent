---
title: "alice CLI 命令参考"
summary: "Alice Agent CLI 全部子命令、标志参数、选项、退出码完整参考"
read_when:
  - "使用 alice CLI"
  - "实现或修改 CLI 子命令"
  - "编写 CLI 测试"
---

# alice CLI 命令参考

## 概览

```
alice [--help] [--version] <subcommand> [options] [parameters]
```

### 子命令一览

| 子命令 | 类型 | 说明 |
|--------|------|------|
| `run` | 一次性 | 执行单次任务并退出 |
| `chat` | 交互式 | 启动交互式对话会话 |
| `tools` | 一次性 | 列出已加载工具 |
| `config` | 一次性 | 管理配置（get/set/show） |
| `routine` | 一次性 | 注册或管理定时任务 |
| `sub-agent` | 一次性 | 管理子 Agent |

### 退出码

| 退出码 | 常量 | 说明 |
|--------|------|------|
| `0` | `EXIT_SUCCESS` | 正常执行 / help / version |
| `1` | `EXIT_RUNTIME_ERROR` | 运行时错误（无 API key、Agent 异常） |
| `2` | `EXIT_PARAM_ERROR` | 参数解析错误 / 无子命令 |
| `130` | `EXIT_INTERRUPTED` | 用户中断（Ctrl+C） |

---

## 1. `alice run` — 执行单次任务

```
alice run [options] <task>
```

### 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `<task>` | String | ✅ | Agent 任务目标描述 |

### 选项

| 选项 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `-m, --model <string>` | String | `gpt-4o-mini` | 覆盖默认模型 |
| `-v, --verbose` | flag | `false` | 打印详细思考/执行过程 |
| `--json` | flag | `false` | 以 JSON 格式输出结果 |
| `--timeout <seconds>` | long | `180` | 任务最大执行时长（秒） |
| `-h, --help` | flag | — | 显示帮助信息 |
| `-V, --version` | flag | — | 显示版本信息 |

### 示例

```bash
alice run "清理当前目录的日志文件"
alice run -m gpt-4o "为 Utils.java 生成单元测试" --verbose
alice run --json "分析 build.log 中的错误原因"
alice run -m claude-3.5-sonnet --timeout 300 "代码审查"
```

### 解析逻辑

`CommandParser.RunCommand` → `toRunConfig()` → `RunConfig{task, model, verbose, jsonOutput, timeoutSeconds}`

---

## 2. `alice chat` — 交互式对话

```
alice chat [options]
```

### 选项

| 选项 | 类型 | 说明 |
|------|------|------|
| `-h, --help` | flag | 显示帮助信息 |
| `-V, --version` | flag | 显示版本信息 |

### 行为

启动 JLine 3 交互式终端会话：
- 显示欢迎横幅和提示符 `alice>`
- 支持 Tab 命令/路径补全
- 支持 `Ctrl+R` 历史搜索
- 支持 `Ctrl+D` 退出

### 斜杠命令（chat 会话内部）

在 `alice>` 提示符下可输入以下斜杠命令：

| 命令 | 功能 | 示例 |
|------|------|------|
| `/run <goal>` | 执行目标 | `/run read file.txt` |
| `/exec <cmd>` | 执行原始命令 | `/exec ls -la` |
| `/skill <ref>` | 注册技能 | `/skill filesystem` |
| `/rules <ref>` | 更新规则 | `/rules security-policy` |
| `/reload` | 重载内核 | `/reload` |
| `/model <id>` | 切换模型 | `/model gpt-4o` |
| `/new` | 重置会话 | `/new` |
| `/clear` | 清除上下文 | `/clear` |
| `/context` | 查看上下文 | `/context` |
| `/compact` | 压缩上下文 | `/compact` |
| `/feedback <msg>` | 提交反馈 | `/feedback Great!` |
| `/exit` | 退出会话 | `/exit` |
| `/routine <cron>` | 注册定时任务 | `/routine 0 */5 * * * ?` |
| `/sub-agent spawn --goal <g>` | 生成子 Agent | `/sub-agent spawn --goal "analyze"` |
| `/sub-agent connect --name <n> --acp-endpoint <url>` | 连接 ACP Agent | `/sub-agent connect --name w1 --acp-endpoint http://...` |
| `/sub-agent list` | 列出子 Agent | `/sub-agent list` |
| `/sub-agent cancel <id>` | 取消子 Agent | `/sub-agent cancel abc-123` |
| `/sub-agent results <id>` | 获取结果 | `/sub-agent results def-456` |
| `/sub-agent send <id> --message <m>` | 发送消息 | `/sub-agent send agent1 --message hello` |
| `/sub-agent prompt <id> --text <t>` | 提示 ACP Agent | `/sub-agent prompt ext-agent --text 'do something'` |

> **注意**: 斜杠命令仅用于 `alice chat` 交互式模式，不等同于 CLI 一次性子命令或 TUI 组件命令。

---

## 3. `alice tools` — 列出工具

```
alice tools [options]
```

### 选项

| 选项 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `-d, --detail` | flag | `false` | 显示工具详细参数 Schema |
| `-h, --help` | flag | — | 显示帮助信息 |
| `-V, --version` | flag | — | 显示版本信息 |

### 示例

```bash
alice tools          # 列出工具名称和描述
alice tools --detail # 列出工具名称、描述、参数
```

### 解析逻辑

`CommandParser.ToolsCommand` → `toRunConfig()` → `RunConfig{task:"tools", listTools:true, toolDetail:true/false}`

`handleListTools()` 方法通过 `ToolRegistryHolder` 访问 `ToolRegistry`，列举已注册工具。

---

## 4. `alice config` — 配置管理

```
alice config [<action> [<key> [<value>]]]
```

### 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `<action>` | String | 否 | `get` / `set` / 空（show 概览） |
| `<key>` | String | 否 | 配置键名（如 `openai.api_key`、`default.model`） |
| `<value>` | String | 否 | 配置值（仅 `set` 动作需要） |

### 选项

| 选项 | 类型 | 说明 |
|------|------|------|
| `-h, --help` | flag | 显示帮助信息 |
| `-V, --version` | flag | 显示版本信息 |

### 示例

```bash
alice config                          # 显示全部配置概览
alice config get openai.api_key       # 读取配置项
alice config set openai.api_key sk-xxx # 设置并持久化
alice config set default.model gpt-4o # 设置默认模型
```

### 配置键参考

| 键名（CLI 点分隔） | 环境变量 | 内建默认值 | 说明 |
|--------------------|----------|------------|------|
| `openai.api_key` | `OPENAI_API_KEY` | — | OpenAI API 密钥 |
| `openai.model` | `OPENAI_MODEL` | `gpt-4o-mini` | OpenAI 默认模型 |
| `anthropic.api_key` | `ANTHROPIC_API_KEY` | — | Anthropic API 密钥 |
| `default.model` | `DEFAULT_MODEL` | `gpt-4o-mini` | 默认模型 |
| `default.timeout` | `DEFAULT_TIMEOUT` | `180` | 默认任务超时（秒） |
| `default.verbose` | `DEFAULT_VERBOSE` | `false` | 默认详细模式 |

### 配置读取优先级

1. **环境变量** — 优先最高，来源标注 `(from env <VAR>)`
2. **`~/.alice/config.json`** — 持久化配置，来源标注 `(from ~/.alice/config.json)`
3. **内建默认值** — 最低优先级，来源标注 `(built-in default)`

### 内部实现

- `AliceConfigStore` — 线程安全 JSON 读写
- 原子写入：先写 `.tmp` 文件，再用 `ATOMIC_MOVE` 替换
- 键名转换：CLI 点分隔 → JSON 下划线（`openai.api_key` ↔ `openai_api_key`）

---

## 5. `alice routine` — 定时任务

```
alice routine [<cron>] [options]
```

### 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `<cron>` | String | 否 | Cron 表达式（如 `0 */2 * * * ?`） |

### 选项

| 选项 | 类型 | 说明 |
|------|------|------|
| `-l, --list` | flag | 列出已注册的定时任务 |
| `-r, --remove <id>` | String | 按 ID 移除定时任务 |
| `-h, --help` | flag | 显示帮助信息 |
| `-V, --version` | flag | 显示版本信息 |

### 示例

```bash
alice routine "0 */2 * * * ?"   # 注册定时任务
alice routine --list             # 列出所有定时任务
alice routine -l                 # 同上（短选项）
alice routine "0 */5 * * * ?" --list  # 注册并立即查看列表
alice routine                    # 查看当前 routine 状态
```

### 解析逻辑

`CommandParser.RoutineCommand` → `toRunConfig()` → `RunConfig{task:"routine", routineCron, listRoutines}`

---

## 6. `alice sub-agent` — 子 Agent 管理

```
alice sub-agent [options]
```

### 选项

| 选项 | 类型 | 说明 |
|------|------|------|
| `--spawn <goal>` | String | 生成一个带目标的子 Agent |
| `--connect <name>` | String | 连接外部 ACP Agent（须配合 `--acp-endpoint`） |
| `--acp-endpoint <url>` | String | ACP 端点 URL |
| `--list` | flag | 列出所有子 Agent |
| `--cancel <id>` | String | 按 ID 取消子 Agent |
| `--results <id>` | String | 按 ID 获取子 Agent 结果 |
| `--send <id>` | String | 向子 Agent 发送消息（须配合 `--message`） |
| `--message <text>` | String | 发送的消息内容 |
| `--prompt <text>` | String | 提示外部 ACP Agent（须配合 `--agent-id`） |
| `--agent-id <id>` | String | 目标 ACP Agent ID |
| `-h, --help` | flag | 显示帮助信息 |
| `-V, --version` | flag | 显示版本信息 |

### 示例

```bash
alice sub-agent --spawn "analyze logs"           # 生成子 Agent
alice sub-agent --list                            # 列出子 Agent
alice sub-agent --connect "worker1" --acp-endpoint "http://localhost:8080"  # 连接外部
alice sub-agent --cancel "abc-123"               # 取消
alice sub-agent --results "def-456"              # 获取结果
alice sub-agent --send "agent1" --message "hello" # 发送消息
alice sub-agent --prompt "do task" --agent-id "ext-agent"  # 提示外部 Agent
alice sub-agent                                   # 无选项，正常执行
```

### 解析逻辑

`CommandParser.SubAgentCommand` → `toRunConfig()` → `RunConfig{task:"sub-agent", subAgentSpawnGoal, subAgentConnectName, ...}`

支持的 RunConfig builder 字段：

| 字段 | 关联选项 |
|------|----------|
| `subAgentSpawnGoal` | `--spawn` |
| `subAgentConnectName` | `--connect` |
| `subAgentConnectEndpoint` | `--acp-endpoint` |
| `subAgentList` | `--list` |
| `subAgentCancelId` | `--cancel` |
| `subAgentResultsId` | `--results` |
| `subAgentSendId` | `--send` |
| `subAgentSendMessage` | `--message` |
| `subAgentPromptAgentId` | `--agent-id` |
| `subAgentPromptText` | `--prompt` |

---

## 数据流

### CLI 一次性子命令

```
用户输入 → picocli 解析 → RunConfig → AliceCliLauncher.handleXxx() → 执行 → 输出
```

### CLI 交互式会话

```
用户输入 → alice chat → JLineChatSession
  ├── 普通文本 → Agent 处理
  └── /xxx 斜杠命令 → AgentCommand.parse() → dispatchCommand() → 执行
```

## 侧写模式

- `alice`（无参数）→ `FacadeSelector` 启动 TUI 模式（`alice-facade-tui`）
- `alice <subcommand>` → 直接执行 CLI 子命令
- 未设置 API key 时所有子命令仍可使用，但 LLM 调用不可用
