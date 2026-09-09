---
title: "alice-core-agent — Kernel 架构与执行工作流设计草案 (Draft)"
summary: "三层架构（Facade/Agent/Kernel）与内核执行语义草案：内核的定义与关系、图语义执行工作流（会话图 + 战术子图展开）、plan 三职能拆解（写权复用 guardrail 权限体系、决策/仲裁落账）、执行契约接口、多级预算、文本 LLM pipeline 阶段模型、与现状代码的迁移对照。Draft 状态，供评审迭代。"
read_when:
  - "重构 AgentExecutor / 定义内核接口时"
  - "讨论执行工作流（图语义、战术子图展开、终止仲裁）时"
  - "设计文本 LLM pipeline / 动态 prompt 加载扩展点时"
scope:
  - "alice-core-agent"
status: "draft"
updated: "2026-09-09"
---

# Alice Agent — Kernel 架构与执行工作流设计草案

> **状态：DRAFT。** 本文是设计讨论的沉淀稿，不代表已实现。所有接口名、规则编号均为草案，
> 有待逐条评审。现状描述以当前代码（AgentExecutor 单体实现）为准。

## 1. 背景与动机

当前执行核心是 `AgentExecutor`（1903 行单体）：Macro PPAO 循环 + 内嵌 Micro-ReAct 全部揉在一个类里。
已知问题（详见 [loop-llm-interaction.md](./loop-llm-interaction.md) 第 7 节）：

| # | 问题 | 一句话 |
|---|---|---|
| P1 | Micro 的 `finish_reason=stop` 直接结束整个会话 | 战略层无"成功推进下一目标"回路 |
| P2 | `planToIntent()` 只消费 `plan.steps().get(0)` | 无多目标队列/游标 |
| P3 | `skipMicro=true` 一轮即终态 | Macro 循环不迭代 |
| P4 | `perceive()` 与每轮 `loopBody` 双计数 | maxIterations 实际少跑一轮 |
| P5 | REVISION 只回 PLANNING，不重新 Perceive | 多轮间上下文陈化 |
| P6 | `GuardrailToolProxy` 无实例化点 | 工具级守卫未生效 |
| P8 | 上下文每轮重建（actionLog 截尾），WAL 不回放 | 长工具链上下文稀释 |

**核心不满**：`AgentExecutor` 不是"内核"，而是一个**具体的 agent program 的耦合实例**——

1. 它知道 `finish_reason` / `tool_calls` / `enable_thinking`（模型协议细节）；
2. 它构建 tools schema、拼 prompt（提示词与应用知识混入）；
3. WAL / 事件 / guardrail / 线程模型全部写死；
4. 导致 Micro 逻辑在 `MicroReActEngine`（712 行，未接线死代码）中必须复制一份。

设计方向：**三层架构，内核 interface-first**。

## 2. 三层架构模型

```
┌────────────────────────────────────────────────────────────┐
│ L1 门面 (Facade) — 面向终端: TUI / CLI / Web / 子 agent 入口      │
│    命令解析、渲染、事件呈现、HITL 输入。                          │
│    只依赖 L2 业务接口，永不 import L3。                          │
├────────────────────────────────────────────────────────────┤
│ L2 Agent (功能本体) — 面向"能力所有者"                         │
│    ・持有核心能力集合: planner/tools/guardrail/memory/wal/      │
│      model/prompt-provider（注入+装配 = 组合根）               │
│    ・业务门面: ask/run/resume/compact/clear/cancel/feedback/   │
│      context —— 全部薄转发                                     │
│    ・会话/上下文所有权 (sessionId, AgentContext)                │
│    ・执行委托 L3，自身实现细节为零                              │
├────────────────────────────────────────────────────────────┤
│ L3 内核 (Kernel) — 面向"执行语义契约"                          │
│    定义接口 + 状态机 + 数据流转类型（先接口后实现）               │
│    实现可替换: 现 AgentExecutor = 一个实现（可拆/留作 legacy）    │
└────────────────────────────────────────────────────────────┘
```

**硬规则**：
1. 依赖单向 `Facade → Agent → Kernel`；`Kernel` 的 import 列表不允许出现任何策略实现类 / 模型供应商 / tool 具体类。
2. 语义与实现分离：`AgentExecutor` 中"语义决定"（何时终止/何时回退/状态迁移）必须能被接口描述；
   "怎么调模型/怎么拼 prompt/工具怎么跑"必须能被移出替换。
3. 所有权分明：生命周期=Agent 层，执行语义=内核层，呈现=Facade 层，内容与算法=策略层。

