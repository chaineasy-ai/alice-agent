---
description: record your changes
---

# Changelog

## Unreleased

### Changes

- alice-facade-tui/TUI 外观模块：基于设计文档(`docs/alice-facade-tui/DESIGN.md`) 实现完整终端用户界面模块，提供富交互、可视化的 Agent 任务监控面板。
- alice-facade-tui/AliceTuiLauncher：新增主入口启动器，初始化 Agent → EventBridge → ScreenManager 链路，进入主事件循环。
- alice-facade-tui/ScreenManager：新增屏幕管理器（对应 §2 ScreenManager），管理 Lanterna 终端渲染循环、键盘输入处理、组件生命周期、终端 resize 响应。
- alice-facade-tui/EventBridge + TuiEvent：新增事件桥接系统（对应 §3/§5），密封类事件体系含 StartThinking/NewThought/ActionExecuting/ChatMessage/ObservationResult/TaskComplete/TaskError/TokenUpdate 等，支持异步多监听器分发。
- alice-facade-tui/TuiState：新增界面状态机（对应 §6），实现 IDLE→INPUTING→RUNNING→INTERVENE→ERROR 状态转换规则。
- alice-facade-tui/SlashCommand + CommandHandler：新增斜杠命令系统（对应 §7.3），支持 /new /clear /exit /help /prompt /history /exec /model /tools 九条命令，Type A/B/C/D 分类处理，/exec 通过 ProcessBuilder 执行 shell，/prompt 读取外部文件。
- alice-facade-tui/UI 组件：新增 7 个 Lanterna 组件（对应 §7.1 布局）— HeaderComponent（标题栏）、ChatComponent（左侧聊天历史）、ThoughtComponent（右侧思考流，彩色标识）、StatusComponent（Token/状态栏）、InputComponent（输入框含光标）、FooterComponent（快捷键提示）、Component（抽象基类）。
- alice-facade-tui/TuiLayout：新增布局管理器，响应终端 resize，按 §7.1 设计排布 Header/Chat+Thought/Status/Input/Footer 区域。
- alice-facade-tui/快捷键支持：实现 F1 帮助 / F5 停止 / F10 退出 / Tab 焦点切换 / PgUp/PgDn 翻页 / 方向键滚动/光标 / Enter 提交 / Ctrl+Q 退出（对应 §7.2 快捷键映射表）。
- alice-facade-tui/测试：新增 15 个 Spock 测试用例，覆盖 TuiState 状态转换、SlashCommand 解析、TuiEvent 构造、EventBridge 异步事件分发。
- alice-facade-tui/构建：添加 Lanterna 3.1.3 / JLine3 3.27.1 终端库依赖，更新 module-info.java 导出 6 个公开包，编译与测试全部通过 (15/15)。

