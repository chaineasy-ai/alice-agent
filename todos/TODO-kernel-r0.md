---
title: "TODO - R0 图内核换轨收口"
summary: "kernel-architecture.md 重构落地后的后续任务板：换轨默认翻转与 facade 契约迁移（specs/004）、图内核能力补齐（WAL/verifyPost/HITL/多级预算/D11/子 agent/取消）、Inferencer kinds 与 PromptProvider 演进、文档与门禁收口。前置里程碑（元模型/R0 解释器/标准骨架/生产适配器/GraphSessionKernel/事件桥与 Agent 开关）已于 2026-09-10 全绿落地。"
read_when:
  - "继续 kernel-architecture.md 的换轨收口工作"
  - "决定 AgentConfig.graphKernelEnabled 默认置真"
  - "迁移 executor 语义测试基线"
  - "为图内核补 WAL/guardrail/HITL/预算能力"
scope:
  - "alice-core-agent"
  - "alice-facade-cli"
  - "alice-facade-tui"
  - "alice-core-planner"
  - "alice-guardrail"
  - "alice-bootstrap"
status: "active"
updated: "2026-09-10"
---

# TODO: R0 图内核换轨收口

> 格式约定：`- [ ]` 待办 | `- [x]` 已完成 | `- [/]` 执行中 | `- [-]` 已取消 | `- [!]` 失败/阻塞
> 元数据键：`[priority:: high|medium|low]` `[owner:: 模块]` `[verify:: 验收方式]`
> 依据文档：docs/alice-core-agent/kernel-architecture.md（§3/§4/§5/§6/§7/§8）+ loop-llm-interaction.md

---

## 0. 已完成里程碑（2026-09-09 → 2026-09-10，`./gradlew check` 全绿）

- [x] 内核执行契约第一刀：`kernel` 包 Loop/SessionRequest/SessionResult/KernelState/EventStream/KernelDelegates；Agent 暴露 kernel()/events()；TUI 订阅走内核事件流  [date:: 2026-09-10]
- [x] 测试债务修复：alice-core-planner（44/93 失败）与 alice-guardrail（37/67 失败）套件恢复；bootstrap 分发测试离线化（hermetic 模型 stub）；TUI headless 模式（dumb terminal） [date:: 2026-09-10]
- [x] P0 死代码收敛：MicroReActEngine/Phase/DispatchStrategy/AgentEventBus 等 12 文件删除  [date:: 2026-09-10]
- [x] P6 工具级守卫生效：执行器自动装配默认 GuardrailToolProxy + Agent.withGuardrailToolProxy  [date:: 2026-09-10]
- [x] D10 plan 工具：PlanTool（@AgentTool("plan")）注册于 createDefault  [date:: 2026-09-10]
- [x] D5 Inferencer 语义契约 + TextLlmPipeline 六段 actor kind；executor LLM 触点外移（行为零漂移） [date:: 2026-09-10]
- [x] R0 图内核：kernel.graph 元模型 + Ledger（R4 无通用写入口）+ R1 决策词表 + R0Interpreter（展开帧/exit port/预算注解/两写点）；R0InterpreterSpec 10 例（R0–R4/P1/P2/P4） [date:: 2026-09-10]
- [x] 标准骨架 StandardSkeleton（规划-TAO-反思 三回路，goal 游标/修订注解/回规划重入）；StandardSkeletonSpec 8 例（P1/P2/P5/P7/D9/R3） [date:: 2026-09-10]
- [x] 生产装配适配器：PlannerGoalBrain / LlmActorBrain（ActorStep.Act/Done+answer 产物）/ ToolRegistryEffectGateway；AgentGraphAssemblySpec 6 例含离线端到端 [date:: 2026-09-10]
- [x] GraphSessionKernel implements Loop：answer 收敛（写点① recordArtifact）、异常收敛 FAILED、cancel、state；GraphSessionKernelSpec 6 例  [date:: 2026-09-10]
- [x] 事件桥 + Agent 开关：R0 KernelTraceListener 埋点 → GraphSessionKernel 真实 EventStream；AgentConfig.graphKernelEnabled（默认 false）；AgentGraphSwitchSpec 2 例 [date:: 2026-09-10]

---

## 1. 换轨默认翻转与契约迁移（specs/004-kernel-runtime-swap，行为级变更，独立评审）