## 3. 内核定义：它管什么，不管什么

**一句话**：内核 = 承载 LLM-Agent 决策循环的执行基底。执行以**嵌套图 + 账本状态**表达：循环骨架与语义（决策→效果→观察→再决策，何时问、结果算什么、如何推进/回退/仲裁）和图的结构语义（战术=战略子图、复合节点展开、递归嵌套）在内核；"智能内容"（提示词、协议编解码、模型供应商、工具实现）在外。

> **评审反馈（2026-09-09）**：早期表述"内核 = 确定性执行基底；智能是跑在它上面的应用"被否。若 LLM 只被当作与工具同级的一个 effect、循环主体外置，内核会退化为哑循环驱动，P1/P2/P3/P5/P8 这类"循环决策归属"缺陷无法根治（详见 §8 D4）。同日二次评审追加两点：
> ① 循环不是"双层双环"，而是**图语义**——战术执行是战略节点的子图展开（§5）；
> ② "plan"不是一个阶段/一个对象，而是**三个层级的职能**（战略规划 / 执行步骤规划 / System-1 路由，§5.1）——本次评审把三者从压平的 `Plan` 中拆开。

### 3.1 内核管的九类"功能语义"

| 语义域 | 内核定义 | 反例（不属于内核） |
|---|---|---|
| **决策循环语义（核心）** | 战术子图（ReAct）与 STRATEGIZE 审慎决策都经统一 decision-loop 语义驱动：Decision 源（LLM/人）产出决策 → 推进图游标与账本 → 效果执行 → Observation 回流 → 再决策；内核决定"何时问、结果算什么、是否继续/如何回退/仲裁" | 提示词与上下文内容、工具 schema、模型协议细节、厂商词翻译 |
| **图与展开语义** | 执行结构是嵌套有向图：节点类型（decision/effect/gate/observe/terminal/composite）与边语义（guard/port/路由）由内核定；复合节点（STRATEGIZE、goal、ACT）可展开为子图——战术=战略子图；子 agent = 同契约递归图 | 子图内部装什么业务、用什么模型、节点内容 |
| 状态语义 | 账本（Ledger）是唯一状态真相：槽位（goal 图/route/artifact/预算/修订计数）与游标。**结构/仲裁槽位的变更只在内核的 decision/仲裁点发生**；内容/事务槽位（artifact、记忆、上下文片段）可被授权工具按 guardrail 权限体系写入（默认无，D11） | 槽位里装什么业务值 |
| 效果语义 | 效果调度、副作用记录、结果如何变观察；效果的写权限 = **复用 guardrail 工具权限体系**（默认无）：`PermissionSandboxValidator` 管外部资源 scope，槽位写由扩展 Validator 在 `GuardrailToolProxy`（P6 装配点）校验 | 工具名/参数含义/文件格式 |
| 终止语义 | 谁可结束什么（goal 判据提交→仲裁；会话终止→会话仲裁点）；预算/熔断/超时 | "何时算完成"的业务判据 |
| 验证语义 | 门在哪些图边被询问、拦截后流向哪里 | 校验规则内容 |
| 失败语义 | 失败→诊断→重试/回退/放弃的结构化路径（含每 goal 修订预算） | 具体错误含义 |
| 时间语义 | 超时点、挂起点、恢复点、WAL 事务边界 | 等待多久合适 |
| 并发/嵌套语义 | 会话可嵌套（子 agent=同契约图）、效果可并行、取消传播 | 子任务做什么 |

### 3.2 内核的六件事

