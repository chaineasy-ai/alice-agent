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

### 2.2 核心模块端点（挖洞 — 缺失待补）

以下 8 个模块的**端点测试**目前缺失，每个模块的端点按 **"挖洞"（Probing）** 方式设计：不深入内部实现细节，仅验证模块边界的 3~5 个关键接口输入输出是否正确。每个挖洞点对应一个 scene doc 和一个 case doc。

---

### 2.2.1 alice-core-agent — 核心代理生命周期

**DESIGN.md 核心接口**：
- `AgentExecutor.execute(Input) → StepResult`
- `Lifecycle.onPerceive/onPlan/onAct/onVerify → Context/Plan/Observation/Boolean`
- `StepResult` — sealed (Continue/Finish/Failure)

| 洞号 | 端点 | 挖洞方法 | 验证内容 |
|------|------|---------|---------|
| AGT-P01 | `AgentExecutor.execute()` 正常流 | 构造 Mock Input，验证返回 `Finish` 或 `Failure` | 执行入口不崩溃，返回 sealed 结果 |
| AGT-P02 | `StepResult` 模式匹配 | 分别构造 Continue/Finish/Failure，验证 switch 分支可达 | sealed 层次完整性 |
| AGT-P03 | `AgentContext` 会话状态管理 | 创建/读取/清除 session，验证状态转移 | session 生命周期正确 |
| AGT-P04 | `AgentConfig` 配置加载 | 从内存/文件加载配置，验证字段映射 | 配置反序列化正确 |
| AGT-P05 | `SubAgentManager` 注册/查找 | 注册 SubAgent，查找/列出/注销 | 子代理注册表增删查 |

**文件规划**：
- Case doc: `docs/alice-agent-command/e2e/case-core-agent.md`
- Scene doc: `docs/alice-core-agent/e2e/scene-executor-endpoints.md`
- 实现: `docs/alice-core-agent/e2e/test_core_agent_endpoints.py` 或 Groovy 直接通过 `buildSrc`/Gradle 子任务执行
- 状态: 🔜 待实现

---

### 2.2.2 alice-core-planner — 规划引擎

**DESIGN.md 核心接口**：
- `PlannerService.plan(AgentContext) → Plan`
- `DecisionStrategy.decide(AgentContext) → Plan` (FastPathStrategy / SlowPathStrategy)
- `WorldModel.predict(State, Action) → Observation`

| 洞号 | 端点 | 挖洞方法 | 验证内容 |
|------|------|---------|---------|
| PLN-P01 | `PlannerService.plan()` 调用 | 传入模拟 ctx，验证返回 Plan 非 null | 规划入口可用 |
| PLN-P02 | `FastPathStrategy.decide()` 快速路径 | 简单上下文触发快速路径验证 | 路径选择逻辑 |
| PLN-P03 | `SlowPathStrategy.decide()` 慢速路径 | 复杂/不确定上下文触发慢速路径 | 慢路径回退 |
| PLN-P04 | `WorldModel.predict()` 世界模型预测 | 给定 state+action，验证 Observation 响应 | 预测接口一致 |

**文件规划**：
- Case doc: `docs/alice-agent-command/e2e/case-core-planner.md`
- Scene doc: `docs/alice-core-planner/e2e/scene-planner-endpoints.md`
- 状态: 🔜 待实现

---

### 2.2.3 alice-env-adapter — 环境适配器

**DESIGN.md 核心接口**：
- `EnvManager.execute(Action) → Observation`
- `McpClient.listTools() → List<Tool>`
- `McpClient.callTool(String, Map) → Result`
- `SnapshotManager.save(state)` / `rollback() → EnvState`

| 洞号 | 端点 | 挖洞方法 | 验证内容 |
|------|------|---------|---------|
| ENV-P01 | `EnvManager.execute()` 动作执行 | 模拟 Action，验证返回 Observation | 执行入口完整 |
| ENV-P02 | `McpClient.callTool()` MCP 工具调用 | 用 `FakeMcpTransport` 模拟，验证结果格式 | MCP 协议适配 |
| ENV-P03 | `McpClient.listTools()` MCP 工具列表 | 验证返回工具名/描述列表 | 工具发现 |
| ENV-P04 | `SnapshotManager.save()` + `rollback()` | 快照保存后回滚，验证状态恢复 | 快照回滚 |

**文件规划**：
- Case doc: `docs/alice-agent-command/e2e/case-env-adapter.md`
- Scene doc: `docs/alice-env-adapter/e2e/scene-env-endpoints.md`
- 状态: 🔜 待实现

---

### 2.2.4 alice-tool-gateway — 工具网关

