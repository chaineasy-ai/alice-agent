# Alice Agent 三层金字塔测试设计

遵循**金字塔测试**原则，将测试按 10% 场景层（E2E）、20% 模块集成层（Module）、70% 单元层（Unit）组织。

| 层级 | 占比 | 测试类型 | 位置 |
|------|------|---------|------|
| 🔷 **场景 (Scene)** | 10% | E2E 端到端 | `e2e/` | 
| 🟩 **模块 (Module)** | 20% | 模块集成 + 端点 | `docs/*/e2e/` |
| ⬜ **单元 (Unit)** | 70% | Spock 单元测试 | `*/src/test/groovy/` |

---

## 一、场景层 (Scene) — 10%

根场景 E2E，验证**完整系统链路**：CLI 二进制启动 → 参数解析 → Agent 初始化 → 执行 → 输出。

| 场景 | 描述 | 文件 | 状态 |
|------|------|------|------|
| S-01 | 完整系统启动与基础任务 | `e2e/test_base.py` | ✅ 已实现 |
| S-02 | 模型供应商集成（Gemma4） | `e2e/gemma4/test_gemma4_integration.py` | ✅ 已实现 |
| S-03 | ACP 子代理通信 | `e2e/test_dispatch.py` | ✅ 已实现 |
| S-04 | AliceAgent 生命周期 | `e2e/test_agent_lifecycle.py` | 🔜 计划 |
| S-05 | 多轮 ReAct 循环终止验证 | `e2e/test_react_loop.py` | 🔜 计划 |

> **设计原则**：每场景对应一个真实用户使用路径（CLI/TUI），不 Mock 核心依赖。

---

## 二、模块层 (Module) — 20%

每个模块的 E2E 端点测试，验证**模块边界**的输入输出。所有测试 case 来源于 `docs/alice-agent-command/DESIGN.md` 中定义的密封指令层次结构。**一个 case 对应一个 doc 文件**，保持简洁。

### 2.1 alice-facade-cmd — CLI 端点

| 文件 | 对应 Case Doc | 覆盖范围 |
|------|-------------|---------|
| `docs/alice-facade-cmd/e2e/scene-cli-subcommands.md` | `docs/alice-agent-command/e2e/case-run.md` | 6 个 CLI 子命令 (run/chat/tools/config/routine/sub-agent) 的 picocli 参数解析 |
| `docs/alice-facade-cmd/e2e/scene-dispatch-full-coverage.md` | `docs/alice-agent-command/e2e/case-dispatch-full-coverage.md` | dispatchCommand() 对全部 21 种密封子类型的分发 |
| `docs/alice-facade-cmd/e2e/scene-cli-subcommands.md` | `docs/alice-agent-command/e2e/case-chat.md` | JLine chat 会话的管道输入 |
| `docs/alice-facade-cmd/e2e/scene-cli-subcommands.md` | `docs/alice-agent-command/e2e/case-config.md` | config 子命令的配置读写 |
| `docs/alice-facade-cmd/e2e/scene-cli-subcommands.md` | `docs/alice-agent-command/e2e/case-tools.md` | tools 子命令的工具列表 |
| `docs/alice-facade-cmd/e2e/scene-cli-subcommands.md` | `docs/alice-agent-command/e2e/case-routine.md` | routine 子命令的 cron 解析 |
| `docs/alice-facade-cmd/e2e/scene-cli-subcommands.md` | `docs/alice-agent-command/e2e/case-sub-agent.md` | sub-agent 子命令的 7 种操作 |

**实现文件**: `docs/alice-facade-cmd/e2e/test_cli_categories.py`

> **注意**：`case-chat.md` / `case-dispatch-full-coverage.md` 中的 JLine 依赖路径无法通过子进程管道捕获，已在 `scene-dispatch-full-coverage.md` 中标记为跳过并交叉引用单元测试。

### 2.2 alice-facade-tui — TUI 端点

| 文件 | 对应 Case Doc | 覆盖范围 |
|------|-------------|---------|
| `docs/alice-facade-tui/e2e/scene-tui-slash-commands.md` | `docs/alice-agent-command/e2e/case-tui-slash-commands.md` | AgentCommand.parse() 对 20 种斜杠命令 + 自然语言的映射 |