1. **状态账本（Ledger）** — 已发生(trace)/在哪(图游标、goal 游标)/信什么(context、槽位快照)。账本由内核写入（append/游标推进/预算扣减等规则不可被绕过）；结构/仲裁槽位仅在 **decision/仲裁点**变更；效果的写在 effect 边界经 **GuardrailToolProxy** 校验（默认无写，D11）。
2. **图结构与展开（Graph/Refinement）** — 会话图（战略 frame）与战术子图的装载、展开、收缩及入口/出口绑定：复合节点（STRATEGIZE、goal、ACT）展开为子图运行，子图 exit port 映射回外层出边；子 agent = 同契约递归嵌套。结构语义在内核；planner/SOP/工具返回的步骤 DAG 只提供**子图骨架素材**，落账形态由内核规则约束。
3. **循环驱动与仲裁（Loop）** — 活性责任全在内核：决策循环（ReAct）骨架、预算、熔断、超时、终止、转向。"本轮决策的内容"（调哪个工具/回答什么/判停理由/是否进入审慎）委托 Decision 源（LLM/人/规则）；"哪些执行点必须产生决策、决策如何推进图与账本"由内核定。
4. **世界交互协议（Boundary）** — 两类推进源，**不可同构**：**Decision 源**（LLM/人/规则）= 产出语义决策，**唯一**能推进结构/仲裁槽位与游标的力量；**Effect 源**（工具/文件/IO）= 执行副作用，写权在 effect 边界经 **GuardrailToolProxy** 校验（默认无；外部资源 scope 由 `PermissionSandboxValidator`，槽位写由扩展 Validator），不得绕过 decision/仲裁点改结构；返回 observation（可结构化）。工具的 execute 是 effect，LLM 的 infer 是决策请求——前者是后者的后果，不是同级抽象。
5. **门（Gates）** — verifyPre/verifyPost/HITL/预算 = 图边上的固定钩子位；内核强制询问，不实现策略。verifyPost 必须带目标上下文（修 P7/D8）。
6. **持久化与控制通道** — WAL/Checkpoint 定义"一步完成的含义"；cancel/feedback/resume 的中断语义。

### 3.3 内核与各方的关系

```
┌─────────────┐   命令/呈现(薄)   ┌────────────────────────────────────┐
│  L1 Facade   │◄───────────────►│ L2 Agent (功能本体/组合根)           │
│ TUI/CLI/Web │                  │ ・翻译业务→SessionRequest            │
└─────────────┘                  │ ・装配策略到内核(唯一知道所有实现的层)│
                                 │ ・生命周期 owner(创建/关闭/恢复入口)  │
                                 │ ・事件翻译(kernel事件→UI事件)        │
                                 └───────────────┬────────────────────┘
                                    ① 执行契约    │ ② 装配(注入策略实现)
                                    (execute/    ▼
                                     cancel/   ┌──────────────────────────┐
                                     state)    │ L3 KERNEL (执行语义)      │
                                     ▲         │ 决策循环/子图展开/仲裁 │
                                     │         └─────┬───────────┬────────┘
                                     │ ③ 回调 SPI   │ ④ 双通道     │ ⑤ 服务
                             策略钩子实现 ◄─────────┤             │
                        (planner/guardrail/   │ LLM/人=决策源 │ 基础设施
                         prompt/memory/       │ 工具/文件=    │ (WAL/checkpoint
                         model-adapter)       │ Effect 源     │  事件/时钟/HITL)
                                             ▼            ▼
                                          World         Durable Store
```

| 关系 | 性质 | 承载物 |
|---|---|---|
| Kernel ↔ Agent | 组合/委托 + 依赖反转 | ①执行契约接口 + ②装配方法 |
| Kernel ↔ 策略(planner/prompt/guardrail/memory/model) | 注入反转(DIP) | 钩子接口：调用点与签名由内核定，实现由 Agent 注入；planner 能力内聚（模块不拆，D10）：STRATEGIZE 审慎后端 + SOP 匹配，System-1 路由折叠进 actor |
| Kernel ↔ 世界(模型/工具/文件/人) | Decision/Effect 双通道 | LLM/人 = Decision 源（语义决策，推进结构槽位）；工具/文件 = Effect 源（副作用；写经 GuardrailToolProxy 校验，默认无，D11） |
| Kernel ↔ 数据真相 | 服务订阅 | WAL/Checkpoint 接口（结构性事实）；memory/vault=语用记忆，是策略输入 |
| Kernel ↔ Facade | **无直接关系** | 穿透禁止 |
| Kernel ↔ 嵌套会话(003 子 agent) | 同契约递归 | SessionRequest/Result 作为普通消息 |

## 4. 执行契约接口草案（第一刀）