**DESIGN.md 核心接口**：
- `ToolRegistry.register()` / `lookup(String) → ToolMetadata`
- `ToolDiscovery.scanAndRegister()`
- `ExecutionEngine.invoke(Action) → Observation`
- `SandboxProvider.executeInIsolation(Callable) → Result`

| 洞号 | 端点 | 挖洞方法 | 验证内容 |
|------|------|---------|---------|
| TGW-P01 | `ToolRegistry.register()` + `lookup()` | 注册工具后按名查找，验证元数据准确 | 注册/查找基础操作 |
| TGW-P02 | `ToolDiscovery.scanAndRegister()` | 扫描带注解的 bean，验证自动注册 | 自动发现机制 |
| TGW-P03 | `ExecutionEngine.invoke()` 工具调用 | 模拟 Action 调用注册工具，验证 Observation | 执行引擎链路 |
| TGW-P04 | `SandboxProvider.executeInIsolation()` | 提交简单任务（如 `λ → 42`），验证结果可用 | 沙箱隔离 |

**文件规划**：
- Case doc: `docs/alice-agent-command/e2e/case-tool-gateway.md`
- Scene doc: `docs/alice-tool-gateway/e2e/scene-tool-gateway-endpoints.md`
- 状态: 🔜 待实现

---

### 2.2.5 alice-memory-vault — 记忆库

**DESIGN.md 核心接口**：
- `VaultController.recall(Context) → MemorySet`
- `VaultController.memorize(Experience)`
- `EpisodicVault.getRecentTrace(sessionId)` / `summarize(sessionId)`
- `SemanticVault.search(String) → List<Knowledge>`
- `ProceduralVault.matchPattern(Context) → List<SOP>`

| 洞号 | 端点 | 挖洞方法 | 验证内容 |
|------|------|---------|---------|
| MEM-P01 | `VaultController.memorize()` + `recall()` | 存入一条经验，再检索验证能取回 | 基本存取闭环 |
| MEM-P02 | `EpisodicVault.getRecentTrace()` | 模拟多步日志后，验证最近 N 步正确 | 事件序列索引 |
| MEM-P03 | `SemanticVault.search()` 语义检索 | 插入带 embedding 的知识，搜索验证相关性 | 向量检索 |
| MEM-P04 | `ProceduralVault.matchPattern()` | 注册 SOP 模式后，匹配相似上下文 | SOP 匹配 |
| MEM-P05 | `WalStore` 持久化 + 崩溃恢复 | 写入 WAL → 模拟崩溃 → 恢复验证数据完整 | 持久化可靠性 |

**文件规划**：
- Case doc: `docs/alice-agent-command/e2e/case-memory-vault.md`
- Scene doc: `docs/alice-memory-vault/e2e/scene-memory-endpoints.md`
- 状态: 🔜 待实现

---

### 2.2.6 alice-model — 模型抽象层

**DESIGN.md 核心接口**：
- `ModelProvider.dispatch(Request) → Call`
- `ModelSupplier.chat(Request) → Response` (OpenAiSupplier / ClaudeSupplier / Gemma4Supplier)
- `Call.execute() → Response`
- `ModelConfigLoader.loadConfig() → ModelConfig`

| 洞号 | 端点 | 挖洞方法 | 验证内容 |
|------|------|---------|---------|
| MDL-P01 | `ModelProvider.dispatch()` + `registerSupplier()` | 注册 FakeSupplier，请求分发验证命中 | 供应商调度 |
| MDL-P02 | `Call.execute()` 调用生命周期 | 构造完整 Call，验证状态流转 (NEW→RUNNING→DONE/FAILED) | Call 生命周期 |
| MDL-P03 | `ModelSupplier.chat()` 统一输入/输出 | Mock HTTP 响应，验证 supplier 解析响应格式 | 供应商适配器 |
| MDL-P04 | `ModelConfigLoader` 加载配置 | 从 JSON/YAML 加载，验证模型定义字段映射 | 配置反序列化 |
| MDL-P05 | 多供应商切换 | 注册 2 个 supplier，按 modelId 路由到不同 supplier | 路由策略 |

**文件规划**：
- Case doc: `docs/alice-agent-command/e2e/case-model.md`
- Scene doc: `docs/alice-model/e2e/scene-model-endpoints.md`
- 状态: 🔜 待实现

---

### 2.2.7 alice-guardrail — 护栏/验证

**DESIGN.md 核心接口**：
- `GuardrailService.verifyPlan(Plan) → AuditResult`
- `GuardrailService.verifyResult(Observation, Plan) → AuditResult`
- `PolicyEngine.evaluate(Object) → AuditResult`
- `PreValidator.check(Plan) → AuditResult`
- `PostValidator.check(Observation, Plan) → AuditResult`

