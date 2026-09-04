---
title: "alice-core-agent — Kernel 架构与执行工作流设计草案 (Draft)"
summary: "三层架构（Facade/Agent/Kernel）与内核执行语义草案：内核的定义与关系、执行契约接口、终止与仲裁规则、文本 LLM pipeline 阶段模型、与现状代码的迁移对照。Draft 状态，供评审迭代。"
read_when:
  - "重构 AgentExecutor / 定义内核接口时"
  - "讨论执行工作流（Goal 编排、终止仲裁）时"
  - "设计文本 LLM pipeline / 动态 prompt 加载扩展点时"
scope:
  - "alice-core-agent"
status: "draft"
updated: "2026-09-04"
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

**一句话**：内核 = 确定性执行基底；智能是跑在它上面的应用。

### 3.1 内核管七类"功能语义"

| 语义域 | 内核定义 | 反例（不属于内核） |
|---|---|---|
| 状态语义 | 状态集、合法迁移（Phase 图）、游标推进、谁能改状态 | 状态里装什么业务字段 |
| 效果语义 | effect 调度、副作用记录、结果如何变观察 | 工具名/参数含义/文件格式 |
| 终止语义 | 谁可结束什么（micro→目标，仲裁→会话）、预算与熔断 | 何时"算完成"的业务判据 |
| 验证语义 | 门在哪些执行点被询问、拦截后流向哪里 | 校验规则内容 |
| 失败语义 | 失败→诊断→重试/回退/放弃 的结构化路径 | 具体错误含义 |
| 时间语义 | 超时点、挂起点、恢复点、WAL 事务边界 | 等待多久合适 |
| 并发/嵌套语义 | 会话可嵌套（子 agent=同契约）、效果可并行、取消传播 | 子任务做什么 |

### 3.2 内核的五件事

1. **状态账本（Ledger）** — 已发生(trace)/在哪(游标)/信什么(context)；唯一由内核写入。
2. **循环驱动与仲裁（Loop）** — 活性责任全在内核：推进、预算、熔断、超时、终止；"决策"委托钩子。
3. **世界交互协议（Boundary）** — 工具/LLM/人输入对内核是同一抽象：**一个 effect，返回一个 observation**。
4. **门（Gates）** — verifyPre/verifyPost/HITL/预算 = 固定执行点上的钩子位；内核强制询问，不实现策略。
5. **持久化与控制通道** — WAL/Checkpoint 定义"一步完成的含义"；cancel/feedback/resume 的中断语义。

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
                                     ▲         │ 状态机/仲裁/门/账本/时间   │
                                     │         └─────┬───────────┬────────┘
                                     │ ③ 回调 SPI   │ ④ effect   │ ⑤ 服务
                             策略钩子实现 ◄─────────┤            │
                        (planner/guardrail/   │ 效果通道     │  基础设施
                         prompt/memory/       │ (模型/工具/  │  (WAL/checkpoint
                         model-adapter)       │  人=同一抽象) │   事件/时钟/HITL)
                                             ▼            ▼
                                          World         Durable Store
```

| 关系 | 性质 | 承载物 |
|---|---|---|
| Kernel ↔ Agent | 组合/委托 + 依赖反转 | ①执行契约接口 + ②装配方法 |
| Kernel ↔ 策略(planner/prompt/guardrail/memory/model) | 注入反转(DIP) | 钩子接口：调用点与签名由内核定，实现由 Agent 注入 |
| Kernel ↔ 世界(模型/工具/文件/人) | 效果通道 | `Effect` 抽象（调度→记录→观察） |
| Kernel ↔ 数据真相 | 服务订阅 | WAL/Checkpoint 接口（结构性事实）；memory/vault=语用记忆，是策略输入 |
| Kernel ↔ Facade | **无直接关系** | 穿透禁止 |
| Kernel ↔ 嵌套会话(003 子 agent) | 同契约递归 | SessionRequest/Result 作为普通消息 |

## 4. 执行契约接口草案（第一刀）

```java
// ── ① 执行契约：内核对外(Agent)暴露的最小面 ───────────────────────
interface ExecutionKernel {                              // 取代 getExecutor() 暴露
    Future<SessionResult> execute(SessionRequest req);   // 进入会话闭环
    void cancel();                                       // 安全点取消语义
    KernelState state();                                 // 阶段/迭代/目标游标 只读快照
    EventStream events();                                // thought/action/observe 订阅
}