```java
// ── ① 执行契约：内核对外(Agent)暴露的最小面 ───────────────────────
interface Loop {                                  // 主接口名=Loop(D7)；取代 getExecutor() 暴露
    Future<SessionResult> execute(SessionRequest req);   // 进入会话闭环
    void cancel();                                       // 安全点取消语义
    KernelState state();                                 // 阶段/迭代/目标游标 只读快照
    EventStream events();                                // thought/action/observe 订阅
}

// ── ② 策略钩子：内核执行点依赖的接口（由 Agent 装配注入）──────────
interface GoalPlanner   { Plan plan(PlanningCtx ctx); }  // legacy: 现 PlannerService。新模型拆三挂点(§5.1)：STRATEGIZE 审慎 / SOP 匹配 / 路由工具
interface ToolGateway   { Future<ToolResult> invoke(ToolCall tc); }      // 现 ExecutionEngine
interface Guardrail     { boolean pre(Step s);
                          boolean post(Artifact a, Goal g); }    // Post 必须带目标上下文(修 P7)
interface PromptProvider {                                       // ← 动态加载扩展点落点
    PromptBundle resolve(PromptKey key, CtxFragment ctx);
    void registerSource(PromptSource s);
    void reload();
}
interface Inferencer    { Future<ModelObservation> infer(InferRequest req); }  // 文本 LLM pipeline 契约
interface WalGateway    { /* append/checkpoint/... */ }           // 现 WalSession
interface HitlChannel   { Future<String> suspend(String reason); }  // 现 executor 半成品 HITL
interface MemoryAccess  { /* ... */ }                             // 现 AgentSession

// ── ③ 内核数据类型/状态机（属内核，不属实现）─────────────────────
// Ledger(槽位: goal 图/route/artifact/预算/修订计数) / 图基元(decision/effect/
//   gate/observe/terminal/composite) / EffectOutput(observation; 写经 GuardrailToolProxy)
//   SessionResult（AgentContext/PhaseStateGraph 降为 legacy 先行实现）
```

> 主接口名已定：`Loop`（D7，2026-09-09 评审）。其余命名（子接口/类型名）仍待定，此处仅定"形状"。

## 5. 执行工作流：图语义版（会话图 + 战术子图展开）

> 取代旧"Goal 推进环 / 战术双环"表述（2026-09-09 评审）。两条定论：
> ① **plan 是战略规划**，不是简单的执行步骤规划——三者（战略/步骤/路由）此前被压平进一个 `Plan` 对象；
> ② 循环架构是**嵌套图语义**：战略定 frame，战术是战略节点的**子图展开**，不存在并行的"双环"。

### 5.1 plan 三职能拆解（禁止混淆）

| 职能 | 回答的问题 | 认知级别 | 生命周期/触发 | 归属 |
|---|---|---|---|---|
| **战略规划（STRATEGIZE）** | 任务本质与含糊性、值不值得做、走哪条路线（SOP/自由/深思考）、模型与预算分配、成功判据与终止边界 | System-2，刻意审慎 | 会话起点**必达** + **反思回路重入**（ARBITRATE 判路线偏差 / 修订超阈值 / 用户显式要求）；**无前置复杂度评估**（D9：规划不足靠执行后反思迭代修正） | 内核图上的**审慎决策复合节点**；审慎后端 = alice-core-planner（**模块不拆**，D10：Fast/Slow 路径、MCTS、SOP 匹配、选模型）；决策循环内也可直接调 tool 层 `plan` 工具（同一后端） |
| **执行步骤规划** | 把选定路线拆成有序 goal 图（子图骨架） | 结构性产出 | goal 执行途中按需（模型或 SOP 触发） | 规划工具（tool 层 `plan`/`decompose`/`apply_sop`，内部委托 PlannerService；返回结构化 goal 建议，写经 GuardrailToolProxy 校验）；**goal 图的绑定（从建议到账本事实）只在 STRATEGIZE/ARBITRATE 决策点发生**（D11） |
| **路由分类（System-1）** | 这一步先答、先调工具、还是直接结束 | 即时决策 | 战术子图每一步 DECIDE 内 | **折叠进 actor 的 tool 选择**；必要时保留低成本路由工具/模型作实现 |

**现状代码判据**：FastPath 词分类是"路由"而非"战略"（只产出 intent 词+FINISH，不触碰任务本质与路线）；SlowPath/MCTS 是战略审慎的雏形；SOP static 是执行步骤规划的雏形；`planToIntent()` 只取 `steps[0]` = 三职能被压平后又被丢弃。

### 5.2 图基元

**节点类型（内核唯一定义）**：

| 节点 | 语义 |
|---|---|
| `decision` | 必须产生一次决策（Decision 源：LLM/人/规则），决策结果映射到出边，推进图与账本 |
| `effect` | 执行副作用；对世界/内容槽位的写在 effect 边界经 **GuardrailToolProxy** 校验（默认无）；结构/仲裁槽位仅 decision/仲裁点可改（D11） |
| `gate` | 图边上的固定钩子位（verifyPre/verifyPost、HITL、预算、工具权限=**GuardrailToolProxy**：P6 装配点，pre/post Validator 链含 `PermissionSandboxValidator`/`ToolExistenceValidator` 等）；内核强制询问 |
| `observe` | 汇总效果结果/记忆刷新，回流为下一决策上下文 |
| `terminal` | goal 级：`goal-done` / `goal-fail` / `abort`；会话级：`finish` / `error` / `cancel` |
| `composite` | 可展开为子图的节点（STRATEGIZE、goal、ACT），含 entry port / exit port |