| 洞号 | 端点 | 挖洞方法 | 验证内容 |
|------|------|---------|---------|
| GRD-P01 | `GuardrailService.verifyPlan()` 计划前置验证 | 传入合法/非法 Plan，验证 AuditResult.passed 正确 | 前置验证链 |
| GRD-P02 | `GuardrailService.verifyResult()` 结果后置验证 | 传入合法/非法 Observation，验证审计结果 | 后置验证链 |
| GRD-P03 | `PolicyEngine.evaluate()` 策略引擎 | 定义简单策略，验证评估通过/不通过 | 策略执行 |
| GRD-P04 | `HallucinationDetector` 幻觉检测 | 传入含矛盾/正常 Observation，验证检测结果 | 幻觉拦截 |
| GRD-P05 | `PermissionSandboxValidator` 权限沙箱 | 传入越界/合法 Action，验证拦截/放行 | 权限控制 |

**文件规划**：
- Case doc: `docs/alice-agent-command/e2e/case-guardrail.md`
- Scene doc: `docs/alice-guardrail/e2e/scene-guardrail-endpoints.md`
- 状态: 🔜 待实现

---

### 2.2.8 alice-facade-web — Web 端点

**现有源文件**：仅 `HealthController.java` + `module-info.java`

| 洞号 | 端点 | 挖洞方法 | 验证内容 |
|------|------|---------|---------|
| WEB-P01 | `GET /health` 健康检查 | 启动嵌入式 HTTP 服务器，调用 GET /health，验证返回 200 | HTTP 端点可达 |
| WEB-P02 | HTTP 404 处理 | 访问不存在路径，验证返回 404 | 路由兜底 |
| WEB-P03 | CORS 头（若有） | 发送 OPTIONS 请求，验证跨域头 | CORS 策略 |

**文件规划**：
- Case doc: `docs/alice-agent-command/e2e/case-web.md`
- Scene doc: `docs/alice-facade-web/e2e/scene-web-endpoints.md`
- 状态: 🔜 待实现

---

### 2.2.9 alice-bootstrap — 引导模块

**DESIGN.md 核心接口**：
- `AliceApp.main(String[])` — JVM 入口
- `AppBootstrapper.bootstrap()` — 启动路由
- `FacadeSelector.select(String[]) → IFacadeLauncher`

| 洞号 | 端点 | 挖洞方法 | 验证内容 |
|------|------|---------|---------|
| BTS-P01 | `FacadeSelector.select()` 外壳选择 | 传入 `--tui` / 无参数，验证返回对应 Launcher | 路由决策 |
| BTS-P02 | `AppBootstrapper.bootstrap()` 启动流 | 验证 bootstrap 流程不抛异常，传递原始 args | 启动链完整 |
| BTS-P03 | `IFacadeLauncher.launch()` 接口一致 | 验证 CLI/TUI 均实现同接口，签名匹配 | 接口协议 |

**文件规划**：
- Case doc: `docs/alice-agent-command/e2e/case-bootstrap.md`
- Scene doc: `docs/alice-bootstrap/e2e/scene-bootstrap-endpoints.md`
- 状态: 🔜 待实现

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
| `case-core-agent.md` | DESIGN.md §2 + alice-core-agent DESIGN | `scene-executor-endpoints.md` | alice-core-agent | Groovy/Gradle | 🔜 |
| `case-core-planner.md` | DESIGN.md §2 + alice-core-planner DESIGN | `scene-planner-endpoints.md` | alice-core-planner | Groovy/Gradle | 🔜 |
| `case-env-adapter.md` | DESIGN.md §2 + alice-env-adapter DESIGN | `scene-env-endpoints.md` | alice-env-adapter | Groovy/Gradle | 🔜 |
| `case-tool-gateway.md` | DESIGN.md §2 + alice-tool-gateway DESIGN | `scene-tool-gateway-endpoints.md` | alice-tool-gateway | Groovy/Gradle | 🔜 |
| `case-memory-vault.md` | DESIGN.md §2 + alice-memory-vault DESIGN | `scene-memory-endpoints.md` | alice-memory-vault | Groovy/Gradle | 🔜 |
| `case-model.md` | DESIGN.md §2 + alice-model DESIGN | `scene-model-endpoints.md` | alice-model | Groovy/Gradle | 🔜 |
| `case-guardrail.md` | DESIGN.md §2 + alice-guardrail DESIGN | `scene-guardrail-endpoints.md` | alice-guardrail | Groovy/Gradle | 🔜 |
| `case-web.md` | alice-facade-web DESIGN | `scene-web-endpoints.md` | alice-facade-web | HTTP E2E | 🔜 |
| `case-bootstrap.md` | alice-bootstrap DESIGN | `scene-bootstrap-endpoints.md` | alice-bootstrap | Groovy/Gradle | 🔜 |

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