// ── ② 策略钩子：内核执行点依赖的接口（由 Agent 装配注入）──────────
interface GoalPlanner   { Plan plan(PlanningCtx ctx); }          // 现 PlannerService(含 SOP)
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
// AgentContext / PhaseStateGraph / StepResult(sealed) / Goal(+goalIndex 队列) / SessionResult
```

> 命名全部待定（ExecutionKernel/WorkflowEngine/…）。此处仅定"形状"，不锁定名字。

## 5. 目标执行工作流（Goal 推进环，修 P1/P2）

```
Session (会话级, 内核驱动)
 │ 1. Perceive: 建 AgentContext, 拉记忆/环境 (会话起点, 每目标前可选刷新)
 │ 2. 目标队列 = planner(SOP 多步 | Fast/Slow intent) 或 用户单目标
 ▼
┌──────────────────────────────────────────────────────────────────┐
│ Goal 推进环 (内核):                                                │
│   for g in goalQueue (游标推进, 非重新规划):                        │
│     ① 状态: ACTING(g)  (Phase 状态机 + goalIndex 持久于 ctx)        │
│     ② 执行体: [战术双环] → 产物 Artifact + 证据(actions/obs)        │
│     ③ 对照 g 验证: verifyPost(artifact, g) —— 带目标上下文           │
│     ④ Reflect(g) 仲裁:                                            │
│          PASS    → 标记 g 完成 → 下一目标 (成功的战略续圈 ← P1 修复) │
│          FAIL    → REVISION(反馈) → 同目标重试(带次数上限)          │
│          ABORT   → 跳过/终止本目标(策略决定: HITL/取消/预算)         │
│   until 队列空 or shouldFinish(session级) or cancelled             │
└──────────────────────────────────────────────────────────────────┘
 ▼ 会话结束: 结果沉淀(WAL FINISHED/ERROR) → 前端呈现
```

终止权规则：micro-Finish 只结束当前目标；会话是否终止由 Goal 环仲裁点决定。

## 6. 文本 LLM Pipeline 草案

### 6.1 定位

pipeline 不是内核，是内核的一个"效果源"。内核只见语义契约：

```java
// 内核语义层：一次 Infer = 一个 effect
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
| `classification` | planner.ftl+rules | 单 user 文本 | instruction model | 意图词→Intent 链 | `FastPathStrategy` |
| `reasoning`(MCTS) | planner 种子 | 文本 | reasoning model | — | `SlowPathStrategy`/ThinkingTree |
| `actor`(Micro 主环) | micro_loop 模板 | XML 上下文注入 | defaultModel | tool_calls→Decision, finish→Status | `dispatchLlmInference` |
| `summarize` | 无模板硬拼 | 对话→摘要 | defaultModel | 摘要文本 | `compactContext()` |
| `guardrail-llm`(未来) | guardrail 模板 | 目标+产物 | 任选 | PASS/FAIL | 暂无(规则式) |
| `sub-agent prompt`(003) | 子任务模板 | 父上下文 | 子 agent | 回传结果 | `AcpClientWrapper` |

**描述方式**：pipeline 由 spec 声明（kind → stages 顺序 + 各段实现选择 + 参数）；运行时按 spec 构建；
实例只依赖 `Inferencer` 接口。新增一种用法 = 注册新 stage + 新 spec，不动内核、不影响其它 kind。

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
| `planToIntent` 只取第一步 | Goal 队列/游标 | 内核数据类型 + Goal 环（修 P2） |
| 手写 `extractReasoningFromRaw` 等 | ResponseDecoder | 按 vendor 拆 parser |

## 8. 开放决策点（评审时逐条捋）

- [ ] D1 三层中"策略"范围：Planner/Guardrail/PromptProvider 先进钩子，还是把 Effect/Gateway 一起立
- [ ] D2 执行契约粒度：`execute` 每目标一次 or 每会话一次（决定是否带 GoalQueue）
- [ ] D3 AgentExecutor 去留：原样收编为 legacy 实现 vs 直接重写新实现
- [ ] D4 内核哲学：运行时/VM 式（agent-agnostic） vs Agent 微架构式（认识目标推进/仲裁）；倾向"VM 纪律 + 执行语义"
- [ ] D5 文本 LLM pipeline：六段是否够/每段是否独立接口；④ 超时重试归属确认
- [ ] D6 Prompt 动态加载的具体形态（registerSource 优先级、watch 热更、session 级隔离）
- [ ] D7 内核接口名与包结构（`org.cland.alice.core.agent.kernel`?）
- [ ] D8 verifyPost(artifact, goal) 的目标比对语义（谁提供"目标达成判据"）

## 9. 参考

- [loop-llm-interaction.md](./loop-llm-interaction.md) — Loop↔LLM 交互与 payload 明细（现状）
- [DESIGN.md](./DESIGN.md) — 核心架构设计（理想形态）
- [KEY_LOG.md](./KEY_LOG.md) / [AWL&CheckPoint.md](./AWL&CheckPoint.md) — 日志与持久化现状
- [../alice-core-planner/inbound.md](../alice-core-planner/inbound.md) — PlannerService 决策流
