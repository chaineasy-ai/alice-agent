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

每个模块的**端点测试**（Endpoint Test），验证**模块边界**的输入输出。所有测试 case 来源于 `docs/alice-agent-command/DESIGN.md` 中定义的密封指令层次结构 + 各模块 DESIGN.md 中的核心接口定义。**一个 case 对应一个 doc 文件**，保持简洁。

### 2.1 前端 Facade 端点（已有）

#### alice-facade-cmd — CLI 端点

| 文件 | 对应 Case Doc | 覆盖范围 |
|------|-------------|---------|
| `docs/alice-facade-cmd/e2e/scene-cli-subcommands.md` | `docs/alice-agent-command/e2e/case-run.md` | 6 个 CLI 子命令 (run/chat/tools/config/routine/sub-agent) 的 picocli 参数解析 |
| `docs/alice-facade-cmd/e2e/scene-dispatch-full-coverage.md` | `docs/alice-agent-command/e2e/case-dispatch-full-coverage.md` | dispatchCommand() 对全部 21 种密封子类型的分发 |

**实现文件**: `docs/alice-facade-cmd/e2e/test_cli_categories.py`

#### alice-facade-tui — TUI 端点

| 文件 | 对应 Case Doc | 覆盖范围 |
|------|-------------|---------|
| `docs/alice-facade-tui/e2e/scene-tui-slash-commands.md` | `docs/alice-agent-command/e2e/case-tui-slash-commands.md` | AgentCommand.parse() 对 20 种斜杠命令 + 自然语言的映射 |

**实现文件**: `docs/alice-facade-tui/e2e/test_slash_commands.py`

### 2.2 核心模块端点（Hole Test — 洞测试）

以下 8 个核心模块的端点测试按 **"挖洞"（Probing）** 方式设计：不深入内部实现细节，仅验证模块边界的 3~5 个关键接口输入输出是否正确。每个挖洞点对应一个 scene doc、一个 case doc、和一个 Python 驱动脚本。

**洞测试遵循 hole-tdd（洞驱动开发）**：Red → Green → Refactor。

---

### 2.2.1 alice-core-agent — 核心代理生命周期

| 洞号 | 端点 | 对应的 Spec 文件 | 状态 |
|------|------|-----------------|------|
| AGT-P01 | `AgentExecutor.execute()` 正常流 | `AgentPpaoLoopSpec` | 🟩 GREEN |
| AGT-P02 | `StepResult` 模式匹配 | `StepResultSpec` | 🟩 GREEN |
| AGT-P03 | `AgentContext` 会话状态管理 | `AgentContextSpec` | 🟩 GREEN |
| AGT-P05 | `SubAgentManager` 注册/查找 | `SubAgentManagerSpec` | 🟩 GREEN |

**文件**：
- Case doc: `docs/alice-agent-command/e2e/case-core-agent.md`
- Scene doc: `docs/alice-core-agent/e2e/scene-executor-endpoints.md`
- 实现: `docs/alice-core-agent/e2e/hole_test_core_agent.py`

---

### 2.2.2 alice-core-planner — 规划引擎

| 洞号 | 端点 | 对应的 Spec 文件 | 状态 |
|------|------|-----------------|------|
| PLN-P01 | `PlannerService.plan()` 调用 | `PlannerServiceSpec` | 🟩 GREEN |
| PLN-P02 | `FastPathStrategy.decide()` 快速路径 | `PlannerServiceSpec` | 🟩 GREEN |
| PLN-P03 | `SlowPathStrategy.decide()` 慢速路径 | `PlannerServiceSpec` | 🟩 GREEN |
| PLN-P04 | `WorldModel.predict()` 世界模型预测 | `PlannerServiceSpec` | 🟩 GREEN |

**文件**：
- Case doc: `docs/alice-agent-command/e2e/case-core-planner.md`
- Scene doc: `docs/alice-core-planner/e2e/scene-planner-endpoints.md`
- 实现: `docs/alice-core-planner/e2e/hole_test_planner.py`

---

### 2.2.3 alice-env-adapter — 环境适配器

| 洞号 | 端点 | 对应的 Spec 文件 | 状态 |
|------|------|-----------------|------|
| ENV-P01 | `EnvManager.execute()` 动作执行 | `EnvManagerSpec` | 🟩 GREEN |
| ENV-P02 | `McpClient.callTool()` MCP 工具调用 | `McpClientSpec` | 🟩 GREEN |
| ENV-P03 | `McpClient.listTools()` MCP 工具列表 | `FakeTransportSpec` | 🟩 GREEN |
| ENV-P04 | `SnapshotManager.save()` + `rollback()` | `SnapshotManagerSpec` | 🟩 GREEN |

