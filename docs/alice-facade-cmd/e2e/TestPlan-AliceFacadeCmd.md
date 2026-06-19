# Test Plan — alice-facade-cmd 模块

**summary**: 为 alice-facade-cmd 模块编写基于 Spock 的测试用例，覆盖每个 CLI 子命令的解析、分发、渲染逻辑
**read_when**: 编写或维护 alice-facade-cmd 测试用例时参考

基于 docs/alice-agent-command/DESIGN.md 的 6 大类指令（Execution / Capability / Alignment / Control / RoutineTime / SubAgent），共 21 个子类型，alice-facade-cmd 中每个 CLI 子命令都需要对应的测试用例。

> **E2E 场景设计文档**（按场景拆分，每个独立文档）：
> - [场景一：CLI 子命令](./scene-cli-subcommands.md) — 25 个 E2E 测试，覆盖 6 个 CLI 子命令的 picocli 参数解析
> - [场景二：TUI 斜杠命令](../alice-facade-tui/e2e/scene-tui-slash-commands.md) — 30 个 E2E 测试，覆盖 20 种斜杠命令 + 自然语言的 parse() 映射
> - [场景三：dispatch 全路径](./scene-dispatch-full-coverage.md) — 21 个 E2E 测试，覆盖全部 21 种 AgentCommand 密封子类型的 dispatch 分发

---

## 测试文件清单

| 文件 | 覆盖范围 | 测试数 |
|------|---------|--------|
| `CommandParserSpec.groovy` | `run`, `chat`, `tools`, `config`, `routine`, `sub-agent` 解析 | ~45 |
| `RunConfigSpec.groovy` | Builder 常规字段 + routine/tools/config/sub-agent fields + toString | ~40 |
| `AliceCliLauncherSpec.groovy` | CLI 入口退出码 + dispatchCommand() 全覆盖 | ~25 |
| `AliceConfigStoreSpec.groovy` | JSON 配置持久化（get/set/delete/可靠性） | 12 |
| `TextOutputRendererSpec.groovy` | Continue/Finish/Failure/Error 渲染 | ~7 |
| `JsonOutputRendererSpec.groovy` | JSON 格式渲染 | ~6 |

## 子命令覆盖明细

### CLI 一次性子命令 (picocli 解析 → `RunConfig` → 执行)

#### 1. `run` 子命令 (CommandParser)
- [x] basic parse with task
- [x] all options (--model, --verbose, --json, --timeout)
- [x] short options (-m, -v)
- [x] --help / --version returns null
- [x] no subcommand → ParseException
- [x] unknown option → ParseException
- [x] verbose + json flags
- [x] default model when --model not given

#### 2. `tools` 子命令 (CommandParser + Builder)
- [x] basic parse to RunConfig with listTools=true
- [x] --detail flag sets toolDetail=true
- [x] `alice tools` 列出已注册工具 (exit 0)
- [x] `alice tools --detail` 列出工具详情 (exit 0)
- [x] RunConfig builder: listTools / toolDetail

#### 3. `config` 子命令 (CommandParser + Builder + AliceConfigStore)
- [x] `alice config` — 显示全部配置概览 (exit 0)
- [x] `alice config get <key>` — 获取单个配置项 (exit 0)
- [x] `alice config set <key> <value>` — 设置配置项 (exit 0)
- [x] `alice config --help` — 帮助信息 (exit 0)
- [x] `alice config` 不带参数 — `configAction="show"`
- [x] 持久化到 `~/.alice/config.json`
- [x] 读取优先级: 环境变量 > config.json > 内建默认值
- [x] RunConfig builder: configAction / configKey / configValue
- [x] AliceConfigStore: 空文件创建 / set/get / delete / 持久化 / 键名转换 / 不可修改视图 / 损坏降级

#### 4. `routine` 子命令 (CommandParser + Builder)
- [x] basic parse with cron expression
- [x] --list flag
- [x] -l short flag
- [x] cron + --list together
- [x] --help returns null
- [x] no args (empty)
- [x] RunConfig builder: routineCron / listRoutines

#### 5. `sub-agent` 子命令 (CommandParser + Builder)
- [x] parse --spawn goal
- [x] parse --connect name + --acp-endpoint
- [x] parse --list
- [x] parse --cancel \<id\>
- [x] parse --results \<id\>
- [x] parse --send \<id\> + --message
- [x] parse --prompt + --agent-id
- [x] parse sub-agent with --help
- [x] parse sub-agent without any option
- [x] RunConfig builder: subAgentSpawnGoal / subAgentConnectName+Endpoint / subAgentList
- [x] RunConfig builder: subAgentCancelId / subAgentResultsId
- [x] RunConfig builder: subAgentSendId+SendMessage / subAgentPromptAgentId+PromptText

### CLI 交互式会话 (picocli 解析 → `JLineChatSession`)