**实现文件**: `docs/alice-facade-tui/e2e/test_slash_commands.py`

> **注意**：所有 TUI 斜杠命令均依赖 JLine 终端交互，E2E 标记为跳过。映射正确性由 `AgentCommandParseSpec.groovy` 等单元测试覆盖。

### 2.3 模块测试映射总表

| Case Doc | 源 | 模块 E2E Doc | 实现文件 | 状态 |
|---------|------|-------------|---------|------|
| `case-run.md` | DESIGN.md §2 — ExecutionCmd | `scene-cli-subcommands.md` | `test_cli_categories.py` | ✅ |
| `case-chat.md` | DESIGN.md §2 — ControlCmd | `scene-cli-subcommands.md` | `test_cli_categories.py` | ⏭️ JLine |
| `case-config.md` | DESIGN.md §2 — 系统配置 | `scene-cli-subcommands.md` | `test_cli_categories.py` | ✅ |
| `case-tools.md` | DESIGN.md §2 — CapabilityCmd | `scene-cli-subcommands.md` | `test_cli_categories.py` | ✅ |
| `case-routine.md` | DESIGN.md §2 — RoutineTimeCmd | `scene-cli-subcommands.md` | `test_cli_categories.py` | ✅ |
| `case-sub-agent.md` | DESIGN.md §2 — SubAgentCmd | `scene-cli-subcommands.md` | `test_cli_categories.py` | ✅ |
| `case-dispatch-full-coverage.md` | DESIGN.md §2 — 全部 21 种 | `scene-dispatch-full-coverage.md` | `test_cli_categories.py` | ✅ / ⏭️ |
| `case-tui-slash-commands.md` | DESIGN.md §2 — 20 种 TUI 映射 | `scene-tui-slash-commands.md` | `test_slash_commands.py` | ⏭️ JLine |

---

## 三、单元层 (Unit) — 70%

每个模块内的 Spock (Groovy) 单元测试，覆盖密封指令的构造、解析、验证、序列化等。

### 3.1 alice-agent-command (11 个)

| 测试文件 | 覆盖范围 |
|---------|---------|
| `AgentCommandParseSpec.groovy` | 全部 20+ 种斜杠命令 + 自然语言的 `parse()` 映射 |
| `AgentCommandSealedHierarchySpec.groovy` | AgentCommand 完整密封层次 + sealed/non-sealed 禁止子类化 |
| `AlignmentCmdSpec.groovy` | SwitchModelCmd 构造/字段/toString/equals |
| `CapabilityCmdSpec.groovy` | RegisterSkillCmd / UpdateRulesCmd / ReloadKernelCmd |
| `ControlCmdSpec.groovy` | 7 种 ControlCmd 子类型 |
| `ExecutionCmdSpec.groovy` | AcquireGoalCmd / ExecuteRawCmd |
| `RoutineTimeCmdSpec.groovy` | RegisterRoutineCmd / TimeTriggeredCmd |
| `RoutineTimeCmdParseSpec.groovy` | `/routine` parse 映射 + cron 表达式解析 |
| `SubAgentCmdSpec.groovy` | 7 种 SubAgentCmd 子类型构造/字段 |
| `SubAgentCmdParseSpec.groovy` | `/sub-agent` 各子命令 parse 映射 |
| `SubAgentCmdSealedHierarchySpec.groovy` | SubAgentCmd 密封层次 + 非法子类化禁止 |

### 3.2 alice-bootstrap (2 个)

| 测试文件 | 覆盖范围 |
|---------|---------|
| `AliceAgentSpec.groovy` | AliceAgent 启动/停止生命周期 |
| `CommandDispatchLoopSpec.groovy` | 命令分发循环 + 信号处理 |

### 3.3 alice-core-agent (9 个)