**边语义**：有向边带 guard（gate 位）与 payload 路由（decision 结果 → 具体出边）；子图 terminal 经 exit port 映射回外层出边。

### 5.3 会话运行（一次 execute 的图游走）

```
Session: execute(SessionRequest)                         ← 内核驱动, 账本唯一真相
 │
 ▼
会话图（战略 frame）
 START ─► PERCEIVE?(可选) ─► STRATEGIZE(复合=审慎决策)    ← 起点必达
                                   │  写账本: route / goal 图 / success 判据 / 预算
                                   ▼
        ┌── for goal g in goal 图 (账本游标逐一推进) ───────────────┐
        │                                                          │
        │   ACT(g) = 复合节点 → 展开为战术子图 (ReAct)               │
        │   ┌────────────────────────────────────────────────────┐ │
        │   │ entry(g) ─► DECIDE ─► effect(权限) ─► OBSERVE  │ │
        │   │     ▲                                  │           │ │
        │   │     └──────────────────────────────────┘           │ │
        │   │   工具 = effect 之一(decompose/apply_sop/read…)     │ │
        │   │   退出: 模型 finish(goal 判据) / 熔断 / 显式 abort   │ │
        │   └──────────────────────┬─────────────────────────────┘ │
        │                          │ exit port: goal-done|fail|abort
        │                          ▼                                │
        │   verifyPost(g, artifact)  ← gate 带目标上下文            │
        │   ARBITRATE(g): done→游标+1 | fail→修订(预算-1, 回 ACT(g))│
        │                 abort→跳过/终止(策略/HITL)                │
        └──────────────────────────────────────────────────────────┘
 ▼ 会话终止: 会话仲裁点(finish/error/cancel) → WAL 沉淀 → 呈现
```

要点：

1. 战略不是"必经阶段链"：STRATEGIZE 是**审慎决策复合节点**——起点必达，其后仅由**反思回路**重入（ARBITRATE 判路线偏差 / 修订超阈值 / 用户显式要求）。**不做前置复杂度评估**（D9）：规划不足由执行后的验证/反思迭代修正，不预测不预判。不再每轮无条件跑（消除现状每轮 planner 强制调用的成本与战略架空）。
2. 战术子图只在 ACT(g) 展开时存在：它不是另一个"环"，而是 goal 节点的**内部图**（战术=战略的子图）。模型 finish = 提交该 goal 的判据，**不自动终结会话**（修 P1）。
3. plan 不再是阶段：执行步骤规划 = 战术子图内可调用的**规划工具**，返回结构化 goal 建议（写经 GuardrailToolProxy 校验）；goal 图从建议到账本事实的**绑定只在决策点**（STRATEGIZE/ARBITRATE）发生（D11）。多目标天然是账本里的图，游标逐一推进，消灭 `steps[0]` 丢弃（修 P2）。
4. verifyPre/verifyPost 从"拦 Action 的阶段"变成**图边上的 gate 节点**：Pre 拦 STRATEGIZE/ACT 出口，Post 拦 goal 出口且带 g 上下文（修 P6/P7）。
5. skipMicro 语义消失：没有"宏轮"概念，只有"直接执行 vs 展开战术子图"两种节点选择（修 P3）。
6. PERCEIVE 只在需要刷新环境/记忆的门控处重入，随 STRATEGIZE 或修订回路携带，不再绑定固定阶段链（修 P5）。

### 5.4 终止权与预算

| 层 | 谁可结束 | 判据来源 | 预算 |
|---|---|---|---|
| goal | 战术子图内 finish/熔断/显式 abort | verifyPost(artifact, g) + ARBITRATE（策略提供判据，内核执行） | goal 内效果数/深度（熔断） |
| 会话 | 仅会话仲裁点 | STRATEGIZE 写入的成功判据/终止边界 + shouldFinish | **多级预算（D12）**：会话 token 预算 / goal 内效果数与深度 / 嵌套子图深度上限（取代 maxIterations / maxMicroDepth 单计数） |
| 修订 | 修订次数上限 | goal 槽位计数 | 每 goal 修订预算 |

### 5.5 相对旧模型的删改对照