- alice-memory-vault/三段式记忆系统：基于设计文档(`docs/alice-memory-vault/DESIGN.md`) 实现完整模块，将记忆从简单会话存储升级为人类记忆分类学驱动的分层检索与生命周期管理系统。
- alice-memory-vault/VaultController：新增统一入口，以组合模式管理 EpisodicVault / SemanticVault / ProceduralVault，通过 MemoryRouter 进行查询路由，支持 `recall(Context)` / `memorize(Experience)` / `finalizeSession(sessionId)` 核心 API。
- alice-memory-vault/MemoryRouter：新增记忆路由器，根据上下文关键词自动分流：Episodic（"刚才/之前/recent"）→ EpisodicVault、Semantic（"什么是/explain/concept"）→ SemanticVault、Procedural（"如何/how to/step"）→ ProceduralVault；无明确倾向时触发全检索融合。
- alice-memory-vault/EpisodicVault：实现情节记忆 vault，按 sessionId 存储交互 Trace，内置 LRU + 重要度评分双重遗忘策略；支持 `penalizeStep()` 纠错机制降低错误推理权重，防止干扰后续规划。
- alice-memory-vault/SemanticVault：实现语义记忆 vault，基于 Jaccard 相似度 + 长度归一化的文本检索，支持 Collection 隔离（project-alpha 的私有 API 文档不会在通用咨询中被误检索），配置化 topK 与相似度阈值。
- alice-memory-vault/ProceduralVault：实现程序记忆 vault，SOP 模式匹配引擎支持工具名精确匹配（0.9）、pattern 关键词重叠匹配（0.3~0.7）、名称匹配（0.5），版本控制支持 SOP 更新替换。
- alice-memory-vault/DefaultMemorySummarizer：实现记忆提炼器 Consolidation 核心，从成功步骤中提取 Facts（去重 + 低重要度过滤），从连续 3+ 成功步骤序列中提取 Success Patterns。
- alice-memory-vault/InMemoryStorageBackend：内存存储后端实现，提供生产就绪的 StorageBackend 接口（可替换为 Redis / PostgreSQL）。
- alice-memory-vault/Context：查询上下文模型，含 `isEpisodicQuery()` / `isSemanticQuery()` / `isProceduralQuery()` 快速路由判断，支持 sessionId 与 metadata 标签。
- alice-memory-vault/MemorySet：融合记忆结果集，采用 sealed interface + record 模式定义 EpisodicEntry / SemanticEntry / ProceduralEntry 三类条目。
- alice-memory-vault/数据模型：新增 Experience/Step/Summary/Knowledge/SOP 五个不可变值对象，全量使用 Builder 模式。
- alice-memory-vault/异步 Consolidation：VaultController.finalizeSession() 通过 CompletableFuture 异步执行 Trace → 提炼 Facts → 存入 SemanticVault + 提炼 Success Patterns → 存入 ProceduralVault。
- alice-memory-vault/测试：新增 85 个 Spock 测试用例，覆盖三段式记忆核心路径、遗忘策略边界、路由逻辑、Consolidation 全链路、纠错机制、Collection 隔离、null/空/边界场景。

- alice-facade-cmd/CLI 命令行外观模块：基于设计文档(`docs/alice-facade-cmd/DESIGN.md`) 实现完整模块，提供 Alice Agent 的命令行界面入口。
- alice-facade-cmd/AliceCliLauncher：主入口，解析参数 → 初始化 ModelProvider → 驱动 ExecutionCoordinator → 退出码映射（0/1/2/130），支持 JVM 关闭钩子。
- alice-facade-cmd/CommandParser：基于 picocli 4.7 的子命令解析器，支持 `alice run`（完整实现）、`alice chat/tools/config`（预留），抛出 `ParseException` 而非 `System.exit()`。
- alice-facade-cmd/RunConfig：封装 CLI 参数的不可变配置对象，含 task/model/jsonOutput/verbose/timeoutSeconds/envVars。
- alice-facade-cmd/ExecutionCoordinator：协调 Agent 核心执行，支持 stdin 管道输入读取、Agent 超时控制、StepResult 实时渲染。
- alice-facade-cmd/OutputRenderer：渲染器接口 + 工厂方法，支持文本增强（`TextOutputRenderer`，带 emoji 标记/时间戳）和 JSON Lines（`JsonOutputRenderer`，紧凑结构）双模式。
- alice-facade-cmd/构建：添加 picocli 4.7.6、vert.x 5.0.8 依赖，配置 `application` 插件，mainClass 为 `AliceCliLauncher`，应用名 `alice`。
- alice-facade-cmd/测试：新增 47 个 Spock 测试用例，覆盖 RunConfig/CommandParser/TextOutputRenderer/JsonOutputRenderer/AliceCliLauncher 全链路，包括 stdout/stderr 重定向验证、JSON 格式校验、异常退出码断言。
- alice-memory-vault/build：修复模块构建配置（jar archiveBaseName），添加 JUnit Platform Launcher 依赖。