| 测试文件 | 覆盖范围 |
|---------|---------|
| `ActionSpec.groovy` | Action 构造/字段/toString |
| `AgentConfigSpec.groovy` | AgentConfig 加载/校验 |
| `AgentContextSpec.groovy` | AgentContext 状态管理 |
| `AgentFacadeSpec.groovy` | Facade → Agent 桥接 |
| `AgentPpaoLoopSpec.groovy` | PPAO 循环（Plan → Process → Act → Observe） |
| `ObservationSpec.groovy` | Observation 字段/验证 |
| `StepResultSpec.groovy` | StepResult (Finish/Continue) |
| `SubAgentManagerSpec.groovy` | 子代理管理器 |
| `SubAgentRegistrySpec.groovy` | 子代理注册表 |

### 3.4 alice-core-planner (1 个)

| 测试文件 | 覆盖范围 |
|---------|---------|
| `PlannerServiceSpec.groovy` | PlannerService 计划生成 |

### 3.5 alice-env-adapter (8 个)

| 测试文件 | 覆盖范围 |
|---------|---------|
| `EnvEventSpec.groovy` | 环境事件构造/分发 |
| `EnvManagerSpec.groovy` | EnvManager 生命周期 |
| `FakeMcpTransport.groovy` | Fake transport（测试用） |
| `McpClientSpec.groovy` | MCP 客户端连接/消息 |
| `EnvSnapshotSpec.groovy` | 环境快照 |
| `SnapshotManagerSpec.groovy` | 快照管理器 |
| `EnvStateSpec.groovy` | 环境状态机 |
| `FakeTransportSpec.groovy` | Fake transport 测试 |

### 3.6 alice-facade-cmd (7 个)

| 测试文件 | 覆盖范围 |
|---------|---------|
| `AliceCliLauncherSpec.groovy` | CLI 启动器 + dispatchCommand() 全部 21 种分支 |
| `JLineChatSessionSpec.groovy` | JLine 会话启动/退出 |
| `AliceConfigStoreSpec.groovy` | 配置存储读写 |
| `CommandParserSpec.groovy` | picocli 参数解析 |
| `RunConfigSpec.groovy` | RunConfig 构造/字段 |
| `JsonOutputRendererSpec.groovy` | JSON 输出渲染 |
| `TextOutputRendererSpec.groovy` | 文本输出渲染 |

### 3.7 alice-facade-tui (1 个)

| 测试文件 | 覆盖范围 |
|---------|---------|
| `TuiSpec.groovy` | TUI 组件生命周期 |

### 3.8 alice-memory-vault (23 个)

| 测试文件 | 覆盖范围 |
|---------|---------|
| `MemoryVaultSpec.groovy` | 内存库基础接口 |
| `MemoryVaultEdgeCaseSpec.groovy` | 边缘情况（空/并发/损坏） |
| `JVectorSemanticVaultSpec.groovy` | JVector 语义库 |
| `WalEpisodicVaultSpec.groovy` | WAL 持久化事件库 |
| `WalEntitySpec.groovy` | WAL 实体 |
| `WalSessionSpec.groovy` | WAL 会话 |
| `WalStoreSpec.groovy` | WAL 存储 |
| `WalStorePerformanceSpec.groovy` | WAL 性能基准 |
| `WalAppenderCheckpointSpec.groovy` | WAL Appender + Checkpoint |
| `WalCompactorSpec.groovy` | WAL Compactor |
| `FileWalStoreSpec.groovy` | 文件 WAL 存储 |
| `CrashRecoveryE2ESpec.groovy` | 崩溃恢复 E2E |
| `RecoveryEngineSpec.groovy` | 恢复引擎 |
| `PromptMelterSpec.groovy` | Prompt 熔断器 |
| `DreamingEngineSpec.groovy` | 梦境引擎 |
| `DreamingEngineConcurrencySpec.groovy` | 梦境引擎并发 |
| `DreamingEngineIntegrationSpec.groovy` | 梦境引擎集成 |
| `DreamingEngineTriggerSpec.groovy` | 梦境引擎触发 |
| `DreamingSessionSpec.groovy` | 梦境会话 |
| `DreamingTriggerConfigSpec.groovy` | 梦境触发配置 |
| `SessionStateManagerSpec.groovy` | 会话状态管理 |
| `ConflictResolverSpec.groovy` | 冲突解决器 |
| `WalSessionReadGuardSpec.groovy` | WAL 会话读取保护 |