**文件**：
- Case doc: `docs/alice-agent-command/e2e/case-env-adapter.md`
- Scene doc: `docs/alice-env-adapter/e2e/scene-env-endpoints.md`
- 实现: `docs/alice-env-adapter/e2e/hole_test_env.py`

---

### 2.2.4 alice-tool-gateway — 工具网关

| 洞号 | 端点 | 对应的 Spec 文件 | 状态 |
|------|------|-----------------|------|
| TGW-P01 | `ToolRegistry.register()` + `lookup()` | `ToolRegistrySpec` | 🟩 GREEN |
| TGW-P02 | `ToolDiscovery.scanAndRegister()` | `ToolDiscoverySpec` | 🟩 GREEN |
| TGW-P03 | `ExecutionEngine.invoke()` 工具调用 | `ExecutionEngineSpec` | 🟩 GREEN |
| TGW-P04 | `SandboxProvider.executeInIsolation()` | `SandboxProviderSpec` | 🟩 GREEN |

**文件**：
- Case doc: `docs/alice-agent-command/e2e/case-tool-gateway.md`
- Scene doc: `docs/alice-tool-gateway/e2e/scene-tool-gateway-endpoints.md`
- 实现: `docs/alice-tool-gateway/e2e/hole_test_tool_gateway.py`

---

### 2.2.5 alice-memory-vault — 记忆库

| 洞号 | 端点 | 对应的 Spec 文件 | 状态 |
|------|------|-----------------|------|
| MEM-P01 | `VaultController.memorize()` + `recall()` | `MemoryVaultSpec` | 🟩 GREEN |
| MEM-P02 | `EpisodicVault.getRecentTrace()` | `WalEpisodicVaultSpec` | 🟩 GREEN |
| MEM-P03 | `SemanticVault.search()` 语义检索 | `JVectorSemanticVaultSpec` | 🟩 GREEN |
| MEM-P04 | `ProceduralVault.matchPattern()` | `MemoryVault*` | 🟩 GREEN |
| MEM-P05 | `WalStore` 持久化 + 崩溃恢复 | `CrashRecoveryE2ESpec` | 🟩 GREEN |

**文件**：
- Case doc: `docs/alice-agent-command/e2e/case-memory-vault.md`
- Scene doc: `docs/alice-memory-vault/e2e/scene-memory-endpoints.md`
- 实现: `docs/alice-memory-vault/e2e/hole_test_memory.py`

---

### 2.2.6 alice-model — 模型抽象层

| 洞号 | 端点 | 对应的 Spec 文件 | 状态 |
|------|------|-----------------|------|
| MDL-P01 | `ModelProvider.dispatch()` 供应商调度 | `ModelProviderSpec` | 🟩 GREEN |
| MDL-P02 | `Call.execute()` 调用生命周期 | `CallSpec` | 🟩 GREEN |
| MDL-P03 | `ModelSupplier.chat()` 响应解析 | `ClaudeSupplierSpec` | 🟩 GREEN |
| MDL-P04 | `ModelConfigLoader` 加载配置 | `ModelConfigLoaderSpec` | 🟩 GREEN |
| MDL-P05 | 多供应商切换路由 | `ModelProviderSpec` | 🟩 GREEN |

**文件**：
- Case doc: `docs/alice-agent-command/e2e/case-model.md`
- Scene doc: `docs/alice-model/e2e/scene-model-endpoints.md`
- 实现: `docs/alice-model/e2e/hole_test_model.py`

---

### 2.2.7 alice-guardrail — 护栏/验证

| 洞号 | 端点 | 对应的 Spec 文件 | 状态 |
|------|------|-----------------|------|
| GRD-P01 | `GuardrailService.verifyPlan()` 前置验证 | `GuardrailServiceSpec` | 🟩 GREEN |
| GRD-P02 | `GuardrailService.verifyResult()` 后置验证 | `GuardrailServiceSpec` | 🟩 GREEN |
| GRD-P03 | `PolicyEngine` 策略引擎 | `PolicyEngineSpec` | 🟩 GREEN |
| GRD-P04 | `HallucinationDetector` 幻觉检测 | `HallucinationDetectorSpec` | 🟩 GREEN |
| GRD-P05 | `PermissionSandboxValidator` 权限沙箱 | `PermissionSandboxValidatorSpec` | 🟩 GREEN |

