---
title: "Changelog"
summary: "Release history for Alice Agent"
read_when:
  - "checking release history and version changes"
  - "updating changelog for a new release"
scope:
  - "alice-bootstrap"
  - "alice-core-agent"
  - "alice-core-planner"
  - "alice-model"
  - "alice-env-adapter"
  - "alice-tool-gateway"
  - "alice-guardrail"
  - "alice-memory-vault"
  - "alice-agent-command"
  - "alice-facade-cmd"
  - "alice-facade-tui"
  - "alice-facade-web"
status: "active"
updated: "2026-07-03"
---

# Changelog

## Unreleased

### Features

- **PromptCmd 指令密封分支 (`alice-agent-command`)**: `CapabilityCmd` 新增 `LoadPromptCmd` 和 `ListPromptsCmd` 两个记录类型，作为 `/prompt` 命令的 AgentCommand 表示。`AgentCommand.parse()` 将 `/prompt:<name>` 映射为 `LoadPromptCmd`，将无参数 `/prompt` 映射为 `ListPromptsCmd`。冒号语法 (`/prompt:<name>`) 在 `AgentCommand.parse()` 和 `SlashCommand.parse()` 中统一实现。

- **PromptHelper 工具类 (`alice-facade-tui`)**: 新增 `PromptHelper` 工具类，提供 managed prompt 的解析 (`resolve`)、读取 (`readContent`)、拷贝 (`copyPromptFile`)、列表 (`listPrompts`)、扫描 (`scanPromptNames`) 静态方法。对应 `CapabilityCmd.LoadPromptCmd` 的实际文件系统操作，调用者需在拷贝后自行调用 `PromptManager.reloadFromDisk()`。

- **TUI 完整 `/prompt` 命令支持 (`alice-facade-tui`)**: `CommandHandler.handleIo()` 完成 `/prompt` 完整处理流程：`PromptHelper.resolve()` → `readContent()` → TUI 显示内容 → `copyPromptFile()` → `PromptManager.reloadFromDisk()` → 以 `UpdateRulesCmd` 派发给 Agent。`AliceTuiLauncher.dispatchAgentCommand()` 新增 `LoadPromptCmd` → `handleLoadPrompt()` 和 `ListPromptsCmd` → `handleListPrompts()` 两个分支，支持通过 `AgentCommand.parse()` 直接分发的 `/prompt` 请求。

- **TUI 动态 Tab 补全 (`alice-facade-tui`)**: `ScreenManager` 的 Tab 补全器支持 `/prompt:` 前缀动态扫描 `~/.alice/prompts/*.ftl`，输入 `/prompt:` 后按 Tab 自动列出匹配的 managed prompt 名称。

- **Managed Prompts 示例 (`docs/prompt/example`, `docs/rule/example`)**: 新增 4 个示例 prompt 文件 (`a.ftl`, `b.ftl`, `c.ftl`, `code-review.ftl`) 和 1 个示例 rule 文件 (`git-convention.md`)，用于演示 managed prompts 和 rules 系统。

- **文档更新 (`docs/alice-agent-command/DESIGN.md`, `docs/alice-facade-cmd/DESIGN.md`, `docs/alice-facade-tui/DESIGN.md`)**: 三份设计文档同步更新，涵盖 `LoadPromptCmd`/`ListPromptsCmd` 密封分支、`PromptHelper` API、冒号语法、Tab 补全、Managed Prompts 系统、AgentCommand 分发流程。

- **SOP 程序性记忆移至 alice-memory-vault (`alice-memory-vault`, `alice-core-planner`, `alice-facade-tui`)**: SOP (Standard Operating Procedure) 模块整体从 `alice-core-planner` 迁移至 `alice-memory-vault`，作为程序性记忆 (Procedural Memory) 的存储中心。
  - `SopGraph` / `SopGraphPersistence` / `SopRegistry` / `StaticPlanner` 移至 `org.cland.alice.memory.sop` 包
  - 新增 JGrapht 1.5.2 依赖（`jgrapht-core`/`jgrapht-io`/`jgrapht-ext`）—— 内存中 DAG 图结构 + GraphML 序列化
  - `SopGraph` 基于 `DefaultDirectedGraph<SopNode, SopEdge>`，支持条件分支 (`on-success`/`on-failure`/`condition:<expr>`)、并行任务、拓扑排序
  - `SopGraphPersistence` 支持 GraphML 文件的 save/load/toXml/fromXml，默认存储 `~/.alice/sops/<id>.graphml`
  - `SopRegistry` 同时支持平铺模板 (`SopTemplate`) 和 DAG (`SopGraph`) 两种注册方式，通过关键词匹配
  - `StaticPlanner` 将 SOP 模板解析为 `Plan` 步骤列表，自动追加 `FINISH`
  - `PlannerService` 改用 `staticPlannerFn(Function<Map, Plan>)` 函数注入替代硬编码 `StaticPlanner` 类型依赖，消除模块循环依赖
  - `Agent` 新增 `withStaticPlanner(Function)` 方法，支持运行时注入 SOP 规划逻辑
  - `AliceTuiLauncher` 启动时自动从 `~/.alice/sops/` 加载所有 `.graphml` 文件到 `SopRegistry`，通过 `agent.withStaticPlanner()` 接入 `PlannerService`
  - 新增 `docs/sop/example/weather-sop.graphml` 示例（6 节点天气查询 DAG，含并行天气/AQI 分支）
  - 新增 `docs/sop/example/README.md` 完整使用文档与 API 参考
  - 新增 `docs/sop/e2e/case.md` 端到端测试案例（8 阶段：构建 DAG → 拓扑排序 → GraphML 持久化 → 加载 → SofRegistry 匹配 → StaticPlanner → PlannerService 集成 → 存储管理）
  - `alice-facade-tui` 新增 `alice-memory-vault` + `alice-core-planner` 依赖，`module-info.java` 同步更新
  - `alice-core-planner` 移除 JGrapht 依赖和 `org.cland.alice.core.planner.sop` 包导出
  - 更新 `TECH_STACK.md`：JGrapht 移至 `alice-memory-vault` 条目，`alice-memory-vault` 新增 SOP 职责描述

- **Jackson 配置解析 + 双路径 Thinking 控制 (`alice-model`, `alice-core-agent`, `alice-core-planner`)**: 全面重构配置结构与 thinking 参数管线。
  - Config schema 变更: `default_model` 改为对象 `{provider, model, enable_thinking, reasoning_effort}`; 新增 `planner` 节（双路径模型 ID + per-path thinking 参数）; `providers.<name>.available_models[]` 替代顶层 `model_pool`
  - `ModelConfigLoader` 使用 Jackson ObjectMapper 重写，移除手写 JSON 解析器和旧格式兼容
  - FastPath（指令模型）自动注入 `enable_thinking=false, reasoning_effort=low` → 关闭 LLM 内部推理
  - SlowPath（推理模型）自动注入 `enable_thinking=true, reasoning_effort=high` → 深度推理
  - 三层参数转发修复: `ReAct.planToIntent()` → `AgentExecutor.mapToAction()` → `AgentExecutor.dispatchLlmInference()`，确保 thinking 参数从 plan step 直达 API 请求
  - `OpenAiSupplier`: DeepSeek `enable_thinking=false` → `thinking:{"type":"disabled"}`; OpenAI o 系列 `false` → `reasoning_effort="low"`

- **双路径模型分离 (`alice-model`, `alice-core-agent`)**: FastPath (System 1) 与 SlowPath (System 2) 现在使用独立模型配置，避免简单任务调用昂贵推理模型。
  - `~/.alice/model.json` 新增可选根字段 `instruction_model`，用于指定 FastPath 轻量指令模型
  - `ModelConfigLoader` 新增 `getInstructionModel()` 方法，未设置 `instruction_model` 时回退到 `default_model`
  - `Agent.createDefault()` 读取 `instruction_model` 供 `FastPathStrategy` 使用，`default_model` 供 `SlowPathStrategy` 使用
  - 配置文档 `docs/config/README.md` 新增双路径模型选择说明

- **Guardrail 验证链全线接线 (`alice-core-agent`, `alice-facade-tui`, `alice-facade-cmd`)**: 新增 `GuardrailVerificatorAdapter` 桥接 Agent 的 `Verificator` 接口与 `GuardrailService` 的 `PreValidator`/`PostValidator` 链，实现 PPAO Verify(Pre) 和 Verify(Post) 的真实验证逻辑。
  - 构造时自动注册 3 个内置验证器：`LogicSanityValidator` (死循环检测)、`PermissionSandboxValidator` (系统路径/命令黑名单)、`HallucinationDetector` (空结果/错误模式/类型一致性)
  - `intercept(Map)` → 转 `Plan` → `GuardrailService.verifyPlan()` → 状态机决策 (ALLOW/REJECT/MANUAL_CONFIRM)
  - `audit(Object)` → `StepResult` 转 `observationMap` → `GuardrailService.verifyResult()` → 后置审计
  - TUI (`AliceTuiLauncher`)、CLI (`ExecutionCoordinator`, `JLineChatSession`) 三处入口统一通过 `.withGuardrail(new GuardrailVerificatorAdapter())` 接入
  - 导出 `org.cland.alice.core.agent.guardrail` 包；更新 `alice-facade-tui`/`alice-facade-cmd` 的 `module-info.java` 及 `build.gradle` 依赖

- **TUI 动态增长布局 v5.0 (`alice-facade-tui`)**: 全面重构 TUI 布局序列和滚动机制。
  - **布局序列变更**: `Header → Main Area [0..N] → QueueMsg [0..1] → Line1 → Input → Line2 → Footer`
  - **Main Area 动态填满**: 高度 = `max(terminalHeight - 6, 1)`，始终填满 Header 与 Queue 之间的可用空间
  - **`TuiLayout.recalculate(w, h, contentLines)`**: 新增 `contentLines` 参数，内容变更后通过 `relayout()` 自动重算所有组件位置
  - **`afterContentAdded()`**: 每次内容追加后调用 `layout.relayout()` + 更新 JLine LINE_OFFSET
  - **交替屏幕缓冲**: 启动 `\033[?1049h` 进入交替缓冲，关闭 `\033[?1049l` 恢复主缓冲，防止终端滚动缓冲区捕获渲染历史
  - **翻页静默**: Page Up / Page Down / Alt+P / Alt+N 通过 JLine 4 Widget 系统绑定，调用 `MessageAreaComponent.pageUp()/pageDown()`。先 `unbind()` 移除 JLine 默认 history-search，再 `bind()` 到自定义 scroll widget
  - **Row 序列精简化**: Queue 从 Separator 下方移至 Separator 上方（Main → Queue → Line1 → Input → Line2 → Footer）
  - **`LineComponent`**: 移除 `suffix` 字段（Queue 不再合并到 Separator，使用独立行）
  - **文档更新**: `docs/alice-facade-tui/Layout.md`、`docs/alice-facade-tui/Layout_TAO.md` 全面更新为 v5.0

- **PlannerService 双路径规划引擎全线接线 (`alice-facade-tui`, `alice-core-planner`)**: PlannerService（FastPath/SlowPath/MCTS）从代码库中存在但运行时未连接的状态修复为完整工作链路。
  - `AliceTuiLauncher` 构造时装配 `DefaultPlannerModelSupplier` + `FastPathStrategy` + `SlowPathStrategy`(MCTS) + `StrategySelector` + `SopRegistry/StaticPlanner` → `PlannerService` → `agent.withPlannerService()`
  - 新增 `DefaultPlannerModelSupplier` 桥接 `ModelProvider` 到 Planner 双路径模型
  - `SlowPathStrategy` 硬编码 `"gpt-4o"` 修复为从 `modelSupplier.getReasoningModel().modelId()` 动态获取
  - 导出 `org.cland.alice.core.planner.model` 包使外部可访问 `PlannerModelSupplier`/`ModelSession`

- **Micro-ReAct Reason 阶段统一 (`alice-core-agent`)**: 移除原来 `plannerService != null` 分支中调用 `PlannerService.plan()` 作为微推理的错误逻辑。Micro-ReAct 的 Reason 阶段统一使用 tool_call 分发 + follow-up LLM 机制，不再依赖 PlannerService。PlannerService 仅用于 Macro Plan 阶段。
  - 修复 `AgentContext.asMap()` 返回 `Map.copyOf()`(不可变 Map) 导致 `UnsupportedOperationException` 的问题

- **PPAO Observe 多工具结果汇总 (`alice-core-agent`)**: Macro Observe 阶段现在从 `__action_log` 收集全部 N 个工具的执行结果，而不是仅取最后一个 step 的 Observation。
  - `[Observe] Collected N tool results (X chars)` 日志记录
  - `ctx.__system_event = "[System] N tool calls executed during this iteration"` — LLM 上下文可见系统事件
  - `fireOnObserve("[System] N tool calls executed", ...)` — TUI ObserveBlock 显示系统提示

- **Circuit Breaker 熔断保数据 (`alice-core-agent`)**: 熔断时不再丢弃已执行的工具结果。
  - 收集 `__action_log` 存入 `ctx.result` + `ctx.lastObservation`
  - 设置 `ctx.__system_event = "[System] Circuit breaker: max depth reached after N tool calls"`
  - 返回 `Finish(actionLog, ...)` 使 PPAO Observe 阶段能获取完整数据

- **Agent 事件系统文档 (`docs/alice-core-agent/event/README.md`)**: 新增事件系统文档，覆盖 `fireOnObserve`/`fireOnAction`/`fireOnThought` 三种事件、`__action_log`/`__system_event`/`lastObservation` LLM 上下文键、事件流图（正常 + 熔断）、TUI ObserveBlock 映射。

- **Planner 测试案例文档 (`docs/alice-core-planner/test/case/fast.md`, `docs/alice-core-planner/test/case/slow.md`)**: 双路径测试案例文档。FastPath 12 条测试点 (FP-T01~T12)，SlowPath 14 条测试点 (SL-T01~T14)，含覆盖缺口分析和代码溯源。

- **MCTS 规划器完整实现 (`alice-core-planner`)**: 依据 MCTS 算法规范全面重构 SlowPathStrategy，实现 4 步迭代（Selection/Expansion/Simulation/Backpropagation）+ UCB1 置信区间 + 0~100 模拟评分。
  - 新增 `ThinkingTree.bestChildByAvgReward()` 选择根节点 avg_reward 最高的子步骤作为下一步执行动作（符合 MCTS 输出规范），替代原 `bestPath()` 全路径分解
  - 新增 `ThinkingTree.pathFromRoot()` 路径回溯方法
  - 新增 `ThinkingTree.logIterationDetail()` / `logNodeChildren()` 实现 per-iteration 详细日志输出：迭代轮次、选中路径、各节点 UCB/visits/avg_reward、HIGH UCB/成熟/未探索标记
  - `DEFAULT_MCTS_ITERATIONS` 从 20 改为 10（规范对齐）
  - 模拟器评分从 1~3 调整为 0~100 分制（50 中立 + prompt 长度加成）
  - Plan 输出从 `bestPath` 全路径多步骤改为单步（根节点 best child）+ FINISH，MCTS 树摘要存储在 metadata（`rootChildren`/`bestAction`/`bestAvgReward`）
  - 新增 `docs/alice-core-planner/mcts.md` 设计文档，覆盖状态定义、4 步迭代、输出格式、实现状态追踪

- **TUI 三区对齐布局 v4.0 (`alice-facade-tui`)**: 全面重构 TUI 布局，从 TAO 四段式 (InputBlock/ThinkBlock/ActionBlock/ObserveBlock) 改为三区对齐 (Main Area / Input Area / Footer)。
  - **`MessageAreaComponent`** (新增) — 统一消息流组件，替代旧的 4 个独立区域组件。所有消息类型按时间序排列，均无背景色，仅通过字体颜色和前缀区分：
    - 用户消息 — 默认终端色
    - 思考推理 — `38;5;252` 浅灰字 + `╸ Step N ╸` 标记
    - 动作执行 — `37` 亮白字 + `▮` 前缀
    - 观察结果 — `37` 亮白字，目录行 `38;5;222` 亮黄
    - 系统/Agent 消息 — 默认终端色
  - **`LineComponent`** (新增) — 区域分割线组件，ANSI `38;5;242` 暗色 `─` 满行。取代旧的 inline `writeRow(separatorLine())` 调用，参与脏标记管线
  - **`TuiLayout`**: 6 组件布局（Header + MessageArea + LineComponent×2 + Input + Footer），FIXED_ROWS=6，三区由 LineComponent 分隔
  - **`Area` / `ColoredArea`** (新增) — 矩形区域基类和整块背景色区域组件。`ColoredArea` 使用 `AttributedStyle` 填充区域背景，子内容覆盖渲染
  - 移除所有 `STYLE_BG_*` / `BG_DARK` / `BG_LIGHT` / `BG_TERMINAL` 背景常量和 `AttributedStyle`→ANSI 转换逻辑。所有消息类型使用 plain ANSI 字符串（同 FooterComponent 方式）
  - `MessageLine` 存储简化：仅保留 `String content` + `String bgCode`（plain ANSI）
  - 废弃的 4 个旧组件文件保留（`InputBlockComponent` / `ThinkBlockComponent` / `ActionBlockComponent` / `ObserveBlockComponent`），不再参与布局管线