### 3.9 alice-model (7 个)

| 测试文件 | 覆盖范围 |
|---------|---------|
| `ModelSpec.groovy` | Model 接口/字段 |
| `ModelProviderSpec.groovy` | ModelProvider 管理 |
| `ModelConfigLoaderSpec.groovy` | 模型配置加载 |
| `CallSpec.groovy` | Call 构造/状态 |
| `CallStatusSpec.groovy` | CallStatus 枚举 |
| `ModelEnumSpec.groovy` | ModelEnum 枚举 |
| `ClaudeSupplierSpec.groovy` | Claude 供应商适配 |

### 3.10 alice-tool-gateway (8 个)

| 测试文件 | 覆盖范围 |
|---------|---------|
| `ToolRegistrySpec.groovy` | 工具注册表 |
| `ExecutionEngineSpec.groovy` | 执行引擎 |
| `ToolDiscoverySpec.groovy` | 工具发现 |
| `ToolResultSpec.groovy` | 工具结果 |
| `ToolMetadataSpec.groovy` | 工具元数据 |
| `SandboxProviderSpec.groovy` | 沙箱提供者 |
| `SchemaGeneratorSpec.groovy` | JSON Schema 生成 |
| `AnnotationSpec.groovy` | 注解扫描 |

---

## 四、测试覆盖统计

| 层级 | 类目 | 测试数 | 占比 |
|------|------|--------|------|
| 🔷 场景 | E2E 根场景 | 3 (已实现) | 10% |
| 🟩 模块 | CLI 端点 | 25 (case) | 20% |
| 🟩 模块 | TUI 端点 | 30 (case) | — (JLine 跳过) |
| ⬜ 单元 | alice-agent-command | 11 | 70% |
| ⬜ 单元 | alice-bootstrap | 2 | |
| ⬜ 单元 | alice-core-agent | 9 | |
| ⬜ 单元 | alice-core-planner | 1 | |
| ⬜ 单元 | alice-env-adapter | 8 | |
| ⬜ 单元 | alice-facade-cmd | 7 | |
| ⬜ 单元 | alice-facade-tui | 1 | |
| ⬜ 单元 | alice-memory-vault | 23 | |
| ⬜ 单元 | alice-model | 7 | |
| ⬜ 单元 | alice-tool-gateway | 8 | |
| **合计** | | **77+** | **100%** |

---

## 五、Case Doc 索引

所有模块层测试 case 来源于 `docs/alice-agent-command/DESIGN.md` 中定义的密封指令层次。每个 case doc 对应一个模块 E2E 场景：

| Case Doc | 来源 (DESIGN.md 章节) | 描述 |
|---------|---------------------|------|
| `case-run.md` | §2 ExecutionCmd — AcquireGoalCmd / ExecuteRawCmd | CLI `run` 子命令 |
| `case-chat.md` | §2 ControlCmd — ResetSessionCmd / FeedbackCmd / InterruptCmd 等 | JLine chat 交互 |
| `case-config.md` | §2 系统配置管理 | CLI `config` 子命令 |
| `case-tools.md` | §2 CapabilityCmd — RegisterSkillCmd | CLI `tools` 子命令 |
| `case-routine.md` | §2 RoutineTimeCmd — RegisterRoutineCmd | CLI `routine` 子命令 |
| `case-sub-agent.md` | §2 SubAgentCmd — 全部 7 种子类型 | CLI `sub-agent` 子命令 |
| `case-dispatch-full-coverage.md` | §2 全部 5 大类 21 种子类型 | dispatchCommand() 全覆盖 |
| `case-tui-slash-commands.md` | §2 全部 5 大类 20 种 TUI 映射 | AgentCommand.parse() |

---

## 六、质量门禁

- **`./gradlew check`** — 所有单元测试通过
- **`python e2e/run_alice_e2e.py`** — 所有场景 E2E 通过
- **`./gradlew spotlessCheck`** — 代码格式合规
- 新建功能必须同步添加对应层级的测试（优先单元，再模块，最后场景）