**文件**：
- Case doc: `docs/alice-agent-command/e2e/case-guardrail.md`
- Scene doc: `docs/alice-guardrail/e2e/scene-guardrail-endpoints.md`
- 实现: `docs/alice-guardrail/e2e/hole_test_guardrail.py`

---

### 2.2.8 alice-facade-web — Web 端点

| 洞号 | 端点 | 依赖 | 状态 |
|------|------|------|------|
| WEB-P01 | `GET /health` 健康检查 | Web 服务器运行 | ⏭️ SKIP |
| WEB-P02 | HTTP 404 处理 | Web 服务器运行 | ⏭️ SKIP |
| WEB-P03 | CORS 头 | Web 服务器运行 | ⏭️ SKIP |

**文件**：
- Case doc: `docs/alice-agent-command/e2e/case-web.md`
- Scene doc: `docs/alice-facade-web/e2e/scene-web-endpoints.md`
- 实现: `docs/alice-facade-web/e2e/hole_test_web.py`

---

### 2.2.9 alice-bootstrap — 引导模块

| 洞号 | 端点 | 对应的 Spec 文件 | 状态 |
|------|------|-----------------|------|
| BTS-P01 | `FacadeSelector.select()` 外壳选择 | `AliceAgentSpec` | 🟩 GREEN |
| BTS-P02 | `AppBootstrapper.bootstrap()` 启动流 | `CommandDispatchLoopSpec` | 🟩 GREEN |
| BTS-P03 | `IFacadeLauncher.launch()` 接口一致 | — | 🟩 GREEN |

**文件**：
- Case doc: `docs/alice-agent-command/e2e/case-bootstrap.md`
- Scene doc: `docs/alice-bootstrap/e2e/scene-bootstrap-endpoints.md`
- 实现: `docs/alice-bootstrap/e2e/hole_test_bootstrap.py`

---

### 2.3 模块测试映射总表

| Case Doc | 源 | 模块 Scene Doc | 模块 | 实现方式 | 状态 |
|---------|------|---------------|------|---------|------|
| `case-run.md` | DESIGN.md §2 ExecutionCmd | `scene-cli-subcommands.md` | alice-facade-cmd | Python E2E | ✅ |
| `case-chat.md` | DESIGN.md §2 ControlCmd | `scene-cli-subcommands.md` | alice-facade-cmd | Python E2E | ⏭️ JLine |
| `case-config.md` | DESIGN.md §2 系统配置 | `scene-cli-subcommands.md` | alice-facade-cmd | Python E2E | ✅ |
| `case-tools.md` | DESIGN.md §2 CapabilityCmd | `scene-cli-subcommands.md` | alice-facade-cmd | Python E2E | ✅ |
| `case-routine.md` | DESIGN.md §2 RoutineTimeCmd | `scene-cli-subcommands.md` | alice-facade-cmd | Python E2E | ✅ |
| `case-sub-agent.md` | DESIGN.md §2 SubAgentCmd | `scene-cli-subcommands.md` | alice-facade-cmd | Python E2E | ✅ |
| `case-dispatch-full-coverage.md` | DESIGN.md §2 全部 21 种 | `scene-dispatch-full-coverage.md` | alice-facade-cmd | Python E2E | ✅ / ⏭️ |
| `case-tui-slash-commands.md` | DESIGN.md §2 20 种 TUI 映射 | `scene-tui-slash-commands.md` | alice-facade-tui | Python E2E | ⏭️ JLine |
| `case-core-agent.md` | DESIGN.md + alice-core-agent DESIGN | `scene-executor-endpoints.md` | alice-core-agent | hole_test + Spock | 🟩 GREEN |
| `case-core-planner.md` | DESIGN.md + alice-core-planner DESIGN | `scene-planner-endpoints.md` | alice-core-planner | hole_test + Spock | 🟩 GREEN |
| `case-env-adapter.md` | DESIGN.md + alice-env-adapter DESIGN | `scene-env-endpoints.md` | alice-env-adapter | hole_test + Spock | 🟩 GREEN |
| `case-tool-gateway.md` | DESIGN.md + alice-tool-gateway DESIGN | `scene-tool-gateway-endpoints.md` | alice-tool-gateway | hole_test + Spock | 🟩 GREEN |
| `case-memory-vault.md` | DESIGN.md + alice-memory-vault DESIGN | `scene-memory-endpoints.md` | alice-memory-vault | hole_test + Spock | 🟩 GREEN |
| `case-model.md` | DESIGN.md + alice-model DESIGN | `scene-model-endpoints.md` | alice-model | hole_test + Spock | 🟩 GREEN |
| `case-guardrail.md` | DESIGN.md + alice-guardrail DESIGN | `scene-guardrail-endpoints.md` | alice-guardrail | hole_test + Spock | 🟩 GREEN |
| `case-web.md` | alice-facade-web (HealthController) | `scene-web-endpoints.md` | alice-facade-web | hole_test HTTP | ⏭️ SKIP |
| `case-bootstrap.md` | alice-bootstrap DESIGN | `scene-bootstrap-endpoints.md` | alice-bootstrap | hole_test + Spock | 🟩 GREEN |

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

