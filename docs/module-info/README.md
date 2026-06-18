# Alice Agent Framework - 模块全景架构图

## 📐 模块链路关系全景图

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                  ALICE AGENT FRAMEWORK                                     │
│                                 模块依赖链路全景图                                          │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

                                              ▼
                                    ┌─────────────────────┐
                                    │   alice-bootstrap   │
                                    │     (启动引导层)     │
                                    │                     │
                                    │  • AliceApp        │
                                    │  • FacadeSelector  │
                                    └──────────┬──────────┘
                                               │ 依赖
                    ┌──────────────────────────┼──────────────────────────┐
                    │                          │                          │
                    ▼                          ▼                          ▼
          ┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
          │ alice-facade-cmd│      │ alice-facade-tui│      │ alice-facade-web│ 🆕
          │   (CLI 门面)    │      │   (TUI 门面)    │      │   (Web 门面)    │
          │                 │      │                 │      │                 │
          │  • 命令行交互    │      │  • 字符界面渲染  │      │  • RESTful API  │
          │  • JLine集成    │      │  • 键盘事件处理  │      │  • WebSocket    │
          │  • 文本/JSON输出│      │  • 分屏布局管理  │      │  • SSE 推送     │
          └────────┬────────┘      └────────┬────────┘      └────────┬────────┘
                   │                        │                        │
                   └────────────────────────┼────────────────────────┘
                                            │ 依赖
                                            ▼
                          ┌─────────────────────────────────┐
                          │        alice-core-agent         │
                          │         (核心代理层)             │
                          │                                 │
                          │  ┌───────────────────────────┐  │
                          │  │         Agent            │  │
                          │  │    (代理主体/门面)        │  │
                          │  └───────────┬───────────────┘  │
                          │              │                   │
                          │  ┌───────────┴───────────────┐  │
                          │  │      AgentExecutor        │  │
                          │  │    (执行器/协调器)         │  │
                          │  └───────────┬───────────────┘  │
                          │              │                   │
                          │  ┌───────────┴───────────────┐  │
                          │  │   ReAct / Lifecycle       │  │
                          │  │   (推理循环/生命周期)      │  │
                          │  └───────────────────────────┘  │
                          └──────────────┬──────────────────┘
                                         │ 依赖
              ┌──────────────────────────┼──────────────────────────┐
              │                          │                          │
              ▼                          ▼                          ▼
    ┌──────────────────┐    ┌─────────────────────┐    ┌──────────────────────┐
    │  alice-core-     │    │  alice-memory-vault │    │  alice-tool-gateway  │
    │  planner         │    │    (记忆保险库)      │    │    (工具网关)         │
    │  (规划引擎)      │    │                     │    │                      │
    │                  │    │  ┌───────────────┐  │    │  ┌────────────────┐  │
    │  • Strategy      │    │  │EpisodicVault │  │    │  │ ToolRegistry   │  │
    │    Selector      │    │  │ (情景记忆)    │  │    │  │ (工具注册)      │  │
    │  • Decision      │    │  ├───────────────┤  │    │  ├────────────────┤  │
    │    Strategy      │    │  │SemanticVault │  │    │  │ExecutionEngine │  │
    │  • FastPath      │    │  │ (语义记忆)    │  │    │  │ (执行引擎)      │  │
    │  • SlowPath      │    │  ├───────────────┤  │    │  ├────────────────┤  │
    │  • ThinkingTree  │    │  │ProceduralVault│  │    │  │SandboxProvider │  │
    │  • TokenBudget   │    │  │ (程序记忆)    │  │    │  │ (沙箱提供者)    │  │
    │  • ModelSession  │    │  ├───────────────┤  │    │  ├────────────────┤  │
    │  • SOP Registry  │    │  │   WAL         │  │    │  │SchemaGenerator │  │
    │                  │    │  │ (预写日志)    │  │    │  │ (Schema生成)   │  │
    │                  │    │  ├───────────────┤  │    │  └────────────────┘  │
    │                  │    │  │MemoryRouter  │  │    │                      │
    │                  │    │  │ (记忆路由)    │  │    │                      │
    │                  │    │  └───────────────┘  │    │                      │
    └────────┬─────────┘    └─────────┬───────────┘    └──────────┬───────────┘
             │                        │                           │
             └────────────────────────┼───────────────────────────┘
                                      │ 依赖
              ┌───────────────────────┼───────────────────────┐
              │                       │                       │
              ▼                       ▼                       ▼
    ┌─────────────────┐   ┌─────────────────────┐   ┌─────────────────────┐
    │  alice-model    │   │  alice-guardrail    │   │  alice-env-adapter  │
    │  (模型接入层)   │   │   (安全护栏)         │   │   (环境适配器)      │
    │                 │   │                     │   │                     │
    │  • ModelProvider│   │  • PreValidator    │   │  • McpClient       │
    │  • ModelSupplier│   │  • PostValidator   │   │  • McpTransport    │
    │  • Call/Status  │   │  • PolicyEngine    │   │  • SSE Transport   │
    │  • Gemini4      │   │  • Hallucination   │   │  • Stdio Transport │
    │  • OpenAI       │   │    Detector        │   │  • EnvManager      │
    │                 │   │  • Permission      │   │  • EnvSnapshot     │
    │                 │   │    Sandbox         │   │                     │
    │                 │   │  • RiskLevel       │   │                     │
    │                 │   │  • AuditResult     │   │                     │
    └─────────────────┘   └─────────────────────┘   └─────────────────────┘