### 3.8 alice-facade-web (0 个 — 待补)

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

### 3.12 alice-guardrail (0 个 — 待补)

| 测试文件 | 覆盖范围 |
|---------|---------|
| (暂无) | — |

---

## 四、测试覆盖统计

| 层级 | 类目 | 已实现 | 挖洞待补 | 合计 |
|------|------|--------|---------|------|
| 🔷 场景 | E2E 根场景 | 3 | 2 | 5 |
| 🟩 模块 | CLI 端点 | 25 case | — | 25 case |
| 🟩 模块 | TUI 端点 | 30 case (跳过) | — | 30 case |
| 🟩 模块 | alice-core-agent 端点 | — | 5 probe | 5 |
| 🟩 模块 | alice-core-planner 端点 | — | 4 probe | 4 |
| 🟩 模块 | alice-env-adapter 端点 | — | 4 probe | 4 |
| 🟩 模块 | alice-tool-gateway 端点 | — | 4 probe | 4 |
| 🟩 模块 | alice-memory-vault 端点 | — | 5 probe | 5 |
| 🟩 模块 | alice-model 端点 | — | 5 probe | 5 |
| 🟩 模块 | alice-guardrail 端点 | — | 5 probe | 5 |
| 🟩 模块 | alice-facade-web 端点 | — | 3 probe | 3 |
| 🟩 模块 | alice-bootstrap 端点 | — | 3 probe | 3 |
| ⬜ 单元 | alice-agent-command | 11 | — | 11 |
| ⬜ 单元 | alice-bootstrap | 2 | — | 2 |
| ⬜ 单元 | alice-core-agent | 9 | — | 9 |
| ⬜ 单元 | alice-core-planner | 1 | — | 1 |
| ⬜ 单元 | alice-env-adapter | 8 | — | 8 |
| ⬜ 单元 | alice-facade-cmd | 7 | — | 7 |
| ⬜ 单元 | alice-facade-tui | 1 | — | 1 |
| ⬜ 单元 | alice-facade-web | — | 待补 | 0 |
| ⬜ 单元 | alice-memory-vault | 23 | — | 23 |
| ⬜ 单元 | alice-model | 7 | — | 7 |
| ⬜ 单元 | alice-tool-gateway | 8 | — | 8 |
| ⬜ 单元 | alice-guardrail | — | 待补 | 0 |
| **合计** | | **135+** | **40 probe + 2 unit** | **≈ 177** |

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

- **核心模块**（alice-core-agent / alice-core-planner 等）：用 **Groovy + Gradle 子任务** 实现，直接调用模块的 public API，不需要 HTTP 层
- **Web 模块**（alice-facade-web）：用 `requests` 或 `curl` 做 HTTP E2E
- **前端 Facade 模块**（alice-facade-cmd / alice-facade-tui）：延续现有 Python E2E 风格

---

## 六、Case Doc 索引

所有模块层测试 case 来源于 `docs/alice-agent-command/DESIGN.md` 中定义的密封指令层次 + 各模块 DESIGN.md 中的核心接口定义。每个 case doc 对应一个模块 E2E 场景：

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
| `case-core-agent.md` | DESIGN.md + alice-core-agent DESIGN | AgentExecutor 端点 | 🔜 |
| `case-core-planner.md` | DESIGN.md + alice-core-planner DESIGN | PlannerService 端点 | 🔜 |
| `case-env-adapter.md` | DESIGN.md + alice-env-adapter DESIGN | EnvManager / MCP 端点 | 🔜 |
| `case-tool-gateway.md` | DESIGN.md + alice-tool-gateway DESIGN | ToolRegistry / ExecutionEngine 端点 | 🔜 |
| `case-memory-vault.md` | DESIGN.md + alice-memory-vault DESIGN | VaultController 存取端点 | 🔜 |
| `case-model.md` | DESIGN.md + alice-model DESIGN | ModelProvider 调度端点 | 🔜 |
| `case-guardrail.md` | DESIGN.md + alice-guardrail DESIGN | GuardrailService 验证端点 | 🔜 |
| `case-web.md` | alice-facade-web (HealthController) | HTTP 健康检查端点 | 🔜 |
| `case-bootstrap.md` | alice-bootstrap DESIGN | FacadeSelector 路由端点 | 🔜 |

---

## 七、质量门禁

- **`./gradlew check`** — 所有单元测试通过
- **`python e2e/run_alice_e2e.py`** — 所有场景 E2E 通过
- **`./gradlew spotlessCheck`** — 代码格式合规
- 新建功能必须同步添加对应层级的测试（优先单元，再模块，最后场景）
- 挖洞测试新增时，同步更新 `tests/TEST.md` 的统计表