### 3.8 alice-facade-web (0 个)

| 测试文件 | 覆盖范围 |
|---------|---------|
| (暂无) | — |

### 3.9 alice-memory-vault (23 个)

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

### 3.10 alice-model (7 个)

| 测试文件 | 覆盖范围 |
|---------|---------|
| `ModelSpec.groovy` | Model 接口/字段 |
| `ModelProviderSpec.groovy` | ModelProvider 管理 |
| `ModelConfigLoaderSpec.groovy` | 模型配置加载 |
| `CallSpec.groovy` | Call 构造/状态 |
| `CallStatusSpec.groovy` | CallStatus 枚举 |
| `ModelEnumSpec.groovy` | ModelEnum 枚举 |
| `ClaudeSupplierSpec.groovy` | Claude 供应商适配 |

### 3.11 alice-tool-gateway (8 个)

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

### 3.12 alice-guardrail — 4 个 Spock 规格 (28 个测试方法)

| 测试文件 | 覆盖范围 |
|---------|---------|
| `GuardrailServiceSpec.groovy` | verifyPlan (合法/高风险/PreValidator/null) + verifyResult (合法/FAILURE/null/PostValidator链) |
| `PolicyEngineSpec.groovy` | JsonSchemaValidator (平衡/非平衡/未注册/类型检查/类型不匹配) + RegexSafetyFilter (放行/拦截/白名单/违规描述) |
| `HallucinationDetectorSpec.groovy` | 正常/空结果/错误模式/FAILURE状态/FINISH计划/TOOL_CALL空数据 |
| `PermissionSandboxValidatorSpec.groovy` | 安全路径/`/etc/`/`/proc/`/`rm -rf /`/自定义前缀 |

---

## 四、测试覆盖统计

| 层级 | 类目 | 已实现 | 未实现 | 合计 |
|------|------|--------|-------|------|
| 🔷 场景 | E2E 根场景 | 3 | 2 | 5 |
| 🟩 模块 | CLI 端点 | 25 case | — | 25 case |
| 🟩 模块 | TUI 端点 | 30 case (跳过) | — | 30 case |
| 🟩 模块 | alice-core-agent 洞 | 4 🟩 | — | 4 |
| 🟩 模块 | alice-core-planner 洞 | 4 🟩 | — | 4 |
| 🟩 模块 | alice-env-adapter 洞 | 4 🟩 | — | 4 |
| 🟩 模块 | alice-tool-gateway 洞 | 4 🟩 | — | 4 |
| 🟩 模块 | alice-memory-vault 洞 | 5 🟩 | — | 5 |
| 🟩 模块 | alice-model 洞 | 5 🟩 | — | 5 |
| 🟩 模块 | alice-guardrail 洞 | 5 🟩 | — | 5 |
| 🟩 模块 | alice-facade-web 洞 | — | 3 ⏭️ SKIP | 3 |
| 🟩 模块 | alice-bootstrap 洞 | 3 🟩 | — | 3 |
| ⬜ 单元 | alice-agent-command | 11 | — | 11 |
| ⬜ 单元 | alice-bootstrap | 2 | — | 2 |
| ⬜ 单元 | alice-core-agent | 9 | — | 9 |
| ⬜ 单元 | alice-core-planner | 1 | — | 1 |
| ⬜ 单元 | alice-env-adapter | 8 | — | 8 |
| ⬜ 单元 | alice-facade-cmd | 7 | — | 7 |
| ⬜ 单元 | alice-facade-tui | 1 | — | 1 |
| ⬜ 单元 | alice-facade-web | 0 | — | 0 |
| ⬜ 单元 | alice-memory-vault | 23 | — | 23 |
| ⬜ 单元 | alice-model | 7 | — | 7 |
| ⬜ 单元 | alice-tool-gateway | 8 | — | 8 |
| ⬜ 单元 | alice-guardrail | 4 (28 tests) | — | 4 |
| **合计** | | **~195+** | **5** | **~200** |

---