- alice-guardrail/审校委员会：基于设计文档(`docs/alice-guardrail/DESIGN.md`) 实现完整模块，将安全与正确性从执行链路中剥离为独立验证层。
- alice-guardrail/GuardrailService：新增核心入口，采用拦截器链模式，支持动态注册 PreValidator / PostValidator，实现 Phase 1 (Pre-Exec) + Phase 2 (Post-Exec) 双重审计。
- alice-guardrail/AuditResult：新增密封审计结果类型，含 Status (ALLOW/REJECT/INVALID/MANUAL_CONFIRM)、RiskLevel、CorrectionSuggestion，支持 Human-in-the-loop 挂起。
- alice-guardrail/PreValidator + PostValidator：新增验证器接口，Pre 使用 `Plan` 类型，Post 使用 `Map<String,Object>` 规避循环依赖。
- alice-guardrail/PermissionSandboxValidator：实现权限沙箱，拦截系统路径（/etc/、/proc/）与危险命令（rm -rf、dd）访问。
- alice-guardrail/LogicSanityValidator：实现逻辑闭环检查，检测动作重复死循环（>3 次连续相同步骤），强制 FINISH/REVISION 终止保障。
- alice-guardrail/HallucinationDetector：实现幻觉检测，通过空结果模式、错误模式、类型一致性三方校验评估观测结果可信度。
- alice-guardrail/PolicyEngine：新增确定性策略引擎，含 JsonSchemaValidator（JSON 平衡检查 + 类型校验）与 RegexSafetyFilter（白名单/黑名单正则过滤）。
- alice-guardrail/高风险动作检测：内置 DROP/DELETE/EXEC/RM_RF 等高危模式，命中自动标记 MANUAL_CONFIRM 请求人工确认。
- alice-guardrail/模块解耦：PostValidator 使用 Map 接口与 alice-core-agent 解耦，避免循环依赖（agent → guardrail → agent）。

- alice-env-adapter/环境适配器：基于设计文档(`docs/alice-env-adapter/DESIGN.md`) 实现完整模块，将外部世界抽象为可观察、可操作且可回滚的状态机。
- alice-env-adapter/EnvManager：新增主入口协调器，管理 MCP 客户端连接、快照生命周期和状态转换（DISCONNECTED→INITIALIZING→READY→CAPTURING_SNAPSHOT→EXECUTING→AUDITING→COMMITTED/ROLLING_BACK）。
- alice-env-adapter/McpClient：完整 MCP 2.0 协议客户端，含 initialize 握手/能力发现(tools/list, resources/list)/工具调用(tools/call)/资源读取(resources/read)/订阅(resources/subscribe)。
- alice-env-adapter/McpTransport：传输层抽象接口，支持 Stdio（本地子进程 JSON-RPC 通信）和 SSE（远程 HTTP 流式通信）两种模式。
- alice-env-adapter/StdioMcpTransport：基于标准输入/输出的 MCP 子进程通信，支持异步请求/响应追踪与 stderr 日志捕获。
- alice-env-adapter/SseMcpTransport：基于 Server-Sent Events 的远程 MCP 服务器通信，支持 HTTP POST 发送请求、SSE 流式接收响应与通知。
- alice-env-adapter/EnvSnapshot：不可变环境状态快照，含资源版本追踪、工作目录状态、环境变量捕获及不可逆副作用记录（含补偿建议）。
- alice-env-adapter/SnapshotManager：快照历史管理，支持 save/rollback/commit/clear/diff，LIFO 回滚策略，超出容量自动驱逐最早快照。
- alice-env-adapter/EnvState：环境状态机枚举，定义 8 个状态（DISCONNECTED/INITIALIZING/READY/CAPTURING_SNAPSHOT/EXECUTING/AUDITING/COMMITTED/ROLLING_BACK），含合法转换与终端状态校验。
- alice-env-adapter/EnvEvent：增强为结构化环境事件，支持 CLIENT_CONNECTED/CLIENT_DISCONNECTED/RESOURCE_CHANGED/ACTION_EXECUTED/ACTION_FAILED/SNAPSHOT_CAPTURED/ROLLBACK_PERFORMED/STATE_COMMITTED 八类事件。
- alice-env-adapter/model：新增 Tool、ToolResult、Resource、ResourceResult 四个 MCP 协议数据模型，全量使用不可变 Builder 模式。
- alice-env-adapter/状态自动回滚：EnvManager 执行 Action 失败时自动触发 rollback，前摄捕获 snapshot 确保可恢复性。
- alice-env-adapter/多租户隔离：EnvManager 内置 namespace 概念，防止不同任务间环境状态污染。
- alice-env-adapter/事件通知：EnvManager 维护 EnvEventListener 列表，MCP 资源变更通知自动广播给注册监听器。
- alice-env-adapter/模块系统：更新 module-info.java 导出所有子包（model/snapshot/state/transport），添加 Gson 和 Guava 模块依赖。