- **TUI TAO 四段式布局 v3.1 (`alice-facade-tui`)**: 全面重构 TUI 布局，以四个独立 TAO 区域组件替代单一 `ThoughtComponent`，实现 PPAO 执行流实时渲染。
  - **`InputBlockComponent`** (新增) — 顶部输入内容区，深色底 ANSI 236，纯文本展示用户最新输入，无会话启动前缀
  - **`ThinkBlockComponent`** (新增) — 中间思考推理区，亮色底 ANSI 255，每段推理前显示暗色 `┈ Step N ┈` 步骤标记
  - **`ActionBlockComponent`** (新增) — 动作命令区，深色底 ANSI 236（同 InputBlock 风格），`$ command (timeout 120s)` 终端格式
  - **`ObserveBlockComponent`** (新增) — 观察输出区，终端深色底 ANSI 234，`ls -la` 风格行亮黄高亮，`Took X.XXs` 耗时统计
  - **`TuiLayout`**: 8 组件布局（Header + 4 TAO Zone + Input + Queue + Footer），FIXED_ROWS=6 + QUEUE_HEIGHT=1，ThinkBlock(~45%)/ObserveBlock(~55%) 比例分配
  - `TaoTag` 色块标签不再用于 ThinkBlock 内容，区域背景色自身提供视觉区分
- **PPAO 事件流 Observer 模式重构 (`alice-core-agent`, `alice-facade-tui`)**: 使用标准 Observer 模式替代原始 `Consumer<>` 回调。
  - `AgentEventListener` interface (新增, alice-core-agent): 类型安全的事件监听器方法——`onThought(String)`、`onAction(target, params)`、`onObserve(rawData, summary, elapsedMs)`
  - `AgentExecutor`: `List<AgentEventListener>` 替代单 `Consumer<PPAOEvent>`；移除 `PPAOEvent` record；新增 `addListener()`、`fireOnThought/Action/Observe()` 分发方法
  - `TuiAgentListener` (新增, alice-facade-tui): 实现 `AgentEventListener`，将 PPAO 事件转发到 EventBridge，追踪 `lastAction` 配对 observe，计入工具实际耗时
  - `AliceTuiLauncher.hookAgentEvents()`: 简化为单行 `agent.getExecutor().addListener(new TuiAgentListener(eventBridge))`
  - `TuiEvent.ObservationResult`: 新增 `elapsedSec` 字段，携带实际工具执行耗时
  - `ScreenManager`: 使用 `ObservationResult.elapsedSec()` 替代硬编码的 `addTiming(0.0)`
- **PPAO observe 事件使用 rawData**: 工具执行结果从 `summary`（截断+ `"Tool [X] returned: "` 前缀）改为 `rawData`（完整原生输出），在 ObserveBlock 中展示完整内容

### Features

- **TUI traceId 追踪 (`alice-facade-tui`)**: PPAO 事件携带 traceId，在 ThinkBlock step 标记中显示 traceId 短哈希。
  - `TuiEvent.NewThought`/`ActionExecuting`/`ObservationResult`: 新增 `traceId` 字段
  - `AliceTuiLauncher.submitTaskToAgent()`: 每次用户提交生成新 traceId
  - `TuiAgentListener.newTrace(traceId)`: 重置步数计数器，后续 t/a/o 事件携带此 traceId
  - `ThinkBlockComponent.addThought(thought, step, traceId)`: step 标记后附加 `[traceShort]`

- **TUI ThinkBlock 时间序 PAO 渲染 (`alice-facade-tui`)**: ActionExecuting 和 ObservationResult 事件同步路由到 ThinkBlock，在思考内容之间插入 action/observe 行。
  - `ScreenManager.setupEventListeners()`: ActionExecuting → `thinkBlock.addActionLine()`; ObservationResult → `thinkBlock.addObservationLine()`
  - `ThinkBlockComponent.addActionLine(desc)`: 插入 TaoTag.ACTION 色块 + 命令描述
  - `ThinkBlockComponent.addObservationLine(obs, sec)`: 插入 TaoTag.OBSERVE 色块 + 前 3 行摘要 + 耗时
  - `ACTION_CMD_PREFIX` 正则过滤 observe 数据中的 `$ command` 引导行（已由 action 行展示）
  - TaoTag 色块重用 JLine AttributedStyle true color API 替代原始 ANSI 字符串

- **TUI 组件自渲染 (`alice-facade-tui`)**: 每个 Component 通过 `renderTo(writer)` 自行处理光标定位和行尾清除。
  - `Component.renderTo(Writer)`: 默认方法，遍历 `render()` 结果逐行写入终端
  - `ScreenManager.fullRedraw()`/`redrawScrollArea()`/`restoreLowerArea()`: 统一为调用 `component.renderTo(writer)` + `writeRow()` 辅助方法
  - 消除 ~150 行重复的 cursorLine + writer.write + ANSI_CLEAR_LINE 模板代码

- **TUI 输入队列 (`alice-facade-tui`)**: Agent 忙碌时用户输入自动入队等待，完成后逐一自动提交。
  - `ScreenManager.inputQueue` (`Deque<String>`): FIFO 队列缓存忙碌期间的输入
  - `runInputLoop()`: Agent 忙碌时不再显示 `Agent 正在执行中，请等待` 错误消息，改为静默入队
  - `dispatchNextFromQueue()`: `TaskComplete` 触发后自动从队列取出并提交下一条，状态无需用户干预
  - `TuiLayout.queueRow`: 分割线与输入区之间的状态行，有消息时显示 `📋 N queued messages`，无消息时留空
  - `restoreLowerArea()`/`fullRedraw()`/`redrawScrollArea()`: 同步渲染队列状态行

### Fixes

- **alice-facade-tui/渲染循环输入活跃期误写终端 — 导致二次交互时光标下移**: 渲染线程在 `inputActive=true` 时仍调用 `redrawScrollArea()` 写入终端，与 JLine `readLine()` 竞争光标控制权。第二次 `readLine()` 调用时光标已偏离 inputRow。
  - `renderLoop()`: inputActive 时不再调用 `redrawScrollArea()`，仅标记 `pendingRedraw`，由主线程在 `readLine()` 返回后处理
  - `runInputLoop()`: `inputActive.set(true)` 移至光标定位之前，提前屏蔽渲染线程的终端写入

- **alice-facade-tui/TaoTag 色块使用 AttributedStyle 替代原始 ANSI**: 之前 `ACTION_TAG`/`OBSERVE_TAG` 使用硬编码 `\u001B[48;5;Nm` 256 色 ANSI 字符串。现改用 JLine `AttributedStyle.background(r,g,b)` true color API，终端兼容性更广。色块后追加 `ANSI_BG_LIGHT + ANSI_FG_DARK` 恢复 ThinkBlock 背景，避免色块 reset 后内容文本失去底色。

- **alice-facade-tui/双重复显示**: `StartThinking` 事件处理器不再重复调用 `InputBlock.showUserInput()`——已在 `runInputLoop()` 中直接写入。`ChatMessage(User)` 消息路由从 InputBlock 改为 ThinkBlock，作为对话历史展示。
- **alice-facade-tui/TuiState**: Invalid TUI state transition `IDLE -> IDLE` 不再抛出 `IllegalStateException`。允许 `IDLE -> IDLE` 作为合法的空操作转换，消除 `TaskComplete` 事件或 `/reset` 命令在状态已为 `IDLE` 时触发的异常。
- **alice-facade-tui/ScreenManager**: 修复终端关闭时 `reader.readLine()` 抛出 `Already closed`（`IllegalStateException`）的竞态条件。新增通用 `catch (Exception e)` 分支，在终端关闭时优雅退出输入循环而非崩溃。
- **alice-tool-gateway/BuiltinTools/grep**: `grep` 工具从仅支持单个文件扩展为支持目录路径递归搜索（类似 `grep -r`）。当 LLM 传入 `.` 等目录路径时自动遍历所有文件搜索匹配行，多文件场景在输出行前附加文件名前缀。
- **alice-tool-gateway/ExecutionEngine/wrapError**: 修复工具执行异常时根因消息被双层异常包装（`ExecutionException -> RuntimeException`）掩盖的问题。新增 `deepestCauseMessage()` 深入异常链提取最内层原因消息，LLM 现在能收到如 `"Tool [grep] execution failed: Sandbox execution failed (cause: grep: path not found: .)"` 的有用信息。

### Docs

- **`docs/alice-facade-tui/Layout.md`**: 从 v2.6 更新为 v3.1 TAO 四段式布局工程设计文档。涵盖 8 组件布局、PPAO Observer 模式（`AgentEventListener`）、输入队列机制、实际耗时传递、线程模型全链路图、ANSI 配色速查表。
- **`docs/alice-facade-tui/Layout_TAO.md`**: 新增 v3.1 规格文档，含四段式区域说明、PPAO 执行流图、事件路由映射表、布局计算公式、ANSI 配色速查、关键代码入口对照表。

### Features

- **Micro-ReAct 深度与宏迭代解耦 (`alice-core-agent`)**: 新增 `AgentConfig.maxMicroDepth` 独立配置，默认值 30，与 `maxIterations` 解耦。Micro-ReAct 熔断阈值从此不再受 PPAO 外层迭代次数限制，支持需要大量工具调用的分析类任务。
  - `AgentConfig.java`: 新增 `DEFAULT_MAX_MICRO_DEPTH = 30`、`maxMicroDepth` 字段、getter、builder setter
  - `AgentExecutor.java`: `microReActLoop()` 改用 `config.maxMicroDepth()`
  - `~/.alice/config.json` / `docs/config/config.json`: 新增 `"max_micro_depth": 30`
  - `~/.alice/example.yaml`: 新增配置键参考
  - `docs/config/README.md`: 配置表更新

- **重复 `read_file` 代码层拦截 (`alice-core-agent`)**: 每次 `dispatchToolCall()` 执行前检查目标路径是否已在 `ctx.__read_files` 集合中，已读路径返回 `[CACHED]` 跳过沙箱执行。
  - 执行前拦截：dispatchToolCall 中 pre-check，已读路径即时返回 `Future.succeededFuture([CACHED])`
  - 路径跟踪：迁移至 `hasMoreMarkers` 检查之前，确保每批每个 tool call 都记录
  - 两种上下文注入：`buildMicroUserContent()` 在 user role 中列出已读文件；`dispatchLlmInference()` 检查 `__micro_system_prompt` 传入 system role

- **Prompt 分拆为 system/user 双 role (`alice-core-agent`, `alice-model`)**: LLM 请求体从单一 `{"role":"user"}` 消息重构为 `[{"role":"system"}, {"role":"user"}]` 双消息结构。
  - `core_loop.ftl` / `micro_loop.ftl`: 完全静态化，移除所有 FreeMarker 变量
  - `PromptManager.java`: 新增 `buildMicroLoopSystemPrompt()`（缓存静态 system 内容）、`buildMicroUserContent()`（动态组装变量）
  - `Call.java`: `Payload` 新增 `systemPrompt` 字段（nullable，向后兼容）
  - `ModelProvider.java`: 新增 `dispatch(modelId, systemPrompt, prompt, params)` 重载
  - `AgentExecutor.java`: `microReActLoop()` 设 `__micro_system_prompt`；`dispatchLlmInference()` 传入 system prompt

- **OpenAiSupplier Jackson 重写 (`alice-model`)**: 废弃手拼 JSON 字符串的 `buildRequestBody()`，改用 Jackson `ObjectMapper` 构建请求体。
  - 正确处理 `messages[]` 数组：有 systemPrompt 时 `[{"role":"system"},{"role":"user"}]`，无则仅 `[{"role":"user"}]`
  - 响应解析改用 Jackson `JsonNode`，废弃手写 `extractJsonField()` 和 `formatToolsArray()`

- **调试日志 (`alice-core-agent`, `alice-model`)**: 新增 `[Micro-ReAct/LLM] Using system prompt (N chars) + user prompt (M chars)`、`[OpenAiSupplier] Request body (first 500 chars): ...` 日志

### Fixes

- **路径跟踪丢失 (`alice-core-agent`)**: `hasMoreMarkers=true` 时 `dispatchToolCall` 的 success 回调提前 return，跳过路径记录代码。修复：将 `read_file` 路径记录迁移到 `hasMoreMarkers` 检查之前。

- **`__micro_system_prompt` 未设置 (`alice-core-agent`)**: 前次 overlapping edits 冲突解决时 `microReActLoop()` 中 `ctx.put("__micro_system_prompt", ...)` 静默丢失。修复：补回该行代码。

- **`buildRequestBody()` JSON 语法错误 (`alice-model`)**: 手拼 JSON 字符串出现转义错误导致 `"}"}` 非法。修复：全局替换为 Jackson ObjectMapper。

### Features

- **Micro-ReAct 并行工具调度 + 虚拟线程 (`alice-core-agent`)**: LLM Function Calling 返回多个 `tool_calls` 时，不再逐个递归 dispatch，改为通过 `CompletableFuture.supplyAsync()` + `Executors.newVirtualThreadPerTaskExecutor()` 并行执行所有独立工具调用。
  - 全部工具结果通过 `CompletableFuture.allOf().whenComplete()` 聚合，保留原始调用顺序
  - 深度消耗从 N 个 depth（每工具+1）降为 1 个 depth（整个 batch +1），熔断阈值不再因多工具浪费
  - `read_file` cache 在并行调度中同样生效，已读路径返回 `[CACHED]` 跳过执行
  - WAL 记录、GuardrailToolProxy 检查、`fireOnAction`/`fireOnObserve` 事件配对均在并行路径中正确处理
  - 新增 `ParallelToolResult` 内部 record 封装并行结果

- **Procedural Memory: Prompt/Rule 新类型 (`alice-core-agent`)**: 新增加载器 + 两种 Procedural Memory 类型，使 agent prompt/rule 可从本地文件动态加载。
  - `PromptDef` record — FreeMarker 模板定义，存储于 `~/.alice/prompts/<name>.ftl`
  - `RuleDef` record — Markdown 规则定义（含 YAML front-matter: `title`/`priority`/`applies_to`），存储于 `~/.alice/rules/<name>.md`
  - `FilePromptLoader` — 懒扫描 + 缓存文件加载器，覆盖 `~/.alice/prompts/*.ftl` 和 `~/.alice/rules/*.md`，支持运行时 `reload()`
  - `PromptManager` 加载优先级：`~/.alice/prompts/` → classpath 兜底
  - `buildSystemPrompt()` 自动追加 `~/.alice/rules/*.md` 内容到 system prompt（按 priority 排序：high > medium > low）
  - 新增 `PromptManager.reloadFromDisk()` 支持运行时重载
  - 新增 `docs/prompt/README.md`、`docs/prompt/STRUCTURE.md`、`docs/rule/README.md` 文档

### Fixes

- **Invalid phase transition: `REFLECTING -> REVISION` (`alice-core-agent`)**: `AgentContext.transitionTo()` 的状态机不允许 `REFLECTING` → `REVISION` 转换。当 Post-Verify 检测到错误模式后强制 revision 时，`reflect()` 先设 REFLECTING 再跳 REVISION 导致 `IllegalStateException`。修复：`AgentContext.java` phase 状态机增加 `REFLECTING -> REVISION` 合法边。

- **TUI Observe 事件 action 前缀错配 (`alice-core-agent`, `alice-facade-tui`)**: 并行调度中 `fireOnAction()` 在预执行循环全部执行完毕（`lastAction` 被覆盖为最后一个工具），之后 `fireOnObserve()` 读到错误的 action 前缀。修复：将 `fireOnAction` 移到 `whenComplete` 中与 `fireOnObserve` 配对，确保每个 observe 拿到正确的 `$ command` 前缀。

## 20260627

### BREAKING

- **TUI 布局 v2.6 重构 (`alice-facade-tui`)**: 重新设计 TUI 布局，Footer 移至终端最底行 (H-1)，输入区位于 Footer 上方 (H-3)，由两条分割线包裹。
  - `TuiLayout`: 新布局顺序 `Header → Content → Separator1 → Input → Separator2 → Footer`
  - FIXED_ROWS=5 保持不变，contentHeight = H-5
  - `separatorRow` (H-4): 滚动区与输入区之间的分割线
  - `separator2Row` (H-2): 输入区与 Footer 之间的分割线
  - `inputRow` (H-3): 输入区
  - `footerRow` (H-1): Footer 在终端最底行

### Fixes