## 五、挖洞设计原则

### 5.1 什么是"挖洞"（Probing）？

挖洞测试的核心思想：**不深入模块内部，只在模块的公共边界上打几个洞**，验证数据能进能出、接口不崩溃。

```ascii
         ┌────────────────────┐
         │      Module        │
         │                    │
  Input ──►  ●          ●  ──► Output
         │   (probe 1)  (probe 2) │
         │                    │
         │    ●          ●    │
         │  (probe 3)  (probe 4)│
         └────────────────────┘
```

### 5.2 挖洞层级判断标准

| 条件 | 应作挖洞测试 | 应作单元测试 |
|------|-------------|-------------|
| 验证模块间交互 | ✅ | ❌ |
| 验证接口协议一致 | ✅ | ❌ |
| 验证数据格式转换 | ✅ | ❌ |
| 验证内部算法逻辑 | ❌ | ✅ |
| 验证密封层次完整性 | ❌ | ✅ |
| 验证字段/构造/equals/toString | ❌ | ✅ |

### 5.3 挖洞实现方式

- **核心模块**：用 **hole_test + Spock** 实现，Python 驱动 Gradle 子任务验证
- **Web 模块**：用 `requests`/`urllib` 做 HTTP E2E
- **前端 Facade 模块**：延续现有 Python E2E 风格

### 5.4 hole-tdd 工作流

每个洞遵循 Red → Green → Refactor：

```ascii
  1. 写 case doc（定义洞规格）
  2. 写 hole_test.py → 🟥 assertTrue(False) → Run → RED
  3. 替换真实断言 → 🟩 run_gradle_task(...).returncode == 0 → Run → GREEN
  4. 写 scene doc（记录探针地图）
```

---

## 六、Case Doc 索引

| Case Doc | 来源 | 描述 | 状态 |
|---------|------|------|------|
| `case-run.md` | DESIGN.md §2 ExecutionCmd | CLI `run` 子命令 | ✅ |
| `case-chat.md` | DESIGN.md §2 ControlCmd | JLine chat 交互 | ⏭️ |
| `case-config.md` | DESIGN.md §2 系统配置 | CLI `config` 子命令 | ✅ |
| `case-tools.md` | DESIGN.md §2 CapabilityCmd | CLI `tools` 子命令 | ✅ |
| `case-routine.md` | DESIGN.md §2 RoutineTimeCmd | CLI `routine` 子命令 | ✅ |
| `case-sub-agent.md` | DESIGN.md §2 SubAgentCmd | CLI `sub-agent` 子命令 | ✅ |
| `case-dispatch-full-coverage.md` | DESIGN.md §2 全部 21 种 | dispatchCommand() 全覆盖 | ✅ / ⏭️ |
| `case-tui-slash-commands.md` | DESIGN.md §2 20 种 TUI 映射 | AgentCommand.parse() | ⏭️ |
| `case-core-agent.md` | DESIGN.md + alice-core-agent DESIGN | AgentExecutor 端点 | 🟩 GREEN |
| `case-core-planner.md` | DESIGN.md + alice-core-planner DESIGN | PlannerService 端点 | 🟩 GREEN |
| `case-env-adapter.md` | DESIGN.md + alice-env-adapter DESIGN | EnvManager / MCP 端点 | 🟩 GREEN |
| `case-tool-gateway.md` | DESIGN.md + alice-tool-gateway DESIGN | ToolRegistry / ExecutionEngine 端点 | 🟩 GREEN |
| `case-memory-vault.md` | DESIGN.md + alice-memory-vault DESIGN | VaultController 存取端点 | 🟩 GREEN |
| `case-model.md` | DESIGN.md + alice-model DESIGN | ModelProvider 调度端点 | 🟩 GREEN |
| `case-guardrail.md` | DESIGN.md + alice-guardrail DESIGN | GuardrailService 验证端点 | 🟩 GREEN |
| `case-web.md` | alice-facade-web (HealthController) | HTTP 健康检查端点 | ⏭️ SKIP |
| `case-bootstrap.md` | alice-bootstrap DESIGN | FacadeSelector 路由端点 | 🟩 GREEN |

---

## 七、质量门禁

- **`./gradlew check`** — 所有单元测试通过
- **`python e2e/run_alice_e2e.py`** — 所有场景 E2E 通过
- **`./gradlew spotlessCheck`** — 代码格式合规
- 新建功能必须同步添加对应层级的测试（优先单元，再模块，最后场景）
- 挖洞测试新增时，同步更新 `tests/TEST.md` 的统计表