```

---

## 📋 模块功能说明

### 第一层：启动引导层

| 模块 | 功能职责 | 核心类 |
|------|---------|--------|
| **alice-bootstrap** | 统一启动入口，根据参数选择门面类型，初始化 Agent 配置，管理应用生命周期 | `AliceApp`, `FacadeSelector` |

---

### 第二层：门面层 (Facade Layer)

| 模块 | 功能职责 | 交互方式 | 核心类 |
|------|---------|---------|--------|
| **alice-facade-cmd** | 命令行交互界面，支持非交互式脚本调用，输出文本或 JSON 格式结果 | 标准输入/输出、命令行参数 | `AliceCliLauncher`, `CommandParser`, `OutputRenderer` |
| **alice-facade-tui** | 终端字符界面，实时显示 Agent 思考过程，支持键盘快捷键操作 | 键盘事件、ANSI 终端渲染 | `AliceTuiLauncher`, `ScreenManager`, `EventBridge` |
| **alice-facade-web** 🆕 | Web 服务门面，暴露 RESTful API、WebSocket、SSE，提供浏览器 Chat UI 和 Dashboard | HTTP/WebSocket/SSE | `AliceWebLauncher`, `ChatController`, `AgentWebSocketHandler` |

---

### 第三层：核心代理层

| 模块 | 功能职责 | 子组件 | 核心类 |
|------|---------|--------|--------|
| **alice-core-agent** | Agent 核心执行引擎，管理 ReAct 循环、生命周期、执行上下文 | ReAct 推理循环、Lifecycle 生命周期管理、Action/Observation 事件 | `Agent`, `AgentExecutor`, `ReAct`, `Lifecycle` |

---

### 第四层：能力支撑层

| 模块 | 功能职责 | 子组件 | 核心类 |
|------|---------|--------|--------|
| **alice-core-planner** | 规划决策引擎，支持 Fast/Slow 双路径策略，维护思考树 | StrategySelector、FastPathStrategy、SlowPathStrategy、ThinkingTree、TokenBudget、SOPRegistry | `PlannerService`, `DecisionStrategy`, `ThinkingTree` |
| **alice-memory-vault** | 多维度记忆存储，支持 WAL 预写日志和会话恢复 | EpisodicVault(情景)、SemanticVault(语义)、ProceduralVault(程序)、WAL、MemoryRouter | `VaultController`, `AgentSession`, `WalSession`, `RecoveryEngine` |
| **alice-tool-gateway** | 工具注册发现与执行引擎，支持沙箱隔离 | ToolRegistry、ExecutionEngine、SandboxProvider、SchemaGenerator | `ToolRegistry`, `ExecutionEngine`, `ToolResult` |

---

### 第五层：基础服务层

| 模块 | 功能职责 | 子组件 | 核心类 |
|------|---------|--------|--------|
| **alice-model** | 多模型接入适配，统一调用接口 | ModelProvider、ModelSupplier、Gemini4Supplier、OpenAiSupplier | `Model`, `Call`, `ModelProvider` |
| **alice-guardrail** | 安全护栏，输入输出校验与审计 | PreValidator、PostValidator、PolicyEngine、HallucinationDetector、PermissionSandboxValidator | `GuardrailService`, `PolicyEngine`, `RiskLevel` |
| **alice-env-adapter** | 环境感知与 MCP 协议适配 | McpClient、McpTransport(SSE/Stdio)、EnvManager、SnapshotManager | `McpTransport`, `EnvManager`, `EnvSnapshot` |

---

## 🔗 依赖链路说明

```
启动引导层 (bootstrap)
    │
    ├──▶ 门面层 (facade-cmd / facade-tui / facade-web)  ──┐
    │                                                       │
    └───────────────────────────────────────────────────────▶│
                                                            ▼
                                              核心代理层 (core-agent)
                                                    │
                 ┌──────────────────────────────────┼──────────────────────────────────┐
                 │                                  │                                  │
                 ▼                                  ▼                                  ▼
        规划引擎 (planner)                  记忆保险库 (memory-vault)           工具网关 (tool-gateway)
                 │                                  │                                  │
                 └──────────────────────────────────┼──────────────────────────────────┘
                                                    │
                 ┌──────────────────────────────────┼──────────────────────────────────┐
                 │                                  │                                  │
                 ▼                                  ▼                                  ▼
           模型接入 (model)                   安全护栏 (guardrail)              环境适配 (env-adapter)
```

---

## 📊 模块依赖矩阵

| 模块 | 依赖模块 | 被依赖模块 |
|------|---------|-----------|
| alice-bootstrap | facade-cmd, facade-tui, facade-web | - |
| alice-facade-cmd | core-agent | bootstrap |
| alice-facade-tui | core-agent | bootstrap |
| alice-facade-web | core-agent | bootstrap |
| alice-core-agent | planner, memory-vault, tool-gateway | facade-cmd, facade-tui, facade-web |
| alice-core-planner | model, memory-vault | core-agent |
| alice-memory-vault | - | core-agent, core-planner, guardrail |
| alice-tool-gateway | guardrail, env-adapter | core-agent |
| alice-model | - | core-planner, guardrail |
| alice-guardrail | model, memory-vault | core-agent, tool-gateway |
| alice-env-adapter | - | tool-gateway |

---

## 🚀 各门面启动方式对比

| 门面 | 启动参数 | 入口类 | 适用场景 |
|------|---------|--------|---------|
| CLI | `--cli` | `AliceCliLauncher` | 脚本调用、自动化、CI/CD |
| TUI | `--tui` | `AliceTuiLauncher` | 本地开发、调试、运维终端 |
| Web | `--web` | `AliceWebLauncher` | 生产部署、团队协作、可视化监控 |