- **TUI 首次输入光标错位 — JLine 首次 readLine() 初始化覆盖手动定位**: 首次 `reader.readLine()` 调用时 JLine 初始化显示层，覆盖了手动 `cursorLine()` 的定位。修复方式：
  - 在 synchronized 块前调用 `terminal.getHeight()` 创建终端 I/O 同步点，确保终端完成处理所有先前输出
  - 使用原始 ANSI 转义 `\033[%d;1H` 而非 `cursorLine()`（`terminal.puts`）定位光标，不更新 JLine 内部光标跟踪
  - 在 `setVariable(LINE_OFFSET, 2)` 前调用 `getVariable(LINE_OFFSET)` 预热 JLine 内部变量系统
  - `LINE_OFFSET=2` 保留输入区下方 2 行（Separator2 + Footer）不被 JLine 覆盖

- **TUI 渲染线程与 JLine readLine() 终端输出竞态**: 渲染线程的 `redrawScrollArea()` 与主线程的 `reader.readLine()` 同时写入终端，导致光标跳跃和内容错乱。
  - 新增 `inputActive` / `pendingRedraw` 原子标记
  - 渲染循环在 `inputActive=true` 时跳过终端写入，将重绘标记记录到 `pendingRedraw`
  - 主线程在 `readLine()` 返回后在 terminalLock 下处理 deferred 重绘

- **TUI `redrawScrollArea()` 使用 raw ANSI 导致光标定位不准**: `cursorLineRaw()` 在某些终端上不能正确解析 `\033[row;1H`，导致 Footer 和分割线出现在同一行。
  - `redrawScrollArea()` / `restoreLowerArea()` / `fullRedraw()` 统一使用 `cursorLine()`（`terminal.puts`）定位，获取 terminfo 感知的转义序列
  - `runInputLoop()` 中的预 readLine 定位依然使用 raw ANSI（避免干扰 JLine 内部跟踪）

- **TUI 启动帮助提示文本**: 移除 `AliceTuiLauncher.start()` 中的 `eventBridge.onChatMessage("System", "欢迎使用 Alice Agent TUI！")` 和 `"输入 /help 查看可用命令。"`；移除 `ScreenManager.start()` 中的 `addSystemMessage("Alice Agent v0.1.0 TUI 已启动。输入 /help 查看可用命令。")`。

### Docs

- **`docs/alice-facade-tui/Layout.md`**: 更新为 v2.6 布局文档，Footer 在终端最底行，输入区由双分割线包裹。

### BREAKING

- **移除所有文本格式工具调用解析，仅保留标准 Function Calling (`alice-core-agent`)**: 移除全部向后兼容的文本格式解析逻辑，仅保留标准 Function Calling API（`finish_reason` + `tool_calls` 结构化字段），遵循 `docs/alice-model/LLM Function Calling（Tool Call）接口技术规范文档.md`。
  - 移除 `parseToolCallFromOutput()` 方法（~200 行）— 不再解析 `[TOOL_CALL:]`、`<tool_call>`、fenced code blocks、inline backticks 等文本格式
  - 移除 `countToolCallMarkers()` 方法 — 文本标记计数不再需要
  - 移除 `[FINISH]` 文本标记检查 — Agent 终止现在由 `finish_reason="stop"` 确定，模型无需输出 `[FINISH]`
  - 移除所有 `<tool_call>` 相关正则表达式和 `indexOf` 手动解析代码（16 处）
  - 删除 `AgentExecutorUnitSpec.groovy` 中对应的 `countToolCallMarkers` 测试方法
  - 应用 `spotless` 格式化

- **Prompt 模板移除 `[FINISH]` 和文本格式指令**: 三个 FreeMarker `.ftl` 模板全面更新。
  - `core_loop.ftl`: 移除系统提示中的 `[FINISH]` 协议标记和 `<tool_call>` 文本回退格式说明，精简为仅使用 Function Calling
  - `micro_loop.ftl`: 移除 `[FINISH]` 和 `<tool_call>` 文本格式指令
  - `micro_loop_error.ftl`: 移除 `[FINISH]` 指令

- **TUI 三层布局 v2.3 净化重构 (`alice-facade-tui`)**: 基于 `docs/alice-facade-tui/Layout.md` v2.3 全面重写 TUI 渲染管线，落地等宽实体背景色块、零噪音输入视口、底部 TAO 实体仪表盘。
  - **`TaoTag` 色块枚举 (新增)**: `THOUGHT`(暗灰底 239)、`ACTION`(橙黄底 214)、`OBSERVE`(绿底 35) — ANSI 256 色背景全填充矩形色块，替代传统 `[T Thought]` 文本前缀
  - **`HeaderComponent` 净化**: 移除 `sessionLabel` 字段/`setSessionLabel()` — 删除会话 ID 冗余文本；分割线动态自适应终端宽度延伸至视口最右侧；版本标识更新至 v0.60.0
  - **`ThoughtComponent` 色块化**: `addThought()`/`addAction()`/`addObservation()` 输出 TaoTag ANSI 背景色块；`addUserMessage()`/`addSystemMessage()`/`addAgentMessage()` 移除了 `User:`/`System:`/`Agent:` 角色文本前缀，直接展示内容
  - **`InputComponent` 零提示符**: 默认 `prompt` 从 `" > "` 变更为 `""`(空字符串)，移除 `>` / `$` 传统提示符噪音
  - **`FooterComponent` 实体仪表盘**: 从前景色(`38;5;XXXm`)切换为背景色块(`48;5;XXXm`) — 费用(橙黄 208)、速率(绿 35)、模型(暗灰 239) 三个物理隔离独立色块；工具信息使用暗色 `── 🔌` 文本前缀
  - **`ScreenManager` 边界防御**: `COMPLETION_LIST_MAX` 从 5 降至 3(Layout.md §1 补全菜单最大 3 行，溢出自动内部滚动，杜绝底部状态栏被顶出)
  - **`StartThinking` 重复内容消除**: 用户输入已通过 `addUserMessage()` 展示，`StartThinking` 事件不再添加 `"思考中:"` 前缀的重复行，仅触发渲染刷新
  - **`resolveLines()` 增强**: `addUserMessage()`/`addSystemMessage()`/`addAgentMessage()` 使用新 `resolveLines()` 方法，处理 `\n` 转义序列 + 受损 `n` 启发式修复(句末标点后的裸 `n`/小写字母后的 `n+大写字母`/结构字符前的 `n`)为实际换行
  - **`[FINISH]` 协议标记隐藏**: `addAgentMessage()` 自动正则剥除 `\[FINISH\]` 内部协议标记；标记剥除后若无实际内容则不添加空行

- **WAL 包从 `alice-memory-vault` 迁移至 `alice-core-agent`**: `org.cland.alice.memory.wal` 包整体移至 `org.cland.alice.core.agent.wal`，解决 WAL 作为核心生命周期组件却位于外围模块的架构倒挂问题。
  - 迁移 12 个 WAL 源文件（`WalSession`/`WalStore`/`FileWalStore`/`InMemoryWalStore`/`WalAppender`/`WalCompactor`/`Checkpoint`/`CheckpointManager`/`RawMessage`/`ToolCall`/`RecoveryEngine`/`PromptMelter`）及 10 个 Spock 测试规范
  - `alice-core-agent` 模块新增 `exports org.cland.alice.core.agent.wal`，移除 `requires alice.agent.alice.memory.vault.main`
  - 依赖方向反转：`alice-memory-vault` 新增 `requires alice.agent.alice.core.agent.main`
  - 同步迁移 `AgentSession` 至 `org.cland.alice.core.agent.memory` 包，消除双向循环依赖
  - 更新全部 8 个外部消费模块的 import 语句（`alice-facade-cmd`/`alice-facade-tui`/`alice-memory-vault` dreaming 与 vault 包）
  - 所有模块编译通过（85 tasks），全部单元测试通过

### Features

- **推理内容（reasoning_content）渲染 (`alice-core-agent`, `alice-facade-tui`)**: 模型返回的 `reasoning_content`（DeepSeek R1 风格推理链）现被提取并传递到 TUI 渲染。
  - `AgentExecutor.dispatchLlmInference()`: 新增 `extractReasoningFromRaw()` 从原始元数据 JSON 中解析 `reasoning_content`，存入 `ctx.__llm_reasoning`
  - `Agent.ask()`: 新增 `lastReasoning` 字段和 `getLastReasoning()` getter，PPAO 完成后从上下文中提取推理内容
  - `AliceTuiLauncher.submitTaskToAgent()`: agent 调用完成后读取 `agent.getLastReasoning()`，通过 `eventBridge.onNewThought()` 发射到 `ThoughtComponent` 渲染
  - 修复 `extractReasoningFromRaw()` 中 `"reasoning_content":"` 长度计算 off-by-one 错误（21 字符，原为 22）
  - 修复 `extractFinishReasonFromRaw()` 中 `"finish_reason":"` 长度计算 off-by-one 错误（17 字符，原为 18），导致 `stop` 读为 `top`

- **`finish_reason` 驱动 Agent 终止 (`alice-core-agent`)**: Agent 终止逻辑从 `[FINISH]` 文本标记迁移为标准的 `finish_reason` 枚举值。
  - `dispatchLlmInference()`: 解析 `finish_reason` 并存入 `ctx.__finish_reason`
  - Micro-ReAct Reason 段: 使用 `finish_reason` 判断下一步——`"stop"` → 完成并返回结果、`"tool_calls"` → 分发工具调用、其他（`length`, `content_filter`, `error`）→ 返回错误
  - 无 `finish_reason` 时默认回退 `"stop"`

- **`__true_start` / `__turn_end` 对话轮次元数据 (`alice-core-agent`)**: 新增两个上下文字段追踪对话状态。
  - `__turn_end` — boolean，`finish_reason="stop"` 时为 `true`（本轮对话结束），否则 `false`
  - `__true_start` — boolean，当响应为最终回答（非工具调用中间响应）时为 `true`

- **TUI ToolRegistry 接线 (`alice-facade-tui`)**: TUI 启动时自动发现并注册 BuiltinTools（9 个工具：read_file, write_file, grep, run, list_dir, file_exists, search_file, remove_file, web_search）到 Agent。
  - `AliceTuiLauncher()`: 从 `ToolRegistryHolder.INSTANCE` 获取全局注册表，通过 `ToolDiscovery.scanAndRegister()` 扫描 `BuiltinTools` 实例
  - 通过 `agent.withToolRegistry(toolRegistry)` 将注册表注入 Agent
  - `module-info.java`: 新增 `requires alice.agent.alice.tool.gateway.main`
  - `build.gradle`: 新增 `implementation project(':alice-tool-gateway')`

- **Micro-ReAct 后续 LLM 调用修复 (`alice-core-agent`)**: 修复 dispatchToolCall 返回 `Continue(Action.llmInference(...))` 后 compose 链深度累积导致 circuit breaker 误触发的问题。
  - 直接递归分派后续 LLM 调用（`microReActStep(updatedCtx, continueAction, originalPrompt, depth + 1, maxDepth)`），保持 compose 链扁平
  - microReActLoop 每次重置 depth 为 0，确保 depth 不会跨 LLM 调用累积
  - 移除 `All tool calls consumed` 中对 `__next_action` 相关字段的错误清理

- **TUI `/resume` 会话恢复命令**: 在 TUI 模式下实现完整的会话列表查看与选择恢复功能。
- **`tool_register` 角色 & 动态工具变更追踪**: 新增 `tool_register` 作为 WAL 第六种消息角色，用于记录对话中途工具集变更。
  - `RawMessage.VALID_ROLES` 新增 `"tool_register"`，添加 `toolRegister()` 工厂方法
  - `WalAppender` 新增 `appendToolRegister()` 方法
  - `WalSession` 新增 `toolRegister()` 便捷方法，自动注入 `spanType=tool_register`, `isUserVisible=false`
  - `AgentExecutor.perceive()` 在 `wal.system()` 后自动调用 `wal.toolRegister()` 记录当前工具集
  - 工具定义以紧凑 JSON 存储在 `content` 字段中（`[{"type":"function","function":{...}}]`）
  - `SpanType` 新增 `TOOL_REGISTER`、`TOOL_CALL_RESULT` 枚举常量
- **WAL 全量消息元数据追踪**: 每条 WAL 消息现在自动携带完整追踪标识。
  - `WalSession.autoMetadata()` 自动生成 `traceId`（每 session 实例唯一）和 `spanId`（每消息唯一，Snowflake 算法）
  - `SnowflakeIdGenerator.getInstance()` 单例供全局使用
  - `parentSpanId` 支持（通过 `autoMetadata(SpanType, boolean, String)` 重载），为子 Agent 嵌套追踪准备
  - `isUserVisible` 用于 SFT 训练过滤（Scenario A 仅保留 `userVisible=true` 的 final response）
- **`SpanType` 从常量类重构为 Java Enum**: `public final class SpanType` → `public enum SpanType`，移除 `fromString()`/`isValid()`/`requireValid()` 等反向兼容方法
- **WAL 存储路径从哈希改为完整 Snowflake ID**: `~/.alice/wal/{hashCode & 0xFFFF}/` → `~/.alice/wal/{sessionId}/`，人类可读、零碰撞

### Fixes

- **系统提示不再含工具描述文本**: `core_loop.ftl` 删除 `[TOOL_CALL: ...]` 文本格式工具列表，改为引用 `tool_register` 消息中的 JSON 工具定义
- **CLI e2e 测试回归验证**: `test_cli_categories.py` 41 测试（34 通过，7 已知失败）和 `test_resume.py` 6 测试全部通过，无回归
- **`WalSession.think()` / `finalAnswer()` 替换 `assistant()`**: 之前所有 LLM 输出（thought + final answer）都用 `wal.assistant()` 导致 spanType 无法区分。现在 `AgentExecutor.dispatchLlmInference()` 根据是否含 `toolCalls` 选择 `think()` / `finalAnswer()`
- **`wal.toolResult()` 现在注入 `spanType=tool_call_result`**: 之前 tool 消息无 spanType 元数据，SFT 训练无法区分 tool result 与其他消息
- **WAL 内容无 `\r\n` 污染**: `ObjectMapper.writeValueAsString()` 紧凑序列化替代 `writerWithDefaultPrettyPrinter()`，消除 Windows 平台 `\r\n` 嵌入 JSON 字符串值的问题
- **TUI `/resume` 分发路由断裂 — CommandHandler.onAgentCommand 未注册**: `CommandHandler` 内部 `dispatchToAgent()` 发现 `onAgentCommand` 回调为 null，所有 `ResumeSessionCmd` 被静默丢弃，`AliceTuiLauncher.handleResume()` 永不触发。
  - `ScreenManager`: 新增 `onAgentCommand(Consumer<AgentCommand>)` 公开方法，委托给 `commandHandler.onAgentCommand()`
  - `AliceTuiLauncher.setupCallbacks()`: 新增 `.onAgentCommand(this::dispatchAgentCommand)`，补全 `CommandHandler → AliceTuiLauncher` 的分发链路
- **TUI `/resume` 历史消息未加载到聊天窗口**: `handleResume()` 仅显示恢复摘要，未加载和展示 WAL 中的原始会话消息。
  - `AliceTuiLauncher.handleResume()`: 恢复后调用 `wal.getAllMessages(sessionId)` 读取全量消息
  - 过滤 `system`/`tool_register` 角色和 `isUserVisible=false` 的内部消息，仅展示实际对话
  - 按角色映射为 `User`/`Assistant`/`[Tools]`/`[Tool Result]`/`[Summary]` 标签，通过 `eventBridge.onChatMessage()` 逐条显示
  - 绑定 `agent.withWal(wal)` 使后续消息继续追加到恢复的会话 WAL
- **TUI `/resume` WAL 目录解析路径不匹配**: `handleResume()` 使用 `Integer.toHexString(sessionId.hashCode() & 0xFFFF)` 哈希子目录，与初始 Agent 构造使用的 `~/.alice/wal/{sessionId}/`（完整 Snowflake ID）不一致，导致旧风格会话无法找到。
  - `AliceTuiLauncher.resolveWalDir(sessionId)`: 新增双策略定位方法 — 优先尝试 `sessionId` 直接路径，未命中时扫描所有子目录（兼容旧 hash 子目录）
  - 操作详情改用 `logger.debug()` 记录，减少终端输出污染
- **TUI 底部状态栏（Footer）永久消失 — `runInputLoop()` 的 `\033[J` 擦除 + 渲染循环不恢复**:
  - `ScreenManager.runInputLoop()`: `\033[J`（清屏到终端底端）在每次输入前擦除下方分割线和状态栏。改为 `\033[2K`（仅清除当前行），避免误擦除底部区域
  - `ScreenManager.redrawScrollArea()`: 新增下分割线和状态栏的重绘逻辑，确保 `contentDirty` 时底部区域与其他静态区域一同刷新