- **删除**：`PLANNING` 阶段"每轮必达"；`planToIntent`/`steps[0]` 消费；Phase 序列链作为编排载体；verifyPre 拦 Action 的宏层位置；skipMicro。
- **保留并重定位**：verifyPre/Post 语义→gate 节点；SOP/StaticPlanner→规划服务（供决策点调用）；FastPath/SlowPath→System-1 路由工具 与 STRATEGIZE 审慎子图素材。
- 现有 `AgentStateGraph`（Phase 有向图 + ACTING 自环）作为"扁平的会话图"先行实现：自环即"战术子图退化为一个循环节点"的特例；新模型是其严格超集（节点可带内部图）。

## 6. 文本 LLM Pipeline 草案

### 6.1 定位

pipeline 不是内核，也不是"效果源"：它是内核**决策循环中 LLM 触点的一段边界实现**——负责"怎么问模型、怎么解析回答"；"何时问、问完的语义结果如何推进状态"归内核。内核只见语义契约：

```java
// 内核语义层：一次 Infer = 一次决策请求（Loop 内的 LLM 触点）；效果由工具/IO 通道执行
record InferRequest(Key role, GoalScope scope, CtxSnapshot ctx, ModelHints hints) {}
record ModelObservation(String content, String reasoning,
                       List<Decision> decisions /* 工具调用/意图, 语义化 */, Status status) {}
// finish_reason / tools schema / reasoning_content 不出现在此签名 —— 是 pipeline 内部语言
```

`Status`（有内容/要工具/失败/截断）= **语义化终止原因**；厂商原始词在 pipeline 内翻译。

### 6.2 阶段模型：六段有序链

```
 ① Resolve   ② Assemble    ③ Serialize    ④ Transport    ⑤ Decode      ⑥ Observe→Record
 ─────────   ────────────   ────────────   ────────────   ────────────   ─────────────────
 Prompt      消息组装        序列化/编码     网络/执行      响应解析        语义解码+记录
Provider    (roles/上下文   (system/user/   retry/timeout/ (extract:      (→Observation/
(动态加载    注入: read_    tools schema    流式?          content/        Status/Decision
 扩展点)     files等)                       vendor adapter  reasoning/     翻译; WAL 触发点)
                                                          tool_calls)
```

| 段 | 接口(草案名) | 职责 | 现状代码 | 扩展点 |
|---|---|---|---|---|
| ① | `PromptResolver` | 按 role/phase/model/session 取模板与规则 | `PromptManager.build*`（static，无接口） | 动态加载：registerSource/版本/多级覆盖 |
| ② | `MessageAssembler` | 上下文注入：`<read_files>/<tool_result>`/lastFeedback | `buildMicroUserContent` 手拼 | 每角色一个 assembler |
| ③ | `PayloadCodec` | roles→请求体、tools schema、thinking 参数 | `dispatchLlmInference` 内嵌 | 按 model/vendor 选 |
| ④ | `ModelTransport` | 网络、超时/重试(策略性)、未来流式 | `ModelProvider.dispatch` | vendor adapter(OpenAI/Gemma 雏形) |
| ⑤ | `ResponseDecoder` | 抠 content/reasoning/tool_calls/finish_reason | 手写 `extractReasoningFromRaw`(字符串 indexOf) | 按 vendor 解析 |
| ⑥ | `ObservationMapper` | 协议词→内核 Status/Decision；是否"要工具" | `microReActStep` 里的 switch | 每 pipeline kind 一个 |

### 6.3 pipeline kinds：同一骨架、不同装配

| kind | ① Resolve | ② Assemble | ④ Transport | ⑤/⑥ 语义 | 现状代码 |
|---|---|---|---|---|---|
| `classification` | planner.ftl+rules | 单 user 文本 | instruction model | 意图词→路由决策（§5.1 System-1，可并入 actor） | `FastPathStrategy` |
| `reasoning`(MCTS) | planner 种子 | 文本 | reasoning model | 供 STRATEGIZE 内部审慎子图使用 | `SlowPathStrategy`/ThinkingTree |
| `actor`(决策循环的 LLM 触点) | micro_loop 模板 | XML 上下文注入 | defaultModel | 协议词→内核 Status/Decision | `dispatchLlmInference` |
| `summarize` | 无模板硬拼 | 对话→摘要 | defaultModel | 摘要文本 | `compactContext()` |
| `guardrail-llm`(未来) | guardrail 模板 | 目标+产物 | 任选 | PASS/FAIL | 暂无(规则式) |
| `sub-agent prompt`(003) | 子任务模板 | 父上下文 | 子 agent | 回传结果 | `AcpClientWrapper` |