- [ ] specs/004-kernel-runtime-swap 建档：spec.md → plan.md → tasks.md → contracts/ → checklists/requirements.md  [priority:: high]
- [ ] `AgentConfig.graphKernelEnabled` 默认置真（需 1.3–1.7 完成后） [priority:: high] [verify:: AgentGraphSwitchSpec 默认路径用例]
- [ ] ask/askAsync/run 业务门面迁移：AgentContext 语义 → SessionRequest/SessionResult（ctx["result"]/error/__llm_reasoning 消费点审计） [priority:: high]
- [ ] facade 消费迁移：ExecutionCoordinator（askAsync+AgentContext 渲染）、JLineChatSession、AliceTuiLauncher 事件呈现走图内核事件桥  [priority:: high]
- [ ] executor 语义测试基线迁移：AgentPpaoLoopSpec / AgentExecutorMultiToolCallSpec / AgentExecutorUnitSpec / ToolGuardrailWiringSpec / LoopSpec / AgentApiSpec 重定到图语义（或显式标注 legacy-only 保留集） [priority:: high]
- [ ] legacy AgentExecutor 保留策略定稿：删除或降为子 agent（003）/恢复路径专用（WAL 恢复语义审计） [priority:: medium]
- [ ] KernelDelegates 收敛：GoalPlanner/ToolGateway/Guardrail/PromptProvider/Inferencer 钩子拆分，shouldFinish/verifyPost legacy 签名退出（§4 ②） [priority:: medium]
- [ ] Agent.kernel()/events() 双运行时并存期文档化（README/TECH_STACK 迁移指南） [priority:: low]

## 2. 图内核能力补齐

- [ ] WAL/Checkpoint 接入图内核：Ledger trace/产物快照 → Checkpoint；恢复 = 回放 + 重路由（§3.2 ⑥） [priority:: high] [owner:: alice-core-agent]
- [ ] verifyPost(g) 规则适配：GuardrailVerificatorAdapter 语义 → GatePolicy（D8：模型判据提交 + 规则后检 + 迭代预算兜底，维持原语义） [priority:: high] [owner:: alice-core-agent, alice-guardrail]
- [ ] HITL gate：HitlChannel.suspend 挂起/恢复/超时语义接入骨架（§4 ② hook） [priority:: medium]
- [ ] 多级预算（D12）定标：goal 内效果数上限、嵌套子图深度上限、会话 token 预算注解默认值（现 revisionBudget=2/taoEffectBudget=config.maxMicroDepth/maxSteps=10000 需评审） [priority:: medium]
- [ ] LedgerScopeValidator（D11）：效果写 artifact 的权限校验 + 结构槽位仅写点的运行时断言（校验器清单与规则） [priority:: medium] [owner:: alice-guardrail]
- [ ] cancel 进循环：R0 解释器 effect 间安全点检查；嵌套会话取消传播（§3.1 并发/嵌套语义） [priority:: medium]
- [ ] 子 agent（003）同契约递归：SessionRequest/Result 作为普通消息的图嵌套接线（SubAgentManager 消费审计） [priority:: medium]
- [ ] artifact 多产物语义：内容槽位经 GuardrailToolProxy 授权工具写入（read/write 工具链） [priority:: low]

## 3. Inferencer / Prompt 演进（D5 剩余 / D6）

- [ ] pipeline kinds spec 装配：TextLlmPipeline 六段抽独立 stage 接口 + kind spec 声明（classification/reasoning/summarize/guardrail-llm/sub-agent） [priority:: medium]
- [ ] ④ Transport 超时/重试策略化（策略性时间归 pipeline） [priority:: medium]
- [ ] ResponseDecoder 按 vendor 拆 parser（OpenAI raw indexOf → 结构化 decode） [priority:: low]
- [ ] PromptProvider 实例化（D6）：PromptManager 收敛 registerSource + 多级覆盖（内置 < ~/.alice/prompts < 会话级）；新增 /reload 命令手动刷新（不 watch） [priority:: medium] [owner:: alice-facade-cli]

## 4. 文档与门禁收口

- [ ] kernel-architecture.md：实现注记合并进正文，清理"留待实现期"标注；loop-llm-interaction.md 标记为 legacy 运行时基线文档 [priority:: medium]
- [ ] AGENTS.md Key Source Files 增补 kernel.graph / graph / pipeline 关键文件 [priority:: low]
- [ ] CHANGELOG 随各里程碑更新（保持现有格式） [priority:: low]
- [ ] 保持 `./gradlew check` 全绿门禁（含 jacoco：alice-core-agent 40/25、其余 80/70） [priority:: high] [verify:: 每次提交前跑 check]

## 5. 环境性遗留（非代码）

- [ ] bootstrap 测试 worker 在高负载下偶发基础设施级中断（全部用例 XML 绿后 worker 失联；无 hs_err/无测试失败；低负载稳定）。已根治测试侧成因（真实 LLM 依赖、TUI 终端劫持）；CI/负载侧观察，复现时抓 worker 日志 [priority:: low] [status:: 观察中]