- **TUI 异步事件竞态（`/resume` 需输入两次才生效）— EventBridge 事件异步投递导致 UI 更新滞后**:
  - `EventBridge`: 新增 `emitSync()` 方法，在调用线程上同步分发事件到监听器
  - `EventBridge.onChatMessage()`: 改用 `emitSync()`（同步投递），确保 UI 关键消息在 `runInputLoop()` 下一次 `redrawScrollArea()` 前完成布局更新
- **TUI 渲染线程 `terminal.puts()` 干扰 JLine 光标追踪 — 输入提示符错行、状态栏覆盖**:
  - `ScreenManager`: 新增 `cursorLineRaw()` 方法，使用原始 ANSI `\033[row;1H` 定位光标，绕过 `terminal.puts()` 避免后台线程更新 JLine 内部光标状态
  - `ScreenManager.redrawScrollArea()`: 全部 `cursorLine()` 替换为 `cursorLineRaw()`，消除渲染循环与 `readLine()` 的光标追踪竞态
- **TUI `/` 补全菜单覆盖底部区域 — JLine AUTO_MENU 列表渲染在输入行下方**:
  - `ScreenManager`: 新增 `restoreLowerArea()` 方法，在每次 `reader.readLine()` 返回后无条件重绘下分割线和状态栏
  - `ScreenManager.runInputLoop()`: 每次读取后调用 `restoreLowerArea()`，恢复被 JLine 补全菜单覆盖的内容
- **TUI 输入重影 / 渲染线程与主线程终端输出竞态 — 物理光标在 `readLine()` 前被后台线程移动**:
  - `ScreenManager`: 新增 `terminalLock` 对象，`synchronized` 保护所有对 `terminal.writer()` 的写入（`redrawScrollArea()`、`restoreLowerArea()`、`runInputLoop()` 中的光标定位）
  - `ScreenManager.renderLoop()`: 移除无内容变更时的主动终端写入（`restoreLowerArea()`），仅当 `contentDirty` 时执行 `redrawScrollArea()`
  - 同步确保 `runInputLoop()` 的 `terminal.puts()` → `readLine()` 序列不被渲染线程的原始 ANSI 写入交错
- **TUI 终端 resize 不触发重绘 — Footer 不在底部 / Input 残留在中间**: 终端 resize 时 WINCH 信号可能因 JLine 4 + JDK 25 信号兼容性问题无法抵达，且 `terminal.getWidth()` 返回缓存值导致轮询无效。同时 resize 未通过 EventBridge 事件系统分发，处理逻辑分散在 WINCH handler 中。
  - `TuiEvent`: 新增 `TerminalResize(int width, int height)` 事件类型 — resize 成为一等 TUI 事件
  - `EventBridge`: 新增 `onTerminalResize(w, h)` 方法，使用 `emitSync()` 同步投递（与 `ChatMessage` 同级）
  - `ScreenManager`: WINCH handler 和轮询均通过 `eventBridge.onTerminalResize()` 分发；新增 `TerminalResize` 事件监听器统一处理 `layout.recalculate()` + `LINE_OFFSET` 更新 + `needsFullClear`/`contentDirty` 标记
  - `ScreenManager.renderLoop()`: 新增轮询保底检测（每 500ms 比较尺寸，INFO 级日志），作为 WINCH 不可达时的 fallback
  - `ScreenManager.redrawScrollArea()`: 当 `needsFullClear` 为 true 时先全屏清除再按新布局重绘各区域，杜绝旧位置像素残留
  - `AliceTuiLauncher`: JLine 终端 provider 顺序从 `exec,ffm,jni,dumb` 改为 `ffm,exec,jni,dumb`（FFM 提供原生 tty 信号处理）
  - `ThoughtComponent`: 新增 `onResize(oldHeight)` 方法 — 终端变大时减少 `scrollOffset` 揭示上方隐藏内容，变小时保持底部锚定
  - `TuiLayout.recalculate()`: 调用 `thought.onResize(oldContentHeight)` 调整滚动视口

- **Micro-ReAct 陈旧状态泄漏导致无限工具循环 (`alice-core-agent`)**: 当 LLM 在一次响应中同时返回文本和 `tool_calls` 时，`dispatchToolCall` 排队的后续 LLM 调用继承了上一轮的 `__tool_calls`、`__tool_call_index`、`__finish_reason` 等上下文，导致陈旧工具被重新调度，Micro-ReAct 陷入无限循环直至断路器触发。
  - 根因：Reason 阶段在 `continueAction=LLM_INFERENCE` 的 early-return 分支（`AgentExecutor.java` 第 ~525 行）直接递归 `microReActStep(updatedCtx, ...)`，跳过了 `__tool_calls` 等变量的清理
  - 修复：在 early-return 分支中增加 `remove("__tool_calls")` 等 5 个清理调用，与下方 "All tool calls consumed" 块的清理保持一致

- **TUI 输入期间 Agent 响应不可见 (`alice-facade-tui`)**: Agent 响应到达时若用户正在输入（`inputActive=true`），渲染循环跳过重绘并将脏标记清空，响应内容直到下一次 `readLine()` 返回后才出现。
  - 根因：`ScreenManager.renderLoop()` 在 `inputActive=true` 时设置 `pendingRedraw` 并清除 `contentDirty`，实质上是丢弃了脏标记而不渲染
  - 修复：移除输入活跃期间的跳过逻辑，始终执行 `redrawScrollArea()`；该操作只修改输入行上方的滚动区（Header + Thought + 分割线），不触碰 JLine 管理的输入行，不会与 `readLine()` 产生终端输出竞争
  - 同步移除 `pendingRedraw` 字段及其在 `runInputLoop()` 中的 deferred 处理代码

### Fixes

- **WAL 缺失 System Prompt 记录**: WAL 中只有 `user`、`assistant`、`tool` 三类消息，缺少 `role: "system"` 消息。Agent 的 System Prompt（来自 `core_loop.ftl` 模板的 `<system>` 块）被构建为完整 prompt 的一部分传给 LLM，但从未写入 WAL。恢复重放时无法追溯 Agent 被赋予的系统指令。
  - `PromptManager.buildSystemPrompt()`: 新增方法，从 `core_loop.ftl` 渲染输出中提取 `<system>...</system>` 块内容并缓存（静态内容，无 FreeMarker 变量）
  - `AgentExecutor.perceive()`: 在 `wal.user()` 之前调用 `wal.system()` 写入系统提示，确保 WAL 消息顺序为 `system → user → assistant → tool`

- **TUI 双重状态转换导致 IllegalStateException**: `submitTaskToAgent()` 同时调用 `eventBridge.onTaskError()/onTaskComplete()`（通过事件监听器触发 `state.transitionTo()`）和直接调用 `screenManager.state().transitionTo()`，导致异步竞争下出现 `ERROR → ERROR` 或 `IDLE → IDLE` 的非法状态转换。
  - `AliceTuiLauncher.submitTaskToAgent()`: 移除 try 和 catch 块中的冗余 `screenManager.state().transitionTo()` 调用，状态转换完全由 `ScreenManager.setupEventListeners()` 中的事件监听器负责

- **Agent.ask() 中 onSuccess 回调异常导致线程挂起**: 当 PPAO 执行器返回的上下文不含 `"result"` 键时，fallback `callLlmDirect()` 内抛出 `RuntimeException`。由于 `onFailure` 绑定的是原始 Future 而非 `onSuccess` 返回的新 Future，异常从未被捕获，`latch.countDown()` 永不调用，主线程在 await 处超时后抛出 `"Agent ask returned null result"`。
  - `Agent.ask()`: `onSuccess` 回调体包裹 `try { ... } catch (Exception e) { errorRef.set(e); } finally { latch.countDown(); }`，确保 latch 始终释放、错误正确传播

- **logback.xml 文件路径使用全角波浪线 + 目录名错误**: `logback.xml` 中文件路径使用 `～`（全角波浪线 U+FF5E）而非 `~` 或 `${user.home}`，Logback 不识别全角波浪线。同时目录 `los/` 疑似 `logs/` 的笔误。
  - `alice-bootstrap/src/main/resources/logback.xml`: 路径改为 `${user.home}/.alice/logs/alice-agent.log`
  - 新增 `<statusListener class="ch.qos.logback.core.status.NopStatusListener" />` 压制 Logback 内部初始化状态信息输出到控制台

- **OPENAI_API_KEY 未设置时 System.err.println 误导性警告**: TUI 启动时 `System.err.println("Warning: OPENAI_API_KEY not set, LLM features will be unavailable.")` 打印到控制台且消息不准确——DeepSeek 等其他提供商仍可正常使用。
  - `AliceTuiLauncher.launch()`: 改为 `logger.warn()` 输出到日志文件，消息更新为 "OpenAI models will be unavailable, but other providers may work."

- **ToolCallParser System.err.println 控制台污染**: `AgentExecutor.java` 中 4 处 `System.err.println("[ToolCallParser] regex compile failed: ...")` 在每次 LLM 工具调用解析时打印到 stderr，干扰正常输出。
  - `AgentExecutor.java`: 4 处全部改为 `logger.warn("[ToolCallParser] regex compile failed: {}", e.getMessage())`，路由到日志文件

### Features

- **~/.alice/model.json 默认模型配置**: `ModelConfigLoader` 新增对 `"default_model"` 顶级字段的解析支持。TUI 和 CLI 现在从 `~/.alice/model.json` 读取默认模型，不再硬编码 `"gpt-4o-mini"`。
  - `ModelConfigLoader`: 新增 `defaultModel` 字段、`load()` 中解析 `"default_model"`、`getDefaultModel()` getter
  - `AliceTuiLauncher.launch()`: 使用 `configLoader.getDefaultModel()` 创建 `AgentConfig`，无配置时回退 `"gpt-4o-mini"`
  - `AliceCliLauncher`: `initializeModelProvider()` 返回默认模型 ID，新增 `loadedDefaultModel` 静态字段用于 `config` 子命令显示
  - `ExecutionCoordinator`: 新增 `modelOverride` 构造参数，`execute()` 使用 `modelOverride ?? config.model()`
  - `printConfigValue("default.model", ...)` 和 `printAllConfig()`: 显示来自 `model.json` 的默认模型及其来源

- **默认 ~/.alice/model.json 配置文件** (`~/.alice/model.json`): 创建完整的模型配置文件，默认模型设为 `deepseek-v4-flash`（匹配当前 DEEPSEEK_API_KEY 环境变量）。
  - 包含两个提供商：`openai`（3 模型）和 `deepseek`（2 模型）
  - API 密钥通过 `${ENV_VAR}` 环境变量引用
  - 权限 `600`（仅用户可读）

### Smoke Tests

- **PMTEV Case 3 验证 WAL System Prompt**: `test_smoke_case_3.py` 全部 2 个测试通过。新生成的 WAL 会话同时包含 `role: "system"`（messageId=1，系统指令）和 `role: "user"`（messageId=2，用户输入），消息链路追溯完整。

## 20260623

### Fixes

- **WAL 工具结果保存原始数据**: 工具执行结果从状态摘要字符串（`"Tool read_file executed successfully"`）改为记录实际返回内容（文件内容、命令输出、搜索结果等）。这使得 WAL 重放后 Agent 能准确知道之前看到了什么。
  - `AgentExecutor.dispatchToolCall()`: `wal.toolResult()` 现在传入 `result.rawData()` 或 `result.summary()`，而非固定状态字符串

- **WAL 保存 LLM 推理链（`<thought>`）**: 当 LLM 返回 `tool_calls` 且 content 为空时，从原始元数据的 `reasoning_content` 字段提取推理链，用 `<thought>...</thought>` 包裹后写入 WAL。恢复重放时 Agent 的推理上下文不会丢失。
  - `AgentExecutor.dispatchLlmInference()`: 新增 `reasoning_content` 解析逻辑
  - 跳过完全空的 assistant 消息（无 content + 无 tool_calls），避免 WAL 被无意义记录污染

- **所有 Agent 会话以 FINISHED Checkpoint 结束**: 之前所有会话的最后 Checkpoint 都是 `ACTING`（来自 onToolReturn）或 `ERROR`（来自 circuit breaker），无法区分正常完成与异常中断。
  - `AgentExecutor.loopBody()`: 新增 early_finish / normal_finish 路径的 FINISHED Checkpoint
  - `AgentExecutor.reflect()`: 新增 finish_action / max_iterations 路径的 FINISHED Checkpoint
  - `CheckpointManager`: 修复幂等性检查 — 不同 `stateNode`（如 `ERROR → FINISHED`）即使 `lastAppliedMessageId` 相同也允许写入，确保最终状态不被覆盖

- **Checkpoint 变量快照丰富化**: 新增 `iteration`、`phase`、`reason`、`messageCount` 等字段，方便恢复时准确判断会话阶段

- **ExecutionCoordinator 读取 agent.max_iterations 配置**: 之前 `maxIterations` 始终使用默认值 10，导致复杂任务（如 TDD 多步工作流）在 circuit breaker 处中断。现在从 `~/.alice/config.json` 的 `agent.max_iterations` 读取配置。

### Features

- **Session ID 客户端透传**: 客户端可通过 `--session-id` 传入自定义会话 ID，沿 CLI → RunConfig → ExecutionCoordinator → AgentContext → WalSession 全链路透传，实现 WAL 恢复的可追溯性。
  - `RunConfig`: 新增 `sessionId` 字段、getter、builder
  - `CommandParser.RunCommand`: 新增 `--session-id` CLI 选项
  - `ExecutionCoordinator`: 使用客户端 sessionId 创建 `AgentContext`；WAL 目录改为确定性哈希 `Integer.toHexString(sessionId.hashCode() & 0xFFFF)`
  - `AgentExecutor`: 新增 `execute(String input, String sessionId)` 重载，支持程序化 API 传入
  - `RunConfig.toString()`: 新增 `sessionId` 输出

- **PromptMelter 双轨上下文熔炼**: 将 WAL + Checkpoint 双轨数据熔炼为三段式 LLM Prompt（静态主干区 + 快照状态区 + 极短消息尾部），最大化 DeepSeek Disk Prompt Cache 命中率。
  - `PromptMelter.melt(sessionId, staticTrunk)`: 三段拼接入口
  - `buildSnapshotState(checkpoint)`: 从 Checkpoint 变量快照生成结构化状态摘要
  - `buildShortTail(messages, rounds)`: 提取最近 N 轮纯文本对话，剥离 tool_calls
  - `MeltedPrompt.cacheKey()`: 返回 `"prompt:{lastAppliedId}"` 格式的缓存键

- **文档更新**: `docs/alice-memory-vault/AWL&CheckPoint.md` 全面重写，新增：
  - WAL 消息格式规范（角色定义、JSON 示例、设计要点）
  - 存储布局（`~/.alice/wal/<sessionIdHash>/`）
  - Checkpoint 安全边界触发映射表（PPAO 各阶段 → stateNode）
  - PromptMelter context2prompt 三段式熔炼说明
  - 消息链路校验 API
  - 代码入口速查表

### Smoke Tests

- **PMTEV 冒烟测试全通过**: 修复后 5 个测试用例全部通过。
  - `e2e/smoke/config.py`: 默认模型 `deepseek-chat` → `deepseek-v4-flash`（与 ModelEnum 对齐）
  - Case 1（基础工具编辑）: PASS
  - Case 2（跨文件重构）: PASS
  - Case 3（TDD 自省闭环）: PASS

## 20260622

### Features

- **/compact 命令端到端实现**: 完整的上下文压缩流程 — WAL 消息读取 → LLM 摘要生成 → 紧凑摘要写入 WAL。
  - `RawMessage`: 新增 `"compact"` 角色到 `VALID_ROLES`（`system`, `user`, `assistant`, `tool`, `compact`），新增 `compact()` 工厂方法
  - `WalEpisodicVault`: 新增 `case "compact" -> "compact_summary"` 消息类型映射
  - `WalSession`: 新增 `compact(sessionId, content)` 便捷方法 — 创建 `RawMessage.compact` 并通过 `store.appendMessage()` 写入
  - `Agent.compactContext()`: 实现完整流程 — (1) 从 WAL 获取全部消息, (2) 过滤 `system` + 已有 `compact`, (3) 组装 LLM 摘要提示词（含工具调用详情）, (4) 调用 `ModelProvider.dispatch()` 生成摘要, (5) 通过 `WalSession.compact()` 写入, (6) 在长期记忆中记录时间戳
  - 摘要提示词使用中文，保留工具调用上下文（函数名 + 参数）
  - `AgentFacadeSpec.groovy`: 测试从旧桩输出 (`"compactContext returns result string"`) 更新为真实行为 (`"compactContext returns failure when WAL not injected"`)