**描述方式**：pipeline 由 spec 声明（kind → stages 顺序 + 各段实现选择 + 参数）；运行时按 spec 构建；
实例只依赖 `Inferencer` 接口。新增一种用法 = 注册新 stage + 新 spec，不动内核、不影响其它 kind。

**边界**：循环本体（何时续推、tool_calls 如何派发给效果通道、判停/回退/仲裁）**不在 pipeline**，归内核
（§3.1 决策循环语义）；pipeline 只完成"这一次 infer 的往返 + 协议词→语义翻译"。kind 名中的"Micro/主环"
仅指该 LLM 触点服务于哪个内核循环，不代表循环逻辑在此实现。kinds 与 §5.1 三职能对应：`actor`=战术子图
DECIDE 触点；`classification`=System-1 路由（可折叠进 actor）；`reasoning`=STRATEGIZE 审慎子图的 pipeline。

### 6.4 归属

```
Kernel（执行语义）     : 只见 Inferencer.infer() 契约 + Status/Decision 类型
Agent（组合根）         : 持有 Inferencer 实现，按 kind 装配 stage
pipeline（策略层内部） : 六段 stage 接口 + spec；每段可注入/可观测
Adapter（更外面）      : vendor codec/transport（OpenAI/Gemma/未来多模态）
```

每段可发事件（stage:resolve/assemble/transport/decode）→ 进内核事件流做可观测。
④ 的超时/重试归 pipeline（策略性时间）；内核只管"一次 Infer 有超时上限"这一执行点。

## 7. 与现状代码的迁移对照（后续细化为任务清单）

| 现状 | 归属 | 动作 |
|---|---|---|
| `AgentFacade`（executor 依赖的最小契约） | 内核内部回调 SPI | 收敛/改名 `KernelDelegates`，由 Agent 实现 |
| `Agent.getExecutor()`/`Agent.vertx()` 暴露 concrete | 删除 | 改为 `Agent.kernel()` 只读接口 + `events()` |
| `AgentExecutor`（1903 行单体） | 内核的 legacy 实现 | 拆分：语义→骨架，协议/prompt/WAL→外移 |
| `MicroReActEngine`/Phase/DispatchStrategy 死代码 | 删除或作新内核实现素材 | **先收敛（P0 决策）** |
| `PromptManager`（全 static）+ `FilePromptLoader` | PromptProvider 接口 | 实例化 + registerSource + 多级覆盖 |
| `GuardrailToolProxy` 未接线 | Guardrail 钩子实现 | 由 Agent 装配注入（修 P6） |
| `planToIntent` 只取第一步 / `Plan.Step` | 账本 goal 图 + 游标 | 删除单步丢弃：goal 图为账本槽位，游标逐一推进（修 P2） |
| `plan()` 每轮强制 planner LLM 调用 + 双模型/双 WAL | 删除 | 路由折叠进 actor；战略审慎进 STRATEGIZE 复合节点（消每轮成本与战略架空，修 P1/P3） |
| `FastPathStrategy` 词分类 | System-1 路由工具实现（可并入 actor tool schema） | 不再充当"战略"（判据见 §5.1） |
| `PlannerService`（聚合根） | alice-core-planner 保持（**不拆**，D10），定位=战略规划 | STRATEGIZE 审慎后端 + tool 层 `plan` 工具内部委托；System-1 路由分类移出模块 |
| `SlowPathStrategy`/`ThinkingTree`(MCTS) | STRATEGIZE 内部审慎子图素材 | 由 STRATEGIZE 节点按预算装配（§5.2 / §6.3 `reasoning` kind） |
| `StaticPlanner`/`SopRegistry` | 规划服务后端（`apply_sop` 类，返回 SOP 步骤建议） | 供 STRATEGIZE/ARBITRATE 决策点绑定 goal 图，而非宏层独占阶段 |
| 效果仅回流文本（`__action_log` ad-hoc 写 ctx） | 接线 GuardrailToolProxy（P6）+ 扩展 Validator | 工具写权复用现有 guardrail 体系（默认无）：外部资源 scope=`PermissionSandboxValidator`；槽位写=新增 Validator 在 effect 边界校验；goal 图等结构槽位仅决策/仲裁点绑定（WAL 审计/回放无歧义） |
| 草案 GoalQueue / `goalIndex` | 账本 goal 槽位 | goal 图数据形态 + 游标（§5.3） |
| 手写 `extractReasoningFromRaw` 等 | ResponseDecoder | 按 vendor 拆 parser |

## 8. 开放决策点（评审时逐条捋）