#### 6. `chat` 子命令
- [x] basic parse to RunConfig.chat() == true
- [x] 启动交互式会话
- [x] 退出码 0

#### chat 斜杠命令 (`AliceCliLauncher.dispatchCommand()`)
> `dispatchCommand()` 是 `AliceCliLauncher` 的公共方法，被 `JLineChatSession` (chat 子命令) 内部调用。`/xxx` 格式是 chat 交互模式的内部命令（如 `/exec ls`、`/clear`、`/exit`），由 `AgentCommand.parse()` 解析后通过 dispatch 执行。这些不和 CLI 二进制子命令（使用 `--xxx` 标志参数）混淆。

- [x] dispatch /run → AcquireGoalCmd
- [x] dispatch /exec → ExecuteRawCmd
- [x] dispatch /skill → RegisterSkillCmd
- [x] dispatch /rules → UpdateRulesCmd
- [x] dispatch /reload → ReloadKernelCmd
- [x] dispatch /model → SwitchModelCmd
- [x] dispatch /new → ResetSessionCmd
- [x] dispatch /clear → ClearContextCmd
- [x] dispatch /context → ViewContextCmd
- [x] dispatch /compact → CompactContextCmd
- [x] dispatch /feedback → FeedbackCmd
- [x] dispatch /exit → InterruptCmd
- [x] dispatch /routine → RegisterRoutineCmd
- [x] dispatch /sub-agent spawn/connect/list/cancel/results/send/prompt
- [x] dispatch null/unknown → EXIT_PARAM_ERROR

---

## 统计

- **单元测试数**: **155 个**（Spock）
- **E2E 测试数**: **76 个**（Python, 3 场景文档见下）
  - [scene-cli-subcommands.md](./scene-cli-subcommands.md) — 25 个
  - [scene-tui-slash-commands.md](./scene-tui-slash-commands.md) — 30 个
  - [scene-dispatch-full-coverage.md](./scene-dispatch-full-coverage.md) — 21 个
- **总覆盖率**: alice-facade-cmd 各 cmd 100%

---

## E2E 执行结果（编译后 `alice` 二进制）

| # | 用例 | 命令 | 预期结果 | 退出码 | 状态 |
|---|------|------|----------|--------|------|
| 1 | 无子命令 | `alice` | 打印帮助信息 (exit 2) | 2 | ✅ |
| 2 | 顶层帮助 | `alice --help` | 打印帮助信息 | 0 | ✅ |
| 3 | 顶层版本 | `alice --version` | 打印版本号 | 0 | ✅ |
| 4 | run 基本执行 | `alice run "count files"` | RunConfig 正确解析, PPAO 循环执行 | 0 | ✅ |
| 5 | run 帮助 | `alice run --help` | 打印 run 子命令帮助 | 0 | ✅ |
| 6 | run verbose+json | `alice run -v --json "test"` | verbose+json 标志生效 | 0 | ✅ |
| 7 | run model 覆盖 | `alice run -m gpt-4o "hello"` | model override 生效 | 0 | ✅ |
| 8 | run 未知标志 | `alice run --unknown-flag` | 参数解析错误 | 2 | ✅ |
| 9 | chat 交互 | `echo exit \| alice chat` | 交互式 chat 启动并退出 | 0 | ✅ |
| 10 | tools 列表 | `alice tools` | 列出已注册工具 (无工具时提示) | 0 | ✅ |
| 11 | tools 详情 | `alice tools --detail` | 列出工具详情 | 0 | ✅ |
| 12 | tools 帮助 | `alice tools --help` | 打印 tools 帮助 | 0 | ✅ |
| 13 | config 概览 | `alice config` | 显示全部配置（含来源标注） | 0 | ✅ |
| 14 | config get | `alice config get default.model` | 读取内建默认值 | 0 | ✅ |
| 15 | config set | `alice config set openai.api_key sk-xxx` | 持久化到 ~/.alice/config.json | 0 | ✅ |
| 16 | config set 再读取 | `alice config get openai.api_key` | 从 config.json 读取 | 0 | ✅ |
| 17 | config 环境变量覆盖 | `OPENAI_API_KEY=... alice config get openai.api_key` | 优先读取环境变量 | 0 | ✅ |
| 18 | config 帮助 | `alice config --help` | 打印 config 帮助 | 0 | ✅ |
| 19 | routine 注册 | `alice routine "0 */2 * * * ?"` | routineCron 正确解析 | 0 | ✅ |
| 20 | routine 列表 | `alice routine --list` | listRoutines=true | 0 | ✅ |
| 21 | routine 帮助 | `alice routine --help` | 打印 routine 帮助 | 0 | ✅ |
| 22 | sub-agent spawn | `alice sub-agent --spawn "analyze logs"` | subAgentSpawnGoal 正确解析 | 0 | ✅ |
| 23 | sub-agent list | `alice sub-agent --list` | subAgentList=true | 0 | ✅ |
| 24 | sub-agent connect | `alice sub-agent --connect "worker1" --acp-endpoint "http://..."` | connectName+endpoint 正确解析 | 0 | ✅ |
| 25 | sub-agent cancel | `alice sub-agent --cancel "abc-123"` | cancel 参数正确解析 | 0 | ✅ |
| 26 | sub-agent results | `alice sub-agent --results "abc-123"` | results 参数正确解析 | 0 | ✅ |
| 27 | sub-agent send | `alice sub-agent --send "agent1" --message "hello"` | send+message 正确解析 | 0 | ✅ |
| 28 | sub-agent prompt | `alice sub-agent --prompt "do task" --agent-id "ext-agent"` | prompt+agent-id 正确解析 | 0 | ✅ |
| 29 | sub-agent 空选项 | `alice sub-agent` | 空 subAgent, 正常执行 | 0 | ✅ |
| 30 | sub-agent 帮助 | `alice sub-agent --help` | 打印 sub-agent 帮助 | 0 | ✅ |
| 31 | run 缺少 task | `alice run` | Missing required parameter | 2 | ✅ |