- **WAL 注入到 CLI/TUI 入口点**: 所有 facade 入口点现在都创建 `WalSession`（通过 `FileWalStore`）并通过 `agent.withWal(wal)` 注入。
  - `AliceTuiLauncher`: TUI 启动时创建 WAL
  - `JLineChatSession`: CLI 聊天模式创建 WAL
  - `ExecutionCoordinator`: CLI 运行模式创建 WAL
  - `Agent.withWal(WalSession)`: 新增便捷方法委托给 `executor.withWal(wal)`
  - 两个 facade 模块的 `build.gradle` 添加 `implementation project(':alice-memory-vault')`
  - 两个 facade 模块的 `module-info.java` 添加 `requires alice.agent.alice.memory.vault.main`

### Fixes

- **DefaultMemorySummarizer 空安全**: 修复 `extractSuccessPatterns()` 和 `extractFacts()` 中 `step.input()` / `step.output()` 可能为 null 时导致的 NPE 风险

- **e2e/smoke/parser.py 烟雾测试夹具修复**: `parse_payload()` 添加 `try/except json.JSONDecodeError` 处理非法 JSON 输入，使 `test_payload_parsing_broken` 测试通过（返回 `{"error": "invalid"}` 而非崩溃）

## 20260620

### Features

- **alice-tool-gateway/BuiltinTools 9 工具全线实现**: 完成全部 9 个内置工具（`read_file`, `write_file`, `grep`, `run`, `list_dir`, `file_exists`, `search_file`, `remove_file`, `web_search`），覆盖本地文件操作、Shell 执行、目录遍历、Web 搜索。

### Features

- **alice-tool-gateway/BuiltinTools 9 工具全线实现**: 完成全部 9 个内置工具（`read_file`, `write_file`, `grep`, `run`, `list_dir`, `file_exists`, `search_file`, `remove_file`, `web_search`），覆盖本地文件操作、Shell 执行、目录遍历、Web 搜索。
  - 新增 `list_dir` 工具 — 列出目录内容（目录标记 `/`），4 条测试 ✅
  - 新增 `file_exists` 工具 — 检查文件/目录是否存在，4 条测试 ✅
  - 新增 `search_file` 工具 — glob 模式递归搜索文件，4 条测试 ✅
  - 新增 `remove_file` 工具 — 安全删除文件（拒绝删除目录），4 条测试 ✅
  - 新增 `web_search` 工具 — DuckDuckGo API 实时搜索（无需 API key），3+2 条测试 ✅
  - 单元测试共计 38 条（2 条 @IgnoreIf 跳过网络）
  - `web_search` 的网络验证由 hole test TGW-P07 执行

### Tests

- **hole test 统一迁移: 8 模块全部从 Spock 委托升级为独立 JavaExec 边界探测**:
  - 所有 Python `hole_test_*.py` 脚本不再调用 `./gradlew :module:test`，改为调用 JavaExec task `runHoleTest`，直接执行 `src/hole/java/` 中的 Java `main()` 方法
  - 每个 probe 对应一个 `static void testXxx()` 方法，无 JUnit/Spock 依赖，纯标准输出断言
  - 共 43 个 hole probes 覆盖 8 个模块:
    - `alice-core-planner` (7: PLN-P01~P07)
    - `alice-model` (5: MDL-P01~P05)
    - `alice-tool-gateway` (9: TGW-P01~P09)
    - `alice-memory-vault` (5: MEM-P01~P05)
    - `alice-guardrail` (5: GRD-P01~P05)
    - `alice-bootstrap` (3: BTS-P01~P03)
    - `alice-core-agent` (4: AGT-P01~P04)
    - `alice-env-adapter` (5: ENV-P01~P05)

- **Windows subprocess 死锁修复**: `e2e/helpers.py` 中 `run_gradle()` 从 `capture_output=True` 改为 `stdout=PIPE + stderr=STDOUT`，避免 Gradle WARNING 日志填满 stderr 管道导致 deadlock。所有 8 个 `hole_test_*.py` 同步从 `result.stderr` 改为 `result.stdout`。

- **TGW 单次 Gradle 调用**: `BuiltinToolsHoleTest.java` 新增 `"all"` 入口键，一次运行全部 8 个 probe（TGW-P01~P09 除 web_search 因需网络参数单独测试），避免多次 `gradlew` 调用导致 Windows 下 Gradle daemon 崩溃 (exit code 3221225794)。Python 脚本也重写为单次 Gradle 调用。

### Docs

- 新增 `docs/alice-tool-gateway/META_TOOLS.md` — 内置工具集完整分层架构（4 层全景）
- 新增 `docs/alice-tool-gateway/inbound.md` — 入站端口设计文档
- 更新 `docs/alice-agent-command/e2e/case-tool-gateway.md`
- 更新 `docs/alice-tool-gateway/e2e/scene-tool-gateway-endpoints.md`
- 更新 `docs/alice-tool-gateway/e2e/hole_test_tool_gateway.py`
- 更新 `docs/alice-bootstrap/e2e/hole_test_bootstrap.py`
- 更新 `docs/alice-core-agent/e2e/hole_test_core_agent.py`
- 更新 `docs/alice-core-planner/e2e/hole_test_planner.py`
- 更新 `docs/alice-env-adapter/e2e/hole_test_env.py`
- 更新 `docs/alice-guardrail/e2e/hole_test_guardrail.py`
- 更新 `docs/alice-memory-vault/e2e/hole_test_memory.py`
- 更新 `docs/alice-model/e2e/hole_test_model.py`

## 20260619

### Features

- **alice-bootstrap/SPI 外观发现系统**: 将 Facade 选择从硬编码枚举重构为 SPI (ServiceLoader) 模式。
  - 新增 `AliceFacade` SPI 接口（`name()` + `launch(String[])`），位于 `alice-bootstrap` 模块的 `spi` 包
  - `FacadeSelector.launch()` 现通过 `ServiceLoader.load(AliceFacade.class)` 在运行时发现 facade 实现
  - 支持 `--facade <name>` 参数显式选择外观，保留 `--tui`/`-t` 和 `--cli`/`-c` 向后兼容
  - 未找到匹配 facade 时输出完整使用帮助
  - `alice-bootstrap` 的 `module-info.java` 移除对具体 facade 模块的 `requires`，改为 `uses org.cland.alice.agent.spi.AliceFacade`
  - `build.gradle` 移除硬编码 facade 子模块依赖
  - 新增 facade 模块仅需：实现 `AliceFacade` → 注册 `META-INF/services` → 声明 `provides` → 通过 `--facade <name>` 启动，无需修改 bootstrap

- **alice-facade-cmd, alice-facade-tui/SPI 实现**: 两个 facade 模块各自实现 `AliceFacade` SPI 接口并注册。
  - `AliceCliFacade`（name: `"cli"`）— 委托 `AliceCliLauncher.run()`
  - `AliceTuiFacade`（name: `"tui"`）— 委托 `AliceTuiLauncher.launch()`
  - 各模块 `module-info.java` 新增 `provides org.cland.alice.agent.spi.AliceFacade with ...`
  - 各模块 `build.gradle` 新增 `implementation project(':alice-bootstrap')`

- **docs: 统一 Build & Run 文档**: 新增 `docs/alice-bootstrap/BUILD.md` 主构建指南，补充 `docs/alice-facade-cmd/BUILD.md` 和 `docs/alice-facade-web/BUILD.md` 模块构建笔记。
  - 涵盖 CLI/TUI/Web 三种外观的运行方式、分发打包、SPI 发现原理、添加新 facade 的步骤
  - 统一 YAML 前端块格式

### Tests

- **E2E 测试重构: 结构化分层 + 诚实断言**: 全面重写 E2E 测试，按模块/访问路径结构化组织：
  - 按模块分层：`docs/alice-facade-cmd/e2e/test_cli_categories.py`（CLI 测试）、`e2e/test_dispatch.py`（dispatch 测试）、`docs/alice-facade-tui/e2e/test_slash_commands.py`（TUI 测试）
  - 按执行路径分类：META（tools/config/--help，无 PPAO）39 测试 → AGENT（run/routine/sub-agent，PPAO 生命周期）→ DISPATCH（chat 斜杠命令，JLine 文档化跳过）
  - 诚实断言重写：所有测试从"不崩溃"升级为验证真实行为（RunConfig 字段值、PPAO 生命周期、退出码语义、错误消息内容）
  - 真实 bug 发现：`RunConfig.toString()` 缺失 8 个子 Agent 字段（`subAgentConnectEndpoint`、`subAgentList`、`subAgentCancelId`、`subAgentResultsId`、`subAgentSendId`、`subAgentSendMessage`、`subAgentPromptAgentId`、`subAgentPromptText`）— 数据已正确存储但不可见。修复后所有子 Agent 选项在控制台日志中可见
  - 删除冗余：移除 7 个重复的每模块测试文件、3 个旧场景文件、3 个调试脚本，合并为 3 个结构清晰的顶级测试文件。`e2e/alice_agent_e2e.py` 重命名为 `e2e/helpers.py` 作为共享基础设施
  - 测试统计：CLI 39 测试（27 可运行 + 12 文档化跳过）、dispatch 21 测试（全跳过，参考 CLI 测试）、TUI 30 测试（全跳过，JLine 依赖）。201 单元测试通过，BUILD SUCCESSFUL

### Fixes

- **alice-facade-cmd/RunConfig.toString() 缺失 8 个子 Agent 字段**: `toString()` 仅包含了 `subAgentSpawnGoal` 和 `subAgentConnectName`，缺少 `subAgentConnectEndpoint`、`subAgentList`、`subAgentCancelId`、`subAgentResultsId`、`subAgentSendId`、`subAgentSendMessage`、`subAgentPromptAgentId`、`subAgentPromptText`。修复后控制台 `RunConfig` 日志完整显示所有子 Agent 字段。

- **alice-core-agent/AgentExecutor — PPAO 循环永不终止 (5 处 Bug)**: 修复 Agent 在 LLM 成功返回后进入无限推理循环的问题。详见 `docs/case/infinite-loop-ppao.md`。
  - `dispatchLlmInference` 成功路径: `Continue(Action.finish())` → `Finish(content, msg)` —— `shouldFinish()` 只认 `result instanceof Finish`，`Continue(FINISH)` 语义上永远不触发终止
  - `dispatchLlmInference` 失败路径: `Continue(revision)` → `Failure(msg)` —— LLM 调用失败不可恢复，不应进入 Revision 重试
  - `dispatchToolCall` 失败路径: `Continue(revision)` → `Failure(msg)` —— 同上，工具调用失败直接熔断
  - `verifyPost`: 新增 `Finish/Failure` 短路检查 —— 终态结果不再经过审计管线，立即设置 `Phase.FINISH`
  - `reflect`: 新增 `Phase.FINISH` 短路返回 —— `reflect` 不再将已设置的 `FINISH` 相位回退为 `REFLECTING`
  - `loopBody` 递归: 新增 `ctx.incrementIteration()` —— 每轮 Macro 迭代递增计数器，确保 `isMaxIterationsReached()` 兜底熔断生效

- **alice-facade-tui/TUI v2.0 布局重构**: 基于 `docs/alice-facade-tui/Layout.md` v2.0 重写 TUI 布局，减少固定行数并融合分割线。
  - **合并顶部分割线到 Header**: Header 行自带 ANSI 暗色 `─` 延伸到 `[Session: xxx]` 标签，移除独立的上方分隔行，FIXED_ROWS 从 6 → 5
  - **HeaderComponent**: 新增 `sessionLabel` 字段 + setter/getter；右侧对齐 `[Session: xxx]`；格式为 `🤖 alice-agent v0.1.0 ───────────── [Session: xxx]`
  - **FooterComponent**: ANSI 256 色分级渲染——橙色 `#214m` 费用、蓝色 `#75m` 速率、绿色 `#118m` 模型、紫色 `#141m` 工具；新增 `stripAnsi()` helper 精确计算宽度
  - **TuiLayout**: 移除顶部分隔行；`separator1Row` = content-input 分界，`separator2Row` = input-footer 分界；使用 ANSI dim `\033[38;5;242m` 渲染分割线
  - **ScreenManager**: 全部 `terminal.puts(InfoCmp.Capability.*)` 替换为直接 ANSI 转义码写入 `terminal.writer()`；新增 `COMPLETION_LIST_MAX=3` 常量锁定补丁菜单边界
  - **固定输入框补全列表硬限 3 行**: `reader.setVariable(LineReader.LIST_MAX, 3)` 防止布局偏移

- **alice-bootstrap/GraalVM Native Image 构建链路**: 完整支持 `nativeCompile` 任务，解决 Windows 原生二进制 TUI 运行问题。
  - 升级 `org.graalvm.buildtools.native` 插件至 `0.11.5`
  - 添加 JANSI `2.4.1` + JNA `5.14.0` 显式依赖（GraalVM AOT 必需）
  - 构建参数：`--initialize-at-run-time` 推迟 Netty/Vert.x/JANSI/JNA 初始化到运行时
  - `--enable-native-access=ALL-UNNAMED` + `--add-opens=java.base/java.lang=ALL-UNNAMED`
  - 反射安全调用 `AnsiConsole.systemInstall()`（`Class.forName` 动态加载）
  - 设置 `-Dsun.stdout.encoding=UTF-8`、`-Dsun.stderr.encoding=UTF-8` 固化编码

- **CI 流水线（`.github/workflows/ci.yml`）**: 新增并行 `native-image` 任务。
  - `build` 任务：Temurin 25 + `cache: gradle` → `assemble` → `test` → `installDist` → 上传 JVM 分发包
  - `native-image` 任务：GraalVM 25 → `gu install native-image` → `nativeCompile` → 上传原生二进制
  - 触发条件：PR 到 `main` + push 到 `main`/`develop`

- **alice-facade-tui/HeaderComponent 可见宽度计算**: 修复因未考虑 ANSI 转义码字节长度导致的 `─` 分隔线不能占满终端宽度的问题。padding 和 truncation 现在基于可见字符计数而非字符串字节长度。
- **alice-facade-tui/FooterComponent 截断逻辑**: 修复 `plain.length() > width` 时错误截断 ANSI 码导致颜色泄漏的问题。改为逐字符遍历，仅计数可见字符，完整保留 ANSI 色码。
- **alice-facade-tui/AliceTuiLauncher JANSI 编译错误**: 移除在 `try-catch` 块中对 `org.fusesource.jansi.AnsiConsole` 的硬编码引用，改用 `Class.forName()` 反射调用，消除编译期依赖缺失错误。
- **alice-bootstrap/native-image 自定义配置**: 移除格式错误的 `jni-config.json`/`reflect-config.json`（对象格式应为数组），依赖插件 `generateResourcesConfigFile` 自动生成。
- **alice-facade-tui/ScreenManager 输入光标位置错位**: 在 `runInputLoop()` 中每次 `reader.readLine()` 前添加 `\033[J`（ANSI 清除光标到屏幕底端）和 `reader.setVariable(LineReader.LINE_OFFSET, layout.inputRow())`，同步 JLine 内部光标跟踪与布局计算的实际输入行位置，解决光标显示行与输入区不匹配的问题。

### CI

- **`.github/workflows/ci.yml` parallel native-image build**: 新建 `native-image` 并行 job，避免所有构建强制走 GraalVM，分开缓存和构建环境。

## 20260618

### Features

- **alice-model/ModelConfigLoader — 模型配置加载系统**: 实现 `docs/alice-model/CONFIG.md` 规范的配置加载器，支持从 `~/.alice/model.json` 读取 `openai_compatible` 提供商配置。
  - 解析 `language_models.openai_compatible` 下任意数量的提供商（openai, deepseek, local 等）
  - 展开 `${ENV_VAR}` 环境变量引用（如 `${DEEPSEEK_API_KEY}`）
  - 校验规则：`api_url` 必须以 `http://` 或 `https://` 开头、`max_tokens >= max_output_tokens`、无模型的提供商自动跳过
  - `registerTo(ModelProvider)` 按名称自动创建适配器：`openai`/`deepseek`/未知 → `OpenAiSupplier`、`gemma4`/`gemma` → `Gemma4Supplier`
  - 自包含 JSON 解析器，零外部 JSON 库依赖
  - 12 个 Spock 测试覆盖全部路径（`ModelConfigLoaderSpec.groovy`）

- **alice-model/DeepSeek (OpenAI-compatible) 集成**: DeepSeek API 与 OpenAI Chat Completion 协议完全兼容，故直接复用 `OpenAiSupplier` 即可。
  - `ModelConfigLoader` 自动识别 `deepseek` 提供商并创建 `OpenAiSupplier(name, apiKey, "https://api.deepseek.com/v1/chat/completions")`
  - `AliceCliLauncher.initializeModelProvider()`: 新增 `DEEPSEEK_API_KEY` 环境变量注册（环境变量优先级低于配置文件）
  - `AliceTuiLauncher.launch()`: 新增 `ModelConfigLoader` 加载 + `DEEPSEEK_API_KEY` 降级注册
  - E2E 验证：`run 'Say hello' --model deepseek-v4-flash` → `Hello!` (1 次迭代)