- alice-core-planner/双路径引擎：基于设计文档(`docs/alice-core-planner/DESIGN.md`) 实现完整模块，将 Planner 从提示词包装器升级为状态化推理机。
- alice-core-planner/PlannerService：新增主入口，执行三层路由：StaticPlanner(SOP) → StrategySelector(复杂度评估) → FastPath/SlowPath。
- alice-core-planner/Plan：新增密封结果类型，含 Type(FAST_PATH/SLOW_PATH/STATIC) 与 Step 子类型，支持多步骤规划输出。
- alice-core-planner/StrategySelector：实现复杂度评估路由，使用 prompt 长度+关键词启发式（可注入自定义函数替代 Router 模型）。
- alice-core-planner/FastPathStrategy：System 1 快速路径，通过轻量指令模型直接生成 LLM 推理规划。
- alice-core-planner/SlowPathStrategy：System 2 慢速路径，基于 MCTS 思维树执行 Selection→Expansion→Simulation→Backpropagation 迭代搜索。
- alice-core-planner/ThinkingTree：维护 MCTS 树结构，利用 ForkJoinPool 并行评估多个推理分支，支持序列化到 memory-vault。
- alice-core-planner/ThinkingNode：节点含 State/Action/Value/reward/visits 及 UCT 计算，支持 MCTS 回溯更新。
- alice-core-planner/TokenBudget：新增熔断机制，可配置最大 Token 消耗与搜索深度，超限自动回退最优分支。
- alice-core-planner/ModelSupplier：新增 LLM-agnostic 抽象层，Planner 内部不直接持有模型客户端。
- alice-core-planner/SopRegistry：新增标准操作流程模板注册表，关键词索引匹配，可扩展为向量检索。
- alice-core-planner/StaticPlanner：SOP 模板直接解析为 Action 序列，完全确定性规划，跳过模型生成。
- alice-core-planner/ReAct：保留向后兼容的 `proposeNext(Map)` API，内部委托给 PlannerService 双路径引擎。
- alice-core-planner/module-info：更新导出所有子包（model/strategy/tree/sop/budget）。
- alice-core-planner/测试：新增 24 个 Spock 测试用例，覆盖 Plan/ThinkingNode/ThinkingTree/StrategySelector/TokenBudget/SopRegistry/StaticPlanner/ReAct 全链路。***(#6)

- alice-model/领域模型：新增 `Call`、`CallStatus`、`Model`、`ModelSupplier` 核心领域对象，对齐设计文档(`DESIGN.md`) 架构。(#1)
- alice-model/枚举：新增 14 个内置模型枚举定义（GPT-4o、Claude、Gemini、DeepSeek、Qwen 系列），含能力标签与成本定价。
- alice-model/供应商：新增 `OpenAiSupplier` 实现 `ModelSupplier` 接口，支持 OpenAI Chat Completion API 调用。
- alice-model/路由：`ModelProvider` 实现完整调度链路：Supplier 注册、Model 注册、路由策略、`dispatch()` 执行。
- alice-core-agent/Agent：实现 `ask(prompt)` / `ask(prompt, modelId)` 方法，集成 `ModelProvider` 调用链路。
- build/配置：在 `alice-core-agent` 和 `app` 模块中添加 `alice-model` 子模块依赖。
- build/测试：修复 JUnit Platform Launcher 缺失问题，确保 Spock 测试正常执行。
- docs: 新增 `docs/alice-model/README.md`，更新模块文档与架构描述。
- docs: 更新 README.md，用 `project.tree` 替代内联项目结构；新增 `project.tree` 文件。
- alice-core-agent/PPAO 闭环：实现 `AgentCore`、`AgentExecutor`、`AgentConfig`，基于 Vert.x 响应式 Future 链驱动循环：Perceive → Plan → Verify(Pre) → Act → Observe → Verify(Post) → Reflect → (loop|finish)。
- alice-core-agent/阶段状态机：`AgentContext` 新增 `Phase` 枚举（START→PERCEIVING→PLANNING→VERIFYING_PRE→ACTING→OBSERVING→VERIFYING_POST→REFLECTING→REVISION→FINISH），含合法转换校验。
- alice-core-agent/密封结果类型：新增 `StepResult` 密封类，模式匹配 `Continue(Action)` / `Finish(answer)` / `Failure(error)`。
- alice-core-agent/动作模型：新增 `Action`（TOOL_CALL/LLM_INFERENCE/FINISH/REVISION/WAIT/OBSERVE）与 `Observation`（SUCCESS/FAILURE/PARTIAL/TIMEOUT/BLOCKED）。
- alice-core-agent/生命周期接口：新增 `Lifecycle<I>` 接口定义 PPAO 各阶段契约。
- alice-core-planner/ReAct：新增 `proposeNext(Map)` 方法，通过 Map 接口与 core-agent 解耦。
- alice-guardrail/Verificator：新增 `intercept(Map)` / `audit(Object)` 默认方法，通过 Map/Object 与 core-agent 解耦。
- alice-tool-gateway/工具网关模块：基于设计文档(`docs/alice-tool-gateway/DESIGN.md`) 完整实现，将工具执行从核心 Agent 剥离为独立沙箱化执行层，打破与 `alice-core-agent` 的循环依赖。
- alice-tool-gateway/ToolRegistry：重写为 `Map<String, ToolMetadata>` 结构，新增 `lookup()` / `allTools()` / `toFunctionCallingSchema()` 方法；保留 `execute(String, Map)` 为 `@Deprecated` 向后兼容包装。
- alice-tool-gateway/ToolMetadata：新增不可变原数据，使用 Builder 模式构建，含 `invoke(Map)` 方法通过 MethodHandle 动态调用，支持方法参数名映射。
- alice-tool-gateway/SchemaGenerator：新增 JSON Schema 生成器，支持 String/int/long/float/double/boolean/enum/Collection/Map/Optional 类型映射。
- alice-tool-gateway/ExecutionEngine：新增核心调度器，支持超时控制（`Future.get()`）与多级沙箱选择（按 RiskLevel 匹配 SandboxProvider）。
- alice-tool-gateway/ToolResult：新增轻量结果类型（Status: SUCCESS/FAILURE/TIMEOUT），与 `Observation` 解耦，不依赖 `alice-core-agent`。
- alice-tool-gateway/SandboxProvider：新增泛型沙箱接口，含 Level 1（DirectSandboxProvider：当前线程直执行）与 Level 2（PolicySandboxProvider：SecurityManager 策略沙箱）。
- alice-tool-gateway/ToolDiscovery：新增 Bean 扫描器，通过 `List<Object>` 扫描 `@AgentTool` 注解方法，自动生成元数据并注册。
- alice-tool-gateway/注解：新增 `@AgentTool`（name/description/risk）、`@ToolParam`（value/description/required）、`RiskLevel` 枚举（LOW/MEDIUM/HIGH）。
- alice-tool-gateway/module-info：更新为仅依赖 `jackson-databind` 和 `guava`，添加 `opens` 指令支持测试反射。
- alice-tool-gateway/测试：新增 67 个 Spock 测试用例，覆盖 ToolRegistry/ToolMetadata/Annotation/SchemaGenerator/SandboxProvider/ToolResult/ToolDiscovery/ExecutionEngine 全链路。(#7)
- alice-tool-gateway/构建：添加 Jackson 2.18.3 依赖（databind/module-jsonSchema/datatype-jsr310），移除 `alice-core-agent` 子模块依赖以打破循环依赖。
- alice-memory-vault/AgentSession：新增 `persist()` / `getShortTerm()` / `putLongTerm()` 等记忆存取方法。
- alice-core-agent/测试：新增 5 个 Spock 测试（AgentContextSpec / ActionSpec / StepResultSpec / ObservationSpec / AgentConfigSpec），共 17 个测试用例全部通过。
- build/依赖：所有子模块构建与编译通过，消除模块间的循环依赖（core-planner / guardrail 通过 Map 接口与 core-agent 交互）。

### Fixes

- alice-model/模块系统：修复 `module-info.java` 中 facade 导出包为空的问题，移除冗余 facade 层。

## 20260503

### Changes

- 项目初始化：创建多模块 Java 25 + Gradle 9.5 项目骨架，含 8 个子模块。