## E2E 退出码规范

| 场景 | 退出码 | 说明 |
|------|--------|------|
| 正常执行 / help / version | 0 | SUCCESS |
| 运行时错误 / 未实现 | 1 | RUNTIME_ERROR |
| 参数解析错误 | 2 | PARAM_ERROR |
| 用户中断 (Ctrl+C) | 130 | INTERRUPTED |

## 修复

🛠 在 e2e 测试过程中发现并修复:
1. `CliRoot` 的 `@Command` 注解缺少 `subcommands` 属性导致 `routine` 和 `sub-agent` 不可解析
2. 删除重复的 `cmdLine.addSubcommand()` 调用（picocli 通过注解自动注册后报 DuplicateNameException）
3. `AliceCliLauncher.run()` 中 help/version 返回 `EXIT_PARAM_ERROR(2)` → 改为 `EXIT_SUCCESS(0)`
4. `alice tools` 从未实现 (exit 1) → 完整实现：通过 `ToolRegistryHolder` 列出已注册工具，支持 `--detail`
5. `alice config` 从未实现 (exit 1) → 完整实现：支持 `get/set/show`，持久化到 `~/.alice/config.json`
6. 新增 `AliceConfigStore` 提供线程安全、原子写入的 JSON 配置存储
7. 配置优先级: 环境变量 > config.json > 内建默认值

## 新增文件

| 文件 | 用途 |
|------|------|
| `alice-tool-gateway/.../ToolRegistryHolder.java` | `ToolRegistry` 全局单例持有者 |
| `alice-facade-cmd/.../config/AliceConfigStore.java` | 持久化 JSON 配置存储 (get/set/delete) |
| `alice-facade-cmd/.../test/.../AliceConfigStoreSpec.groovy` | AliceConfigStore 12 个测试 |
| `docs/config/README.md` | 配置系统文档 |
| `docs/config/example.yaml` | 配置键参考 |

## 修改文件

| 文件 | 修改内容 |
|------|----------|
| `CommandParser.java` | `ToolsCommand`/`ConfigCommand` 添加 `toRunConfig()`/`toAgentCommand()`；`ConfigCommand` 参数 `arity="0..1"` |
| `RunConfig.java` | 添加 `listTools`/`toolDetail`/`configAction`/`configKey`/`configValue` 字段+getter+builder+toString |
| `AliceCliLauncher.java` | 添加 `handleListTools()`/`handleConfig()`/`configStore()`；`dispatchCommand()` 全命令分支 |
| `module-info.java` | 添加 `requires alice.agent.alice.tool.gateway.main` + `opens ...config` |
| `build.gradle` | 添加 `implementation project(':alice-tool-gateway')` |
| `CommandParserSpec.groovy` | tools/config/sub-agent 解析测试（从 throw 改为 parse） |
| `RunConfigSpec.groovy` | 添加 listTools/toolDetail/configAction/configKey/configValue/subAgent 字段测试 |
| `AliceCliLauncherSpec.groovy` | 添加 --help/--version exit 0 测试 + dispatchCommand 全覆盖 |
| `AliceConfigStore.java` | 构造器 `(Path)` 改为 `public` 以便测试 |

## 最终测试结果

- **单元测试**: **135 tests**, 全部通过 ✅
- **E2E 测试**: 所有 6 个子命令 (run, chat, tools, config, routine, sub-agent) 全部正确可用 ✅
- **退出码**: 所有 help/version → 0, 错误 → 2 ✅
- **配置持久化**: `alice config set` → `~/.alice/config.json`，原子写入 ✅
- **配置读取优先级**: 环境变量 > config.json > 内建默认值，来源自标注 ✅