- **docs/e2e 测试文档**: 新增 CLI e2e 测试文档。
  - `docs/alice-facade-cmd/e2e/case.md`: 4 个测试用例（基础推理/数值推理/默认模型/中文输入），含预期结果和实测结果
  - `docs/alice-facade-cmd/e2e/README.md`: 完整测试指南（4 种测试方式/供应商矩阵/PPAO 终止验证/配置/检查清单）
  - `docs/case/infinite-loop-ppao.md`: PPAO 无限循环故障案例文档

### Fixes

- **alice-core-agent/AgentExecutor — PPAO 循环永不终止 (5 处 Bug)**: 修复 Agent 在 LLM 成功返回后进入无限推理循环的问题。详见 `docs/case/infinite-loop-ppao.md`。
  - `dispatchLlmInference` 成功路径: `Continue(Action.finish())` → `Finish(content, msg)` —— `shouldFinish()` 只认 `result instanceof Finish`，`Continue(FINISH)` 语义上永远不触发终止
  - `dispatchLlmInference` 失败路径: `Continue(revision)` → `Failure(msg)` —— LLM 调用失败不可恢复，不应进入 Revision 重试
  - `dispatchToolCall` 失败路径: `Continue(revision)` → `Failure(msg)` —— 同上，工具调用失败直接熔断
  - `verifyPost`: 新增 `Finish/Failure` 短路检查 —— 终态结果不再经过审计管线，立即设置 `Phase.FINISH`
  - `reflect`: 新增 `Phase.FINISH` 短路返回 —— `reflect` 不再将已设置的 `FINISH` 相位回退为 `REFLECTING`
  - `loopBody` 递归: 新增 `ctx.incrementIteration()` —— 每轮 Macro 迭代递增计数器，确保 `isMaxIterationsReached()` 兜底熔断生效

- **alice-facade-tui/TUI v2.0 布局重构**: 基于 `docs/alice-facade-tui/Layout.md` v2.0 重写 TUI 布局，减少固定行数并融合分割线。
  - **合并顶部分割线到 Header**: Header 行自带 ANSI 暗色 `─` 延伸到 `[Session: xxx]` 标签，移除独立的上方分隔行，FIXED_ROWS 从 6 → 5
  - **HeaderComponent**: 新增 `sessionLabel` 字段 + setter/getter；右侧对齐 `[Session: xxx]`；格式为 `🤖 alice-agent v0.1.0 ───────────── [Session: xxx]`
  - **FooterComponent**: ANSI 256 色分级渲染——橙色 `#214m` 费用、蓝色 `#75m` 速率、绿色 `#118m` 模型、紫色 `#141m` 工具；新增 `stripAnsi()` helper 精确计算宽度
  - **TuiLayout**: 移除顶部分隔行；`separator1Row` = content-input 分界，`separator2Row` = input-footer 分界；使用 ANSI dim `\033[38;5;242m` 渲染分割线
  - **ScreenManager**: 全部 `terminal.puts(InfoCmp.Capability.*)` 替换为直接 ANSI 转义码写入 `terminal.writer()`；新增 `COMPLETION_LIST_MAX=3` 常量锁定补丁菜单边界
  - **固定输入框补全列表硬限 3 行**: `reader.setVariable(LineReader.LIST_MAX, 3)` 防止布局偏移

- **alice-bootstrap/GraalVM Native Image 构建链路**: 完整支持 `nativeCompile` 任务，解决 Windows 原生二进制 TUI 运行问题。
  - 升级 `org.graalvm.buildtools.native` 插件至 `0.11.5`
  - 添加 JANSI `2.4.1` + JNA `5.14.0` 显式依赖（GraalVM AOT 必需）
  - 构建参数：`--initialize-at-run-time` 推迟 Netty/Vert.x/JANSI/JNA 初始化到运行时
  - `--enable-native-access=ALL-UNNAMED` + `--add-opens=java.base/java.lang=ALL-UNNAMED`
  - 反射安全调用 `AnsiConsole.systemInstall()`（`Class.forName` 动态加载）
  - 设置 `-Dsun.stdout.encoding=UTF-8`、`-Dsun.stderr.encoding=UTF-8` 固化编码

- **CI 流水线（`.github/workflows/ci.yml`）**: 新增并行 `native-image` 任务。
  - `build` 任务：Temurin 25 + `cache: gradle` → `assemble` → `test` → `installDist` → 上传 JVM 分发包
  - `native-image` 任务：GraalVM 25 → `gu install native-image` → `nativeCompile` → 上传原生二进制
  - 触发条件：PR 到 `main` + push 到 `main`/`develop`

### Fixes

- **alice-facade-tui/HeaderComponent 可见宽度计算**: 修复因未考虑 ANSI 转义码字节长度导致的 `─` 分隔线不能占满终端宽度的问题。padding 和 truncation 现在基于可见字符计数而非字符串字节长度。
- **alice-facade-tui/FooterComponent 截断逻辑**: 修复 `plain.length() > width` 时错误截断 ANSI 码导致颜色泄漏的问题。改为逐字符遍历，仅计数可见字符，完整保留 ANSI 色码。
- **alice-facade-tui/AliceTuiLauncher JANSI 编译错误**: 移除在 `try-catch` 块中对 `org.fusesource.jansi.AnsiConsole` 的硬编码引用，改用 `Class.forName()` 反射调用，消除编译期依赖缺失错误。
- **alice-bootstrap/native-image 自定义配置**: 移除格式错误的 `jni-config.json`/`reflect-config.json`（对象格式应为数组），依赖插件 `generateResourcesConfigFile` 自动生成。
- **alice-facade-tui/ScreenManager 输入光标位置错位**: 在 `runInputLoop()` 中每次 `reader.readLine()` 前添加 `\033[J`（ANSI 清除光标到屏幕底端）和 `reader.setVariable(LineReader.LINE_OFFSET, layout.inputRow())`，同步 JLine 内部光标跟踪与布局计算的实际输入行位置，解决光标显示行与输入区不匹配的问题。

### CI

- **`.github/workflows/ci.yml` parallel native-image build**: 新建 `native-image` 并行 job，避免所有构建强制走 GraalVM，分开缓存和构建环境。

---

## 20260617

### Features

- **alice-facade-cmd/AliceConfigStore nested provider config**: 重构配置存储以支持混合存储——3+ 段键（`providers.openai.api_key`）存储为嵌套 JSON 对象，已知 2 段键和单段键保持扁平下划线格式（`default_timeout`）。新增 `splitKey()` / `resolvePath()` / `putNested()` / `removePath()` / `deepCopy()` 方法。155 测试全部通过。

- **alice-facade-cmd/Config & Tools subcommands fully implemented**: `tools` 和 `config` 子命令从桩代码（exit 1）升级为完整实现。`config` 子命令支持 `get`/`set`/list；`tools` 子命令通过 `ToolRegistryHolder` 列出已注册工具，支持 `--detail` 参数。

- **docs: Configuration system documented**: 新增 `docs/config/README.md`（配置系统设计）、`config.json`（系统设置示例）、`model.json`（Provider 嵌套配置示例）、`example.yaml`（键参考）。`docs/alice-facade-cmd/cmd.md`（CLI 命令参考）完整记录全部 6 个子命令、chat 斜杠命令、退出码、数据流。

- **docs/alice-facade-tui/tui.md**: New TUI slash command reference, covering all 14 commands, three-layer single-line layout, keyboard shortcuts, command classification (INTERNAL/IO/SYSTEM/CONFIG), and comparison with CLI.

### Fixes

- **alice-facade-cmd/CliRoot**: 修复 `@Command` 注解缺少 `subcommands` 属性导致 `routine` 和 `sub-agent` 子命令不可解析的问题。重构为使用 `@Command(subcommands = {...})` 单一注册方式。

- **alice-facade-tui/TuiLayout separator encoding**: `SEPARATOR_CHAR` 从 Unicode `\u2500` (`─`) 改为 ASCII `-`，解决 Windows GBK 终端下 box-drawing 字符渲染为 `€鈹€鈹€` 乱码的问题。

- **alice-facade-tui/ScreenManager ANSI escape leak**: 所有 `terminal.puts(InfoCmp.Capability.*)` 调用替换为直接 ANSI escape code 写入（`\033[row;colH`），解决 Windows 终端下光标定位序列被当作文本输出的问题。

- **alice-facade-tui/Chinese encoding**: `TerminalBuilder` 显式设置 `.encoding(StandardCharsets.UTF_8)`，`AliceTuiLauncher.main()` 设置 `file.encoding` / `sun.stdout.encoding` 系统属性为 UTF-8，解决 Windows GBK 终端中文乱码。

### Docs

- **docs/alice-facade-tui/Layout.md**: 更新补全描述——从"向上顶出选择抽屉"修正为"输入框内补全列表"（JLine 3 AUTO_MENU 行为），分割线字符从 `───` 改为 `-`。
- **docs/config/README.md**: 转换为 ai-doc 格式（YAML 前端块），更新为反映实际代码的混合扁平/嵌套存储格式。
- **docs/config/model.json**: 从扁平键（`openai_api_key`）改为实际嵌套结构（`providers.openai.api_key`）。

## 20260615

### Fixes

- **alice-memory-vault/JVectorSemanticVault: 修复 3 处过时 API**:
  - `GraphIndexBuilder.addGraphNode(int, RandomAccessVectorValues)` → `addGraphNode(int, VectorFloat<?>)` — 直接传入 `vectorList.get(localId)` 避免使用已过时的 `ravv` 重载
  - `GraphIndex.size()` → `vectorList.isEmpty()` — 用本地列表判断空索引，绕开已过时的 `size()`
  - `RandomAccessVectorValues.getVector()` 保留实现并添加 `@SuppressWarnings("deprecation")`（因接口方法为 abstract 必须实现）；移除同样已过时的 `vectorValue()` 重复 override
- **alice-core-agent/AgentExecutor: 修复已过时的 `ToolRegistry.execute()`** → `ExecutionEngine.invoke()`:
  - 构造器内预构建 `ExecutionEngine` 实例（来自 `agent.toolRegistry()`）
  - `dispatchToolCall()` 中调用 `executionEngine.invoke()` 获取结构化 `ToolResult` 替代旧 boolean 返回值
  - 错误信息纳入 `ToolResult.summary()` 增强可读性
- **过时API.md**: 新增过时 API 跟踪文件，记录所有修复详情
- **alice-facade-tui/AliceTuiLauncher: 修复启动时 bootstrap 日志污染 TUI 显示**:
  - 移除 `start()` 中 `logger.info("Starting Alice Agent TUI...")` 调用——该日志在 `screenManager.start()` 清屏前写入 stdout，导致引导日志残留在 TUI 缓冲区中
  - 移除 `run()` 中 `logger.info("Alice Agent TUI entering main input loop.")` 调用——TUI 已激活后 stdout 日志会直接打印到终端区域，干扰 LineReader 显示
  - 所有用户可见信息改由 `eventBridge.onChatMessage()` → `ThoughtComponent` 在 TUI 滚动区内展示

### Features

- **alice-core-agent, alice-agent-command, alice-facade-cmd, alice-facade-tui: /sub-agent — Multi-Agent via ACP Protocol (Phase 3-7)** — 实现子 Agent 完整生命周期：
  - **US1 (Spawn)**: 真实 `Agent` 实例创建与独立的 ReAct 循环执行；异步完成通知回调；SLF4J 日志记录
  - **US2 (ACP Connect)**: 新增 `AcpClientWrapper`（反射式 ACP SDK 调用，JPMS 兼容）、`AcpConnection`、`AcpClientException`；真实 WebSocket 连接与三阶段生命周期握手
  - **US3 (List/Cancel/Results)**: 完整 List/Cancel/Results 命令处理；ACP 连接关闭集成
  - **US4 (Message/Send)**: 消息队列（`LinkedBlockingQueue`）支持父子 Agent 双向通信；`sendToSubAgent`/`pollMessage`/`pendingMessageCount` API
  - ACP SDK 依赖 (`com.agentclientprotocol:acp-core:0.9.0`) 加入 `alice-core-agent/build.gradle`
  - CLI dispatch (`AliceCliLauncher`): 所有 7 个 SubAgentCmd 分支完整 switch case
  - TUI dispatch (`AliceTuiLauncher`): 所有 7 个 SubAgentCmd 分支完整 switch case + handler 方法
  - TUI 斜杠命令: `/sub-agent` 注册（`SlashCommand` + `CommandHandler`）

### Dependencies

- **alice-core-agent**: 新增 `com.agentclientprotocol:acp-core:0.9.0` — ACP Java SDK for external agent integration

### Features

- **alice-model: Anthropic Claude 适配器** — 实现 `ClaudeSupplier`，支持 Anthropic Messages API（v1/messages）：
  - `ClaudeSupplier(apiKey)` 构造 — 默认端点 `https://api.anthropic.com/v1/messages`
  - 三态生命周期：构建 Anthropic 格式请求体 → HTTP POST → 解析响应
  - 支持 `temperature`、`system` 等 Anthropic 参数
  - 支持 `stop_reason: "tool_use"` 检测（metadata 标记）
  - Token usage 提取（`input_tokens`/`output_tokens`）
  - 通过 `ANTHROPIC_API_KEY` 环境变量自动注册（`AliceAgent` + `AliceCliLauncher`）
  - 新增 12 个 Spock 测试覆盖全部路径（`ClaudeSupplierSpec.groovy`）

## 20260614

### Changes

- **alice-memory-vault: Offline Dreaming Engine** — 完整实现记忆梦境引擎（Dreaming Engine）的 7 个阶段 35 项任务：
  - 新增 `dreaming/` 包，包含 12 个生产类：`DreamingTriggerConfig`、`DreamingSession`、`DreamingFact`、`CrystallizedPattern`、`SessionState`、`StateTransitionException`、`PromptMelter`、`Crystallizer`、`ConflictResolver`、`SessionStateManager`、`DreamingEngine`、`WalSessionReadGuard`
  - PromptMelter: 离线 WAL 日志 → 事件总结（噪声滤波 + 空间聚类）
  - Crystallizer: 滑动窗口 Tool-Call 语义重复检测 → SOP 结晶（3 次重复触发阈值）
  - ConflictResolver: 基于时间戳的知识冲突消解（新事实优先 + 旧知识 DEPRECATED 标记 + 并行修改 MANUAL_REVIEW 标记）
  - SessionStateManager: 6 状态状态机（CREATED → RUNNING → COMPLETED → DREAMING → ARCHIVED），`ConcurrentHashMap.replace()` CAS 原子锁定
  - DreamingEngine: 三级管道编排（PromptMelter → ConflictResolver → Crystallizer）→ Vault 写入，支持 on-demand / batch / background 三种触发模式
  - WalSessionReadGuard: DREAMING 状态 READ-lock 强制（禁止在线 ReAct 读取正在被 Dreaming 处理的会话）
  - 新增 9 个 Spock 测试文件（70 测试点），全模块 222 测试通过
  - 设计文档：`docs/alice-memory-vault/dreaming/FunctionArch.md`、`SystemArch.md`

- **alice-agent-command, alice-facade-cmd, alice-facade-tui: Routine-Time Command Model Update** — 新增第五个密封分支 `RoutineTimeCmd` 表示定时调度：
  - `alice-agent-command`: 新增 `RoutineTimeCmd.java`（sealed interface + `RegisterRoutineCmd`/`TimeTriggeredCmd` records），更新 `AgentCommand.permits` 和 `AgentCommand.parse()` 对 `/routine` 的支持
  - `alice-facade-cmd`: 新增 `RoutineCommand` picocli 子命令，`RunConfig` 增加 `routineCron`/`listRoutines` 字段
  - `alice-facade-tui`: 新增 `/routine` 斜杠命令（`Type.CONFIG`），`CommandHandler.handleConfig()` 分发 `RegisterRoutineCmd`
  - 新增 3 个 Spock 测试文件 + 更新 5 个现有测试文件，共 65+ 新增测试点，全模块编译通过

- **docs: update DESIGN.md for Routine-Time command model** — 将常规调度驱动（Routine-Time）融合至密封指令层次设计，新增类图分支、用例映射表和定时触发时序流程。

### Tests

- **alice-facade-tui, alice-bootstrap, alice-facade-cmd: 补齐全部指令分发测试** — 完成 TODO-alice-agent-command.md §3.1/§3.2 所有测试项：
  - `TuiSpec.groovy`: 新增 11 个测试 — SlashCommand 解析 `/context`/`/compact`/`/feedback` `toAgentCommand`；CommandHandler 分发 `/context`/`/compact`/`/clear`/`/model`/`/feedback`；AliceTuiLauncher.dispatchAgentCommand() 覆盖全部 6 种新指令 + null guard
  - `CommandDispatchLoopSpec.groovy`: 新增 `/routine` CLI 分发验证 + 加入完整链路清单
  - `AliceCliLauncher.java`: 补充 `RoutineTimeCmd.RegisterRoutineCmd` 开关分支，修复 `/routine` CLI 路由返回 EXIT_PARAM_ERROR 的问题