- [ ] D1 策略挂点：Planner 以**模块内聚**提供 STRATEGIZE 审慎后端与 `plan` 工具（模块不拆，D10），内核只见统一规划钩子；Guardrail/PromptProvider 为钩子；Effect/Gateway 属内核循环边界而非策略
- [ ] D2 执行契约粒度：`execute` **每会话一次**；goal 图游标与战术子图展开都是内核执行语义（§5），"每目标一次"的并发契约变体由子图/子 agent 递归表达（会话级契约最简）
- [ ] D3 AgentExecutor 去留 → **已定：手术式抽取**。从 `AgentExecutor` 拆出"LLM 内核"部分（决策循环骨架、infer 触点、语义决策/终止/仲裁语义）入新内核 `Loop`；**其余不动**（WAL 记录点、事件分发、现有编排保持原样）。MicroReActEngine 等死代码收敛（P0）仍待决
- [ ] D4 内核哲学：~~运行时/VM 式（agent-agnostic）~~ vs Agent 微架构式（认识 LLM-Agent 决策循环 + 目标推进/仲裁）→ **评审反馈：倾向 Agent 微架构式**，VM 纪律仅作实现纪律不作哲学边界（见 §3 评审注与 §3.1 "决策循环语义"）
- [ ] D5 文本 LLM pipeline → **已澄清边界**：六段是**执行/策略层实现**，**内核不需要**——内核只依赖 `Inferencer.infer()` 语义契约 + `Status/Decision`/流式预留。六段拆不拆独立接口属 pipeline 策略层内部可维护性决策（与内核解耦，可先内部实现、后按 kind spec 装配）；④ 超时/重试归 pipeline 策略（内核只管"一次 Infer 有超时上限"）
- [ ] D6 Prompt 动态加载的具体形态 → **已定**：优先级 内置 < `~/.alice/prompts` < 会话级；**不 watch 热更**（新增 `/reload` 命令手动刷新）；PromptKey 简化按 kind + 文件名路由，不做 role/phase/model/session 全组合
- [ ] D7 内核接口名与包结构 → **已定**：主接口名 **`Loop`**；**不建新模块**，放 alice-core-agent 内子包（`org.cland.alice.core.agent.kernel`? 仍待确认最终包名）
- [ ] D8 verifyPost(artifact, goal) 的目标比对语义（谁提供"目标达成判据"）
- [ ] D9 ~~前置复杂度/新颖性门评估~~ → **已定：不做评估**。STRATEGIZE 触发 = 会话起点必达 + 反思回路重入（ARBITRATE 判路线偏差 / 修订超阈值 / 用户显式要求）；规划不足由执行后的验证/反思迭代修正（§5.1、§5.3 要点 1）
- [ ] D10 ~~PlannerService 拆解~~ → **已定：模块不拆**。alice-core-planner 保持聚合根，定位 = **战略规划**（Fast/Slow 路径、MCTS、SOP 匹配、选模型）；System-1 路由分类移出（折叠进 actor 的 tool 选择）。**tool 层新增 `plan` 工具**（注册于 ToolRegistry，同后端委托 PlannerService），供 STRATEGIZE 审慎与决策循环内按需调用；产出结构化 goal 建议，绑定仍限决策/仲裁点（D11）。包结构/导出面维持现状
- [ ] D11 槽位写权限 → **已定：不造新机制，权限体系已有**（alice-guardrail：`PermissionSandboxValidator` 管外部资源 scope；`GuardrailToolProxy` 为 P6 装配点，Pre/PostValidator 链是扩展口）。补的仅是"槽位写"这一新检查目标：登记一个 Validator（如 `LedgerScopeValidator`）在 effect 边界校验写目标槽位与工具 scope。无默认写权；goal 图/route/预算/游标等**结构/仲裁槽位**仍仅在内核 decision/仲裁点变更。校验器清单与规则细节待定
- [ ] D12 会话预算语义 → **已定：多级预算**。会话 token 预算 / goal 内效果数与深度 / 嵌套子图深度上限；取代 maxIterations / maxMicroDepth 单计数（§5.4）。各级默认值与超限行为待定

## 9. 参考

- [loop-llm-interaction.md](./loop-llm-interaction.md) — Loop↔LLM 交互与 payload 明细（现状）
- [DESIGN.md](./DESIGN.md) — 核心架构设计（理想形态）
- [KEY_LOG.md](./KEY_LOG.md) / [AWL&CheckPoint.md](./AWL&CheckPoint.md) — 日志与持久化现状
- [../alice-core-planner/inbound.md](../alice-core-planner/inbound.md) — PlannerService 决策流