- **alice-core-agent, alice-facade-cmd: 补齐 Facade 模块单元测试** — 完成 TODO-alice-facade.md §5.1 所有测试项：
  - `AgentFacadeSpec.groovy`: 新增 9 个测试 — `getActiveContext()` Markdown 表格格式输出、`clearMemory()`/`compactContext()`/`switchModel()`/`injectFeedback()` 异常安全、`feedback()` 默认 null 返回
  - `JLineChatSessionSpec.groovy`: 新增 5 个测试 — 自然语言与斜杠命令解析验证、dispatch 分发验证、close 幂等性验证

### Features

- **alice-memory-vault: EpisodicVault 基于 WAL 重构** — 新增 `WalEpisodicVault`，将情景记忆重构为 WAL 的查询视图：
  - `getTrace()` 通过 WAL 全量回放重建 Step 列表
  - `getRecentSteps()` 基于差量读取
  - 保留重要度遗忘策略（与 InMemoryEpisodicVault 一致行为）
  - 17 个单元测试覆盖全路径

- **alice-memory-vault: JVectorSemanticVault — JVector 4.x 嵌入式向量搜索引擎**：
  - 每个 Collection 独立 OnHeapGraphIndex（HNSW + COSINE 相似度）
  - 增量添加/搜索/标记删除，零外部运行时依赖
  - 文本通过 FNV 哈希投影为 128 维 L2 归一化向量
  - 18 个单元测试覆盖存储、检索、隔离、删除全路径

- **alice-memory-vault: WAL 压缩清理引擎** — 新增 `WalCompactor`，后台线程异步压缩：
  - 基于 Checkpoint lastAppliedMessageId 精确截断（会话内消息遍历）
  - minRetentionCount 保护：保留最近 N 条消息，防止过度压缩
  - 安全保证：绝不删除未确认（lastAppliedId 之后）的消息
  - 支持自动调度（可配置间隔）和手动触发
  - 18 个单元测试覆盖基础压缩、多会话、幂等、生命周期全路径
  - FileWalStore 写入吞吐: ~1100 msg/s（单条）、~1250 msg/s（批量）
  - InMemoryWalStore 基线: ~16500 msg/s（Groovy Spock 环境，不含 JIT 预热）
  - Checkpoint 保存延迟: ~1.6ms（FileWalStore）、~0.5ms（InMemoryWalStore）
  - 恢复耗时: 1000 条重建 ~53ms、10000 条重建 < 3s

### Tests

- **alice-memory-vault: 模拟崩溃恢复 E2E 测试** — 新增 `CrashRecoveryE2ESpec.groovy`（5 个测试）：
  - 基本崩溃恢复：WAL 写入 → 模拟崩溃 → 新会话恢复 → 验证消息完整性
  - 多轮工具调用链恢复：确保 tool_call → tool_result 配对完整
  - 用户中断穿插场景：中断后切换任务的正确恢复
  - 空会话恢复：FRESH_START
  - 无 Checkpoint 全量回放

## 20260614

### Docs

- **docs(memory): add implementation overview and JVector tech notes** — `docs/memory/README.md`
  - Vault components table (Episodic/Semantic/Procedural)
  - WAL subsystem components
  - JVectorSemanticVault design decisions and pitfalls (VectorFloat instantiation, node ID continuity, dimension selection)

## 20260613

### Changes

- **docs: standardize documentation structure across all modules**
  - Add `docs/DOC_SPEC.md` defining YAML front-matter header standard with `title`, `summary`, `read_when`, `scope`, `status`, `updated` fields
  - Add `read_when` + `summary` YAML headers to all 35 project `.md` files for AI agent consumption
  - Move TODO files (`TODO-*.md`) to `todos/` directory for cleaner project root
  - Rewrite `AGENTS.md` and `CONTRIBUTING.md` with actual Alice Agent project info (replaced stale Temporal SDK templates)
  - Standardize all `docs/*/DESIGN.md` files with consistent YAML headers
  - Create `create-ai-doc` skill at `~/.agents/skills/create-ai-doc/` with `SKILL.md` + `references/DOC_SPEC.md`
  - Add `bin/` to `.gitignore` for Gradle build artifacts

### Docs

- **AGENTS.md**: Full rewrite — module layout table, Java 25/Spock/Gradle build commands, key source file cross-reference, TODO tracking, review checklist
- **CONTRIBUTING.md**: Full rewrite — Java 25+ dev environment, module dependency graph, PR checklist, commit prefix table, code style guidelines
- **docs/DOC_SPEC.md**: New documentation specification defining YAML front-matter header standard for all project `.md` files

## 20260612

### Changes

- **alice-facade-tui/Lanterna → JLine 3 重构**: 基于 `docs/alice-facade-tui/Layout.md` 三层单线分割布局（TAO Standard Mode）重写 TUI 模块，从 Lanterna 迁移至 JLine 3。
  - **布局重写**: 移除旧版 box-drawing 四层边框布局（`┌─┐│└─┘`），实现三条统一单线 `───` 划分的三大固定区域：上方滚动区 + 中间输入区 + 底部状态栏。
  - **ScreenManager 重写**: 替换 Lanterna `Screen`/`TextGraphics`/`KeyStroke` 为 JLine 3 `Terminal` + `LineReader`。增量渲染通过 `terminal.puts(cursor_address)` 仅重绘变更行，零闪烁。
  - **LineReader AUTO_MENU**: 使用 JLine 3 原生 `AUTO_MENU` + `StringsCompleter` 实现 `/model` 命令向上顶出补全弹窗，选中项回车即销毁，分割线与状态栏完全静止。
  - **HeaderComponent 精简**: 移除 model/status 显示，仅保留 agent 名称+版本（`alice v0.1.0`）。
  - **FooterComponent 重写**: 改为底部计费状态栏，显示 Cost/Speed/Model/Active Tool 四项核心指标（💰📊🧠🔌 图标）。
  - **ThoughtComponent 合并**: 合并旧 ChatComponent + ThoughtComponent 为单一滚动日志区，前缀格式 `[T Thought]`/`[A Action]`/`[O Observe]`/`[User]`/`[System]`。
  - **InputComponent 精简**: 仅维护输入缓冲区模型，实际 I/O 委托给 JLine 3 LineReader。
  - **Component 基类重构**: 移除 Lanterna `draw(TextGraphics)` 依赖，新增 `render() → List<String>` 抽象方法，添加公共 row/col/width/height getter。
  - **自动构建适配**: 更新 `module-info.java`（`requires org.jline.reader` + `requires org.jline.terminal`）及 `build.gradle`（jline-terminal/reader/builtins 三件套）。
  - **终端 resize**: 使用 JLine 3 `Signal.WINCH` 替代 Lanterna `addResizeListener`。
  - **删除文件**: `ChatComponent.java` 功能合并至 ThoughtComponent。
- **alice-facade-tui/HeaderComponent 精简**: 移除 model/status 显示，`setModel()`/`setStatus()`/`modelId()` 方法删除。模型信息仅保留在底部 FooterComponent 状态栏。相关调用（AliceTuiLauncher/ScreenManager）同步更新为引用 `footer().setModel()`/`footer().modelInfo()`。

### Fixes

- **alice-core-agent/Agent.java**: 修复 `getActiveContext()`、`clearMemory()`、`compactContext()` 中 `sessionId` 变量未定义导致的编译错误。新增 `private final String sessionId` 字段并在构造器中初始化 UUID。

- **alice-agent-command 指令模块分发补齐**: 完成 TUI/CLI 分发路由、SlashCommand 枚举、CommandHandler、Agent 核心接口、AgentExecutor HITL 支持的全链路补齐。
  - **SlashCommand 枚举补齐**: 注册 `/context`、`/compact`、`/feedback` 三条新斜杠命令及其帮助文本。
  - **CommandHandler 命令执行补齐**: `handleInternal()` 增加 `/context` → ViewContextCmd、`/compact` → CompactContextCmd、`/feedback` → FeedbackCmd 的分发转换。
  - **AliceTuiLauncher.dispatchAgentCommand()**: 添加 ClearContextCmd、ViewContextCmd、CompactContextCmd、FeedbackCmd、SwitchModelCmd 五个新分支的完整处理逻辑（含 UI 联动、EventBridge 输出、Agent 核心调用）。
  - **AliceCliLauncher.dispatchCommand()**: 添加 ClearContextCmd、ViewContextCmd、CompactContextCmd 三个新分支的 CLI 输出。
  - **Agent 核心接口补齐**: 新增 `getActiveContext()`（Markdown 表格格式）、`clearMemory()`（短期记忆清空）、`compactContext()`（写入 WAL/Checkpoint 替代）、`switchModel(modelId)`、`injectFeedback(feedback)` / `feedback()` 五个方法。
  - **AgentExecutor HITL 支持**: 新增 `suspendForHuman()`（CompletableFuture 挂起）、`resumeWithFeedback(String)`（唤醒恢复）、`isSuspendedForHuman()` 查询方法。
  - **文档同步**: 更新 `docs/alice-agent-command/DESIGN.md` 类图补齐 ClearContextCmd/ViewContextCmd/CompactContextCmd，新增 `/clear`、`/context`、`/compact`、`/feedback` 时序图。<｜end▁of▁thinking｜>**Section 3: Testing & Section 4.1: TODO-spec.md update** — updating the task file.

Now let me update the TODO task board to mark completed items:

<｜｜DSML｜｜tool_calls>
<｜｜DSML｜｜invoke name="read">
<｜｜DSML｜｜parameter name="path" string="true">E:\work\chaineasy-ai\alice-agent\TODO-alice-agent-command.md

- **alice-memory-vault/WAL + Checkpoint 双轨制记忆系统**: 实现预写日志（WAL）与控制流快照（Checkpoint）双轨持久化子系统，基于设计文档 `AWL&CheckPoint.md`。
  - **数据模型**: `RawMessage`（OpenAI 兼容消息实体，含 role/content/tool_calls）、`ToolCall`（工具调用实体）、`Checkpoint`（控制流快照，含 state_node/variable_snapshot/plan_snapshot）
  - **WAL 运行时**: `WalStore` 存储接口 + `InMemoryWalStore` 实现、`WalAppender`（流式追加 + 严格有序 + 批量刷盘）
  - **Checkpoint 管理器**: `CheckpointManager`（5 个安全边界触发器：onReActCycleEnd/onUserInput/onToolReturn/onError/熔断，幂等性保证）、`RecoveryEngine`（崩溃恢复：加载最新 Checkpoint → 脏 WAL 差量重放 → 生成新快照）
  - **上下文熔炼**: `PromptMelter`（三段式 Prompt 组装：静态主干 + 快照状态 + 短消息尾部，含 cacheKey 缓存支持）
  - **集成门面**: `WalSession`（统一包装 WalAppender + CheckpointManager）、`module-info` 导出 `org.cland.alice.memory.wal` 包
  - **测试**: 6 个 Spock Spec 共 63 个测试用例，覆盖实体校验、存储读写、Checkpoint 触发与幂等、恢复重放（clean/dirty/full）、Prompt 熔炼、集成门面全链路
  - **文档**: 新增 `docs/alice-memory-vault/AWL&CheckPoint.md` 设计文档
- **TODO 跟踪文件**: 新增 `TODO-alice-agent-command.md`、`TODO-alice-facade.md`、`TODO-memory-vault.md`、`TODO-spec.md` 四个 GFM 格式任务看板，track 当前开发阶段 backlog。
- **README/TECH_STACK**: 更新 README 添加 project structure/tech stack 章节，新增 `TECH_STACK.md`（JLine 3）。

### Tests

- alice-bootstrap/CommandDispatchLoopSpec: 新增 bootstrap → facade → cmd 完整分发链路测试（249 行），覆盖 FacadeSelector 选型、AliceCliLauncher.dispatchCommand()、AliceTuiLauncher.dispatchAgentCommand() 对全部 12 种 AgentCommand 子类型的路由验证。

### Fixes

- alice-core-agent/AgentExecutor: 修复 `dispatchLlmInference` 和 `dispatchToolCall` 在异常时返回 `Continue(revision)` 导致 Micro-ReAct 无限循环的问题。异常（如 `No supplier found for modelId`）现直接返回 `Failure` 熔断退出循环，避免反复重试同一失败模型。(#loop-termination)

---

## 20260608

### Changes

- **alice-agent-command/新增三条上下文指令**: 在 `ControlCmd` 密封体系下新增三个上下文管理指令，完善 Agent 指令集（共 12 种具体子类型）。
  - `ClearContextCmd`（/clear）— 清空 Session 的 M (Memory) 缓存，保留 System Prompt/Rules，重置 Token 计数器
  - `ViewContextCmd`（/context）— 从 M (Memory) 拉取当前滑动窗口内的线索、对话历史及 Token 占用统计
  - `CompactContextCmd`（/compact）— 强制触发 M (Memory) 总结机制，通过 LLM 将历史对话提炼为 Summary 事实快照，释放 Context Window
  - 三个新指令均为 `sealed record`，继承 `ControlCmd`，遵循 `reason()`、`sessionId`、`traceId`、`timestamp` 契约
  - `AgentCommand.parse()` 已扩展三条新 switch 分支
  - 测试更新：`ControlCmdSpec` (+9 tests)、`AgentCommandParseSpec` (+6 tests)、`AgentCommandSealedHierarchySpec` (12 subtypes)
  - 对应设计文档：`docs/alice-agent-command/v0.0.1.md`

## 20260607

### Changes

- **e2e/gemma4/新增 E2E 测试套件**: 针对本地 Gemma4 模型 API（http://192.168.1.14:10303/v1）创建完整的端到端 Python 测试套件。
  - `gemma_4_client.py` — 轻量级 OpenAI 兼容客户端，支持流式与非流式调用
  - `gemma4_e2e_test.py` — 10 个测试用例覆盖：基础对话、多轮上下文、日语、流式、System Prompt、Token 用量、错误处理、延迟验证
  - `run_e2e.py` / `run_e2e.sh` / `run_e2e.bat` — 跨平台运行脚本
  - `requirements.txt` / `pyproject.toml` — 依赖管理
  - 通过环境变量 `GEMMA4_BASE_URL`、`GEMMA4_MODEL`、`GEMMA4_TIMEOUT` 灵活配置
  - 测试包含服务健康检查，未启动时自动跳过（SkipTest）

## 20260529

### Changes

- **alice-memory-vault/命名规约重构**: 移除 vault 接口的 `I` 前缀（`IEpisodicVault` → `EpisodicVault`），实现类重命名为 `InMemory*` 前缀（`EpisodicVault` → `InMemoryEpisodicVault`，`SemanticVault` → `InMemorySemanticVault`，`ProceduralVault` → `InMemoryProceduralVault`）。
  - `VaultController` 和 `MemoryRouter` 依赖纯接口（`EpisodicVault`、`SemanticVault`、`ProceduralVault`）而非具体实现
  - 所有测试更新为使用新的 `InMemory*` 实现类名
  - 全模块编译通过（85/85 测试通过）

## 20260525

### Changes

- **alice-agent-command/抽象指令层**：新增 `alice-agent-command` 模块，定义 `AgentCommand` 密封接口体系（对应 `docs/app/AgentCommand.md` 类图）。
  - `AgentCommand` 顶层密封接口，含 `parse()` 工厂方法，支持自然语言 → AcquireGoalCmd 自动映射
  - `ExecutionCmd` 任务驱动：`AcquireGoalCmd`（/run）和 `ExecuteRawCmd`（/exec）
  - `CapabilityCmd` 能力装载：`RegisterSkillCmd`（/skill）、`UpdateRulesCmd`（/rules）、`ReloadKernelCmd`（/reload）
  - `AlignmentCmd` 运行配置：`SwitchModelCmd`（/model）
  - `ControlCmd` 控制与反馈：`ResetSessionCmd`（/new）、`FeedbackCmd`（/feedback）、`InterruptCmd`（/exit/Ctrl+C）
  - 每条指令为 `sealed record`，携带 `sessionId`、`traceId`、`timestamp` 实现全链路追踪
  - 模块 `alice.agent.command.main`，暴露单个导出包 `org.cland.alice.agent.command`
- **alice-facade-cmd/AgentCommand 集成**：`CommandParser` 新增 `parseToAgentCommand()` 和 `RunCommand.toAgentCommand()` 方法；`AliceCliLauncher` 新增 `dispatchCommand()` 静态方法，通过 `AgentCommand.parse()` 将用户输入路由到完整密封 switch 分支。
- **alice-facade-tui/AgentCommand 集成**：`SlashCommand` 新增 `toAgentCommand()` 转换方法；`CommandHandler` 新增 `onAgentCommand` 回调分发机制，IO/System/Config 命令执行后统一转化为 AgentCommand 派发给 Agent 核心；`AliceTuiLauncher` 新增 `dispatchAgentCommand()` 模式匹配分发器，覆盖 9 种具体子类型的路由处理。
- **alice-core-agent/AgentSession import 修复**：修复 `Agent.java` 中 `import org.cland.alice.memory.AgentSession` 为正确的子包路径 `org.cland.alice.memory.agent.AgentSession`，解决前次 flat-package 重构导致的编译错误。
- **alice-agent-command/测试**：新增 56 个 Spock 测试用例，覆盖：
  - `AgentCommandParseSpec` (17 tests): 自然语言与全部 `/` 斜杠命令解析、null/空输入、未知命令、时间戳、toString
  - `ExecutionCmdSpec` (8 tests): 构造/null 拒绝/自定义时间戳/密封约束/record 相等性
  - `CapabilityCmdSpec` (6 tests): 构造/null 拒绝/密封约束/record 相等性
  - `AlignmentCmdSpec` (6 tests): 构造/null 拒绝/密封约束/record 相等性
  - `ControlCmdSpec` (7 tests): 构造/null 拒绝/reason 格式化/密封约束/record 相等性
  - `AgentCommandSealedHierarchySpec` (5 tests): instanceof 分类、跨分支排他性、全部 9 种具体类型确认

### Build

- settings.gradle: 新增 `include('alice-agent-command')`
- alice-agent-command/build.gradle: 新增 `groovy` 和 `java-library` 插件、Spock 测试依赖
- alice-facade-cmd/build.gradle: 新增 `implementation project(':alice-agent-command')`
- alice-facade-tui/build.gradle: 新增 `implementation project(':alice-agent-command')`
- app/build.gradle: 新增 `implementation project(':alice-agent-command')`
- 所有相关 module-info.java 添加 `requires alice.agent.command.main`

## 20260524

### Changes

- alice-core-agent/AgentCore 合并: 将 `AgentCore` 的所有字段（`plannerService`、`guardrail`、`toolRegistry`、`memory`、`envAdapter`）、DI 方法（`withPlannerService()`、`withGuardrail()`、`withToolRegistry()`、`withMemory()`、`withEnvAdapter()`）、getter 及验证钩子（`verifyPre()`、`verifyPost()`、`shouldFinish()`）完整合并到 `Agent` 类中。`Agent` 现为自包含的公共 API，不再持有 `AgentCore` 实例。(#agent-core-merge)
- alice-core-agent/AgentCore 废弃: `AgentCore` 转换为 `@Deprecated` 委托包装器，所有方法委托给内部 `Agent` 实例，保持对外部调用处的向后兼容。构造方法 `AgentCore(String, AgentConfig)` 保持包级可见。(#agent-core-merge)
- alice-core-agent/AgentExecutor: 关键字段从 `AgentCore` 改为 `Agent`；构造参数更新为 `Agent`；全部 17 处内部引用从 `agentCore.xxx()` 迁移为 `agent.xxx()`。(#agent-core-merge)
- alice-core-agent/Agent.shouldFinish: 新增 `context.currentPhase() == Phase.FINISH` 检查，确保 PPAO 循环在 FINISH 阶段也能通过 `shouldFinish(ctx, null)` 正确终止，修复空结果递归死循环。(#loop-termination)
- alice-core-agent/AgentContext.stateMachine: `ACTING` 状态新增到 `FINISH` 的合法转换，`OBSERVING` 状态新增到 `FINISH` 的合法转换，支持致命错误时的优雅终止。(#state-machine)
- alice-core-agent/AgentContext.transitionTo: 新增自身状态转换幂等检查（如 `PLANNING → PLANNING` 不会抛异常），简化 Micro-ReAct 自循环的逻辑。(#state-machine)
- alice-core-agent/AgentExecutor.actWithMicroReAct(FINISH): 修复 FINISH 分支未设置 `ctx.put("result", ...)` 导致 `agent.ask()` 回退调用 `callLlmDirect()` 触发 `No supplier found` 异常的问题。(#finish-result)
- alice-core-agent/AgentExecutor.handleFatalError: 修复 NPE 时 `error.getMessage()` 返回 null 导致的二次 NPE（`ConcurrentHashMap.putVal` 拒绝 null value）。(#fatal-error)
- alice-facade-tui/EventBridge: import/字段/方法参数从 `AgentCore` 迁移为 `Agent`。(#agent-core-merge)
- app/AliceAgent: 移除已不存在的 `agent.agentCore()` 空值检查。(#agent-core-merge)
- alice-core-agent/AgentPpaoLoopSpec: 新增 45 个 Spock 测试用例，使用 mock StrategySelector（Spock Stub）替代真实 LLM 调用，覆盖 PPAO 循环的 FINISH/REVISION/OBSERVE 路径、verify 钩子、状态转换、AgentCore 向后兼容、多 Agent 隔离、配置访问、边界条件。(#ppaotest)
- **alice-env-adapter/模块重构**: `alice-env-adapter` 的 Tool、ToolResult、Resource、ResourceResult 四个 MCP 协议数据模型从 `env.adapter.model` 包迁移至 `alice-tool-gateway.model` 包，成为全系统通用的抽象类型定义。`alice-env-adapter` 通过 `implementation project(':alice-tool-gateway')` 依赖网关模块，ModuleInfo 添加 `requires alice.agent.alice.tool.gateway.main`。删除原有的 `model` 包，消除重复定义。(#tool-gateway-model)
- **alice-tool-gateway/model**: 新增 `model` 包（`org.cland.alice.tool.gateway.model`），内含 Tool（工具描述符）、ToolResult（工具调用结果）、Resource（资源描述符）、ResourceResult（资源读取结果）四个通用抽象类型。`module-info.java` 新增 `exports`/`opens` 指令。(#tool-gateway-model)
- **alice-env-adapter/测试**: 新增 108 个 Spock 测试用例，覆盖：
  - `McpClientSpec` (22 tests): MCP 协议握手、工具/资源发现、工具调用（成功/错误/isError）、资源读取、订阅、通知转发、属性管理、生命周期状态转换、Stdio/SSE 传输层构造、网关模型类型使用验证
  - `EnvManagerSpec` (18 tests): 客户端连接/断开、去重拒绝、工具执行（tool call / resource read）、前置快照捕获、回滚、提交、执行失败自动回滚、事件监听器（连接/执行）、多客户端工具聚合、shutdown
  - `SnapshotManagerSpec` (16 tests): 保存/检索、LIFO 回滚（空/有历史）、按 ID 回滚、commit（空/有历史）、maxHistorySize 驱逐/校验、clear、diff（资源变更/文件变更/不可逆副作用/无变化/null）
  - `EnvSnapshotSpec` (11 tests): 空快照、全字段构建、addIrreversibleEffect、null 校验、不可变 map、副作用（含补偿/默认时间/非空 action）、toString
  - `EnvStateSpec` (4 tests): canExecute、isTerminal、isTransitional、全部枚举值
  - `EnvEventSpec` (7 tests): 构造/默认时间/null 数据/不可变/null 类型/toString/全部事件类型
  - `FakeTransportSpec` (8 tests): 连接/断开/failOnConnect/响应路由/消息记录/failOnSend/未连接发送/通知监听器/动态 handler
  - `FakeMcpTransport`（测试基础设施）: 内存假传输层，支持静态/动态响应 handler、消息记录、连接/发送失败模拟、通知模拟
  - (#env-adapter-tests)

### Fixes

- alice-core-agent/AgentExecutor.FINISH: 调用 `ctx.put("result", action.target())` 确保 `agent.ask()` 能从上下文中读取执行结果。(#finish-result)
- alice-core-agent/AgentContext.transitionTo: 自身状态转换时不抛异常，Micro-ReAct 自循环不再触发 `IllegalStateException`。(#state-machine)
- alice-core-agent/AgentContext.transitionTo: `ACTING → FINISH` 与 `OBSERVING → FINISH` 加入合法转换表，防止 `handleFatalError` 中抛 `Invalid phase transition`。(#state-machine)
- alice-core-agent/Agent.shouldFinish: 新增 `Phase.FINISH` 阶段检查，避免 PPAO 循环在已到达 FINISH 阶段后仍通过递归继续执行。(#loop-termination)

## 20260519

### Changes

- alice-tool-gateway/SandboxProviderSpec: 新增 **Workspace Op File** 测试套件（8 个 Spock 测试用例），覆盖 @TempDir 工作区内文件操作的沙箱验证：FileArgHolder 路径白名单校验、ShellCommand 文件参数校验、子目录访问、路径穿越拦截、多文件参数支持。(#workspace-op)
- alice-tool-gateway/SandboxProviderSpec: 修复 4 处 Spock 编译错误——`thrown()` 异常条件移至 `then:` 块、`and:` 块中移除非法异常断言。修复 6 处运行时 `MissingMethodException`——将 Groovy map 交集类型强制转换 (`as FileArgHolder & Callable`) 替换为具体静态内部辅助类 (`FileArgHolderCallable`、`ShellCommandCallable`)，消除 JDK 动态代理对 Groovy `&` 交集类型的不兼容问题。(#sandbox-tests)

## 20260517

### Breaking Changes

- `AgentCore/planner` 字段: 移除 `ReAct planner` 字段，仅保留 `PlannerService plannerService`。`planner()` getter 变更为 `plannerService()`。`withPlanner(ReAct)` 方法移除。
- `AgentExecutor` 规划接口: 从 `agentCore.planner().reason()` 变更为 `agentCore.plannerService().plan()`，`PlannerService` 是唯一的规划入口。
- `alice-core-planner`: 完全移除 `ReAct` 和 `ReActContext`。规划器模块 (`alice-core-planner`) 不再包含循环模板代码，专注于决策引擎职责（`PlannerService`、策略、MCTS 树）。
- `ReAct.proposeNext()`: 移除旧方法，所有规划通过 `PlannerService.plan()` 进行。
- `PlannerServiceSpec.groovy`: 移除旧 `ReAct` 类的向后兼容测试用例，更新集成测试直接构造 `PlannerService`（依赖 `ModelSession`、`StrategySelector`）。

### Changes

- `alice-core-agent/lifecycle/ReAct`: 新增 `@FunctionalInterface` 循环模板，定义 `reason(Map)` 单抽象方法 + 默认 `loop()` 模板（Reason→Act→Observe→...→FINISH）。提供 `from(PlannerService)` 适配器。
- `alice-core-agent/lifecycle/ReActContext`: 新增循环运行时上下文，跟踪迭代次数、token 消耗、行动历史。
- `alice-core-agent/AgentExecutor`: `act()` 阶段重构为 `actWithMicroReAct()`，嵌入 Micro-ReAct 战术循环（Dispatch→Observe→Reason→loop）。新增 `dispatchLlmInference()`、`dispatchToolCall()`、`microReActLoop()`、`microReActStep()`、`planToIntent()` 方法。
- `alice-core-agent/AgentContext.Phase`: 状态机支持 `ACTING → ACTING` 自循环，允许 Micro-ReAct 循环保持在 ACTING 阶段内迭代。
- `alice-core-agent/AgentCore`: 新增 `plannerService` 字段 + `withPlannerService(PlannerService)` 注入方法。
- `alice-core-planner/package-info`: 更新文档描述，移除 ReAct 相关内容，阐明规划器模块仅聚焦决策引擎。

### Fixes

- 无

## 20260516

### Changes

- alice-model/模型注册：新增 `GEMMA_4` 模型枚举注册，支持 `gemma-4` 模型 ID 查找和 `fromModelId()` 路由。(#15)
- alice-model/供应商：新增 `Gemma4Supplier` 实现 `ModelSupplier` 接口，对接 OpenAI 兼容的本地 Gemma-4 API（`http://192.168.1.14:10303/v1`），支持 Chat Completion（含 `tool_calls` 功能调用）与 SSE 流式响应。(#15)
- docs: 新增 `docs/alice-model/models/gemma4/gemma4.http` HTTP API 测试文件，含 Simple Chat、Tool Calling、Streaming 三项冒烟测试，全部通过。(#15)

- 日志系统：全模块从 `System.Logger` / `System.getLogger()` 迁移至 **SLF4J 2.0 + Logback 1.5** 工业级日志方案。
- 日志/模块依赖：所有 10 个子模块的 `build.gradle` 统一添加 SLF4J API、Logback Classic、Logback Core 实现依赖。
- 日志/模块系统：所有 10 个子模块的 `module-info.java` 统一添加 `requires org.slf4j` 和 `requires ch.qos.logback.classic`。
- 日志/Java 源文件：28 个 Java 文件完成迁移，`System.Logger` 声明全部替换为 `LoggerFactory.getLogger()`，`logger.log(Level.XXX, ...)` 替换为 `logger.info/warn/error/debug()`，`{0}/{1}` 占位符替换为 `{}`。
- 日志/配置文件：新增 `app/src/main/resources/logback.xml`，强制 UTF-8 编码输出，配置控制台日志（带 `[thread]` `%level` 格式）和按天滚动文件日志（30 天保留），彻底根治乱码问题。
- 日志/编译验证：全模块 `compileJava` 通过，无编译错误。***(#14)

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
- alice-facade-cmd/AliceCliLauncher：将 `run(String[])` 访问级别从 package-private 改为 `public`，允许 app 模块 Orchestrator 调用。
- alice-facade-tui/TUI 自动退出：修复 `AliceTuiLauncher.run()` 中 EOF 被错误视为退出信号的问题。EOF 不再触发退出，TUI 仅通过 `/exit` 命令、`Ctrl+Q` 或 `F10` 退出。
- alice-facade-tui/重绘布局：根据 `docs/alice-facade-tui/DESIGN.md` §7.1 布局规范重写 UI 渲染。新增 box-drawing 边框（`┌─┐│└─┘`），Header 改为单行 `┌─ title ─ Model ─ Status ─┐`，Chat/Thought 面板使用完整边框，Input 区改为 `┌> ` 提示符，移除多余的 StatusComponent。
- alice-facade-tui/状态栏移除：删除已废弃的 `StatusComponent`，状态信息合并至 Header 组件显示。
- alice-facade-tui/双重关闭：修复 `AliceTuiLauncher.shutdown()` 被 `run()` finally 块和 `close()` 重复调用的问题，新增 `shutdown` 守卫标志。
- alice-facade-tui/Escape 键：修复按下 Escape 键导致 TUI 立即退出的问题，改为仅清空输入框内容。
- alice-facade-tui/无用导入清理：移除 `AliceTuiLauncher` 中未使用的 `AgentContext`、`Action`、`StepResult`、`EnvEvent`、`TimeUnit` 导入。

## 20260505

### Changes

- app/App 模块（Bootstrapper & Orchestrator）：基于设计文档(`docs/app/DESIGN.md`) 完整实现引导程序与外观选择器架构。
- app/AliceApp：新增 JVM 入口点（对应 §2.2 AliceApp），负责 JVM 级初始化（日志、ShutdownHook、环境变量检查），委托 `AliceAgent.bootstrap()` 启动完整生命周期，退出码约定：0 正常 / 1 运行时错误 / 2 参数错误 / 130 中断。
- app/AliceAgent：重写为应用层 Orchestrator（对应 §2.2 AliceAgent），实现四阶段引导：Phase 1 ModelProvider 初始化 → Phase 2 Facade 选择 → Phase 3 AgentConfig 构建 → Phase 4 launch 阻塞执行；实现 `AutoCloseable` 生命周期管理。
- app/FacadeSelector：新增 Facade 决策逻辑（对应 §2.2 FacadeSelector），`detect(args)` 通过 `--tui`/`-t` 或 `--cli`/`-c` 标志检测模式（默认 CLI），`launch()` 委托 `AliceCliLauncher.run()` 或 `AliceTuiLauncher`。
- app/参数解析：`buildConfig()` 支持 `--model`/`--max-iterations`/`--timeout`/`--verbose`/`--debug`/`--no-pre-verify`/`--no-post-verify` 参数。
- app/参数过滤：`filterAppArgs()` 剥离 app 级参数后传递剩余参数给 CLI facade。
- app/module-info：新增 `exports org.cland.alice.agent`，添加 `requires alice.agent.facade.cmd.main` 和 `alice.agent.facade.tui.main`。
- app/build.gradle：新增 `implementation project(':alice-facade-cmd')` 和 `alice-facade-tui` 子模块依赖；`mainClass` 改为 `org.cland.alice.agent.AliceApp`。
- app/测试：更新 `AliceAgentSpec` 为 7 个 Spock 测试用例，覆盖版本号、实例化、Facade 检测模式（`--tui`/`-t`/`--cli`/默认）、退出码常量。

## 20260503

### Changes

- 项目初始化：创建多模块 Java 25 + Gradle 9.5 项目骨架，含 8 个子模块。
