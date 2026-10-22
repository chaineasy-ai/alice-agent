---
title: "alice-agent 唯一 TODO（v1 · 2026-10-23）"
summary: "合并 todos/TODO-kernel-r0.md、todos/TODO-spec.md、todos/TODO-20260628.md 与 #173 现存子单的唯一任务板；四态（已完成/进行中/待定/放弃），每条带可验收判据或明确待定决策人，并与 #3 ADR 待定项对齐。"
read_when:
  - "想知道 alice-agent 现在还有什么没做、下一步做什么"
  - "接手任务前确认它是否已完成/是否已放弃，避免重复做"
  - "评审 #173 后续切片或 #3 ADR 的待定项"
  - "更新 alice-agent 任务状态"
scope:
  - "alice-agent"
  - "alice-bootstrap"
  - "alice-agent-proto"
  - "alice-agent-runtime"
  - "alice-core-agent"
  - "alice-core-planner"
  - "alice-model"
  - "alice-env-adapter"
  - "alice-tool-gateway"
  - "alice-guardrail"
  - "alice-memory-vault"
  - "alice-facade-cli"
  - "alice-facade-tui"
  - "alice-facade-rpc"
status: "active"
updated: "2026-10-23"
---

# alice-agent 唯一 TODO（v1）

> **本文件是唯一任务板**。`todos/TODO-kernel-r0.md`、`todos/TODO-spec.md`、`todos/TODO-20260628.md` 自 2026-10-23 起**冻结为历史材料**，不再更新；旧条目映射见 §5。
> **四态定义**：
> - **已完成**：有代码/测试/工单证据，可直接引用。
> - **进行中**：已有明确执行动作或执行人，尚未达到出口判据。
> - **待定**：等 `base/cland-arch#3` ADR 或 Owner/指定人决策；必须写"谁定 + 什么条件下定"。
> - **放弃**：明确不做/被取代；必须写依据，不能只是"没人做"。
>
> **关闭规则**：进行中/待定项完成时，必须把判据证据（命令回显/测试名/工单链接）贴到 `#173` 或对应子单，再改本表状态；**无证据不改状态**。

---

## 1. 已完成（有证据）

| ID | 事项 | 判据/证据 | 来源 |
|---|---|---|---|
| D01 | 内核执行契约 + R0 图内核骨架 + 标准骨架 + 生产适配器 + `GraphSessionKernel` + 事件桥 | `kernel-architecture.md`；`./gradlew check` 全绿；30 例 graph specs（2026-09-10） | `TODO-kernel-r0` §0 |
| D02 | 图内核取消语义进循环（安全点 + 收敛 `CANCELLED` + 嵌套传播） | `#174` 已关闭；`R0InterpreterSpec`/`GraphSessionKernelSpec` 覆盖 | `#174` |
| D03 | WAL/Checkpoint 接入图内核（回放 + 重路由） | `#178` 已关闭；`LedgerStateSpec`/`GraphCheckpointAdapterSpec`/`R0InterpreterResumeSpec`/`GraphSessionKernelResumeSpec` | `#178` |
| D04 | `verifyPost(g)` → `GatePolicy` 语义适配 | `#175` 已关闭；`VerifyPostGatePolicySpec` 12 例 parity | `#175` |
| D05 | 核心闭环端到端跑通 + 问题清单 | `#297` 已关闭 | `#297` |
| D06 | CI runtime 压测竞态修复（`RoundEventPublisher`） | `#461` 已关闭 | `#461` |
| D07 | `HallucinationDetector` 裸子串误报修复（结构化判定） | `#522` 已关闭；`VP-P02d`（commit `41f2169`） | `#522` |
| D08 | CLI/RPC 接入 `graphKernel` 开关（默认 on） | `#306` 已关闭；CLI 启动横幅 `graphKernel=true`；RPC `graphKernel=true (source=default)` | `#306` |
| D09 | 可观测：JSON Lines 日志 + `/metrics` 基础指标 | commit `0187828`；`docs/ops/observability.md`；实测 `/api/v1/metrics` 返回 | `#173 M3-2` |
| D10 | 门禁尾巴（统一形状）+ exposure 巡检 | commit `07e608e`、`844601f`；`gate.yaml` 6 段；`scripts/check.sh` | `#173 M3-3` |
| D11 | 协议层转正：`proto` + `runtime` + `facade-rpc` HTTP RPC 2.0 | 发布清单 `docs/release/20260919/`；实测 SSE `/chat/stream`、`/health`、400 | `#173` |
| D12 | 架构评审 / 冒烟评审 / 产品能力边界定义 | `#514`、`#515`、`#516` 均已关闭并有交付物 | `#514/#515/#516` |
| D13 | 测试债修复（planner/guardrail 套件、bootstrap 离线化、TUI headless） | `TODO-kernel-r0` §0；`./gradlew check` 全绿 | `2026-09-10` |
| D14 | R0 兼容层"不需要兼容"的裁定 | Owner `#3` 裁定第 3 条 | `#3` |

---

## 2. 进行中（有执行人/动作，未达出口）

| ID | 事项 | 出口判据（可验收） | 执行人/工单 |
|---|---|---|---|
| I01 | 功能说明书 + 唯一 TODO 交付 | 本文 + `docs/功能说明书.md` 落仓；`#663` 回帖；atlas 验收通过 | compass / `#663` |
| I02 | 测试资产清点 + 假绿专项 + 用例台账 | `docs/testcases/` 台账落仓；模块×用例矩阵无空白；每条假绿有证据；一页"装/等/不装 Kiwi"判断 | probe / `#664` |
| I03 | 冒烟套件升合并门禁的 5 项前置 | P-1 退出码语义、P-2 runner on/off、P-3 工作树隔离、P-4 自省断言、P-5 CI 接入逐条可复跑；全绿后才挂门禁 | probe/mint/steward / `#521` |
| I04 | CLI 输出断言漂移校准 | 断言与当前 CLI 输出一致；`e2e` 可重复通过；无"空断言/只看退出码" | mint / `#415` |
| I05 | Agent 横向对比 + benchmark | 同构协议下 pi/alice 同任务跑通；输出轮次/token/工具数/耗时对比表 | curie/turing / `#517` |
| I06 | `#173` 后续切片重排 | 按 `#3` ADR 结论产出下一批子单（有 due/验收/责任人），不再是"子单全关" | atlas / `#173` |
| I07 | CI 升级为真门禁（`check + spotlessCheck + 覆盖率`） | GitHub Actions 跑 `scripts/check.sh --stage all`；连续 7 天绿；合并强制 | mint/steward / `#173 M3-0` |
| I08 | 文档收口：kernel-architecture 实现注记、AGENTS 关键文件、CHANGELOG | 文档无"留待实现期"；`AGENTS.md` 增补 `kernel.graph/graph/pipeline`；CHANGELOG 随里程碑更新 | compass/mint |
| I09 | **G1 安全缺陷：guardrail 前置不校验工具参数**（`#665`） | 修复后 `dd`/`/etc/passwd` 等真实 CLI 任务被 `REJECT`；新增端到端回归用例；`docs/功能说明书.md §3.4` 更新为"已拦截" | mint / `#665`（挂 `#173`） |
| I10 | 三条入口回归纳入日常 | 每次发版前跑 CLI/TUI/RPC 三条路径并留回显；失败即阻塞发版 | mint/compass |

---

## 3. 待定（等 `#3` ADR 或指定人决策）

> 每条都写"谁定 + 条件"；**条件未满足前不排期、不写"尽快"**。

| ID | 事项 | 待定原因 / 谁定 | 定后判据 |
|---|---|---|---|
| H01 | 模块稳定性分级（稳定核心/内核/实验）与变更流程 | `#3` 议题 2；pillar 出 ADR，Owner 参与"不确定的模块" | ADR 列出 13 模块级别 + 每级变更流程（ADR/parity/可推翻） |
| H02 | 图内核：冻结为 experimental 还是继续收口 | `#3` 议题 3；Owner 拍板 | ADR 明确"冻结/继续"；若继续，给下一批切片 |
| H03 | TUI 接图内核（当前固定 legacy） | 依赖 H02；pillar/Owner | TUI 启动横幅与 CLI/RPC 一致；`graphKernelEnabled` 默认值单点定义 |
| H04 | sub-agent 语义统一（alice 003 / pi subagent / ACP） | `#3` 议题 4；pillar | 只保留一套语义 + 权限面表（人员/数据/工具可见范围） |
| H05 | 门禁 / 可观测口径与平台统一 | `#3` 议题 5；pillar/steward | 与 `gate.py`/`check.sh`/`#385`/`#641` 同口径；不自建；`gate.yaml` 段数口径对齐 |
| H06 | 依赖方向硬约束（4 条断言 + 豁免到期日） | `#3` 议题 6；pillar | ArchUnit/脚本进 CI；`proto` 不被实现依赖、facade 不被实现依赖、core 不依赖 facade/memory-vault、同层门面依赖集合一致 |
| H07 | 是否拆 `core-agent` 上帝模块 | `#3` 议题 2；Owner（建议本季不拆） | 先有依赖门禁 + 分级 + parity 基线，才允许评估拆分 |
| H08 | `memory-vault` 接线（Vault → AgentSession，持久化/摘要/遗忘） | 需产品/技术共同定；mint/pillar | 至少 1 条真实调用路径 + e2e；重启后记忆可恢复；无孤岛 |
| H09 | `env-adapter`/MCP 接线（McpClient → ToolRegistry） | mint/pillar | 一个真实 MCP server 的工具可被 LLM 调用；e2e 证据 |
| H10 | HITL gate（挂起/恢复/超时） | 依赖 H02；mint | `HitlChannel` 存在；挂起/恢复/超时三场景可测 |
| H11 | 多级预算 D12（goal 效果数/嵌套深度/token） | 依赖 H02；mint | 超限收敛并记录原因；预算优先级有测试 |
| H12 | D11 `LedgerScopeValidator`（账本写权限/槽位断言） | 依赖 H02；mint/guardrail | 越权写 artifact 被拒绝；结构槽位只允许写点 |
| H13 | 子 agent 同契约递归（SessionRequest/Result 图嵌套） | 依赖 H04/H02；mint | 父会话 spawn/cancel/results 全链路可测 |
| H14 | artifact 多产物语义 | 依赖 H02；mint | 多产物经授权工具写入；读写工具链闭环 |
| H15 | `specs/004-kernel-runtime-swap` 建档 + M2 facade 契约迁移 | `#3` ADR 后；pillar/mint | spec/plan/tasks/contracts 齐；facade 全链路走 SessionRequest/Result；`graphKernelEnabled` 单点默认 |
| H16 | legacy `AgentExecutor` 去留（删除/降为子 agent/恢复专用） | 依赖 H15；pillar/Owner | parity 用例齐；去留定稿并文档化；无两份编排真源 |
| H17 | pipeline / PromptProvider / ResponseDecoder 演进 | mint | 六段 stage 接口 + kind spec；Prompt 多级覆盖 + `/reload`；vendor parser 结构化 |
| H18 | 远程 SSH / Docker 沙箱 | 产品范围未定；compass/Owner | Owner 决定是否入产品；入则给威胁模型与验收 |
| H19 | 命令白名单/敏感信息脱敏/审计日志持久化 | 产品范围未定；compass/Owner | 同上；与 G1 修复一起评审 |
| H20 | 多模型路由 / Provider SPI 热加载 | 产品需求未证；compass/Owner | `#517` 证明需要后再排；否则不做 |
| H21 | TUI 任务看板 / 多 Session / 多 Tab | 无用户需求证据；compass | 有明确使用场景与验收再排 |
| H22 | usage/cost 上报（G4）+ 上下文窗口滑动/Token 预警 | mint | 一轮真实任务后 `/health.lastUsage` 非 0；上下文超限有策略 |
| H23 | `alice tools` / TUI `/tools` 装配（G2/G3） | mint | 两个入口都能列出运行期 9 个工具与 Schema |
| H24 | `steer` 队列语义（G5） | runtime/mint | 空闲/忙时插话都能被消费；无"202 假成功" |
| H25 | `web_search` 工具修复（G8） | mint | 一次搜索成功返回结果；失败不无限重试 |
| H26 | 发布形态：git tag + 三平台 JVM/native 制品 | atlas/Owner（资源决策） | 存在可回溯 tag；三平台制品可下载并启动；安装/升级文档 |
| H27 | 任务重试 / 局部重规划（旧 TODO-spec） | planner/mint | 失败步骤可重试；部分失败触发局部重规划并有测试 |
| H28 | 工具执行结果缓存（旧 TODO-spec） | mint | LRU 缓存 + 命中统计 + 可关闭 |
| H29 | bootstrap 测试 worker 高负载偶发中断（观察项） | mint | 复现时抓到 worker 日志；否则保持观察，不计入排期 |
| H30 | CLI 进度条 / 状态图标（旧 TODO-spec） | compass/mint | 有明确交互需求再排；当前文本输出已够用 |
| H31 | 临时 DEBUG 日志清理（旧 TODO-20260628） | mint | `src/main` 无遗留临时 DEBUG；日志级别可配置 |

---

## 4. 放弃（明确不做/被取代，附依据）

| ID | 事项 | 放弃依据 |
|---|---|---|
| X01 | "24 模块"作为构建模块口径 | 实读 13 个 Gradle/JPMS 模块；`#3` 已判口径废止；本文 §2 用 13+11 映射保留全部历史单元 |
| X02 | `TODO-spec.md` / `TODO-20260628.md` 继续作为活动看板 | 与 `TODO-kernel-r0` 双板漂移；自 2026-10-23 冻结，统一到本文件 |
| X03 | `TODO-20260628` 的 3 条"LLM 行为建议"作为代码待办 | 根因是 LLM 行为，不是代码缺陷；保留为观察项（见 §5），不占排期 |
| X04 | R0 旧接口兼容层 | Owner `#3`："不需要兼容"；兼容层直接拆 |
| X05 | 插件市场 / 工具市场 / Agent 模板仓库 | 无消费者、非当前产品范围；产品化未成立前不做 |
| X06 | ShowDoc 同步 / API 文档自动生成 | 文档与代码同仓（`docs/`）是唯一真源；同步会产生第二份口径 |
| X07 | 多租户 / 计费 / 集群 HA / 分布式调度 | 非当前产品目标；见功能说明书 §6 |
| X08 | 数据库边界（JDBC/ORM/Redis） | 仓内无 DB；`#3` 判定"未来提 DB 必须先有读写场景" |
| X09 | Lanterna TUI（旧 TODO-spec 条目） | 已被 JLine 3 TUI 取代；旧条目为陈旧记录 |
| X10 | 任务看板 / 多 Tab 作为"近期必做" | 无用户需求证据；降为待定 H21，不占当前窗口 |
| X11 | 为 `AgentExecutor` 保留兼容签名 | 若 H16 决定收敛，则旧签名不保兼容；以 parity 用例保证行为 |

---

## 5. 旧板逐项归并映射（证明没有丢条目）

### 5.1 `todos/TODO-kernel-r0.md`

| 旧条目 | 新 ID | 状态 |
|---|---|---|
| §0 全部已完成里程碑 | D01/D13 | 已完成 |
| §1 specs/004 建档 | H15 | 待定 |
| §1 `graphKernelEnabled` 默认置真 | H02/H03/H15 | 待定 |
| §1 ask/askAsync/run 门面迁移 | H15 | 待定 |
| §1 facade 消费迁移 | H15 | 待定 |
| §1 executor 测试基线迁移 | H16 | 待定 |
| §1 legacy AgentExecutor 保留策略 | H16 | 待定 |
| §1 KernelDelegates 收敛 | H17 | 待定 |
| §1 双运行时并存期文档化 | I08 | 进行中 |
| §2 WAL/Checkpoint | D03 | 已完成 |
| §2 verifyPost(g) | D04 | 已完成 |
| §2 HITL gate | H10 | 待定 |
| §2 多级预算 D12 | H11 | 待定 |
| §2 LedgerScopeValidator D11 | H12 | 待定 |
| §2 cancel 进循环 | D02 | 已完成 |
| §2 子 agent 同契约递归 | H13 | 待定 |
| §2 artifact 多产物语义 | H14 | 待定 |
| §3 pipeline kinds spec | H17 | 待定 |
| §3 Transport 超时/重试策略化 | H17 | 待定 |
| §3 ResponseDecoder 按 vendor 拆 | H17 | 待定 |
| §3 PromptProvider 实例化 | H17 | 待定 |
| §4 kernel-architecture 文档收口 | I08 | 进行中 |
| §4 AGENTS.md Key Source Files 增补 | I08 | 进行中 |
| §4 CHANGELOG 随里程碑更新 | I08 | 进行中 |
| §4 保持 `check` 全绿 | I07 | 进行中 |
| §5 bootstrap worker 高负载中断 | H29 | 待定（观察） |

### 5.2 `todos/TODO-spec.md`

| 旧主题/条目 | 新 ID | 状态 |
|---|---|---|
| Agent 运行循环重构 | D01 + H15 | 已完成骨架 / 迁移待定 |
| 上下文窗口滑动、Token 监控 | H22 | 待定 |
| 多模型路由（direct/round-robin/fallback）、Provider 健康检查 | H20 | 待定 |
| 模型 Provider：本地 Ollama/vLLM、Azure、自定义 SPI | H20 | 待定 |
| DAG 并行调度、任务重试/回退、动态重规划 | H27 | 待定 |
| Baseline web_search（DuckDuckGo） | D + H25 | 基础已有 / 修复待定 |
| web_search provider 重构（Tavily/Bing） | H25 | 待定 |
| 工具执行结果缓存 | H28 | 待定 |
| 动态工具加载 Hot-Plug | X05 | 放弃 |
| 远程 SSH 执行、Docker 沙箱 | H18 | 待定 |
| 命令白名单/黑名单、敏感信息过滤、审计日志持久化 | H19 | 待定 |
| 记忆检查点自动保存间隔、记忆摘要、时间衰减/锁定 | H08 | 待定 |
| CLI 彩色进度、任务进度条 | H30 | 待定 |
| TUI 任务看板、多 Session/多 Tab | H21 | 待定 |
| GraalVM native：Reflection 配置、镜像尺寸 | H26 | 待定 |
| CI/CD 流水线 | I07 | 进行中 |
| ShowDoc 同步 / API 文档自动生成 | X06 | 放弃 |
| E2E 覆盖（异常恢复/多模型） | I02/I04 | 进行中 |
| OpenTelemetry / Metrics / Tracing | H05 | 待定（平台统一） |
| 插件市场 / 工具市场 / Agent 模板仓库 | X05 | 放弃 |

### 5.3 `todos/TODO-20260628.md`

| 旧条目 | 新 ID | 状态 |
|---|---|---|
| 3 条"LLM 重复读文件"行为建议 | X03 | 放弃（保留观察） |
| 移除临时 DEBUG 日志 | H31 | 待定 |

### 5.4 `#173` 现存子单 / 相关单

| 工单 | 新 ID | 状态 |
|---|---|---|
| `#173` epic | I06 | 进行中（等 ADR 重排） |
| `#174` M1-S1 取消 | D02 | 已完成 |
| `#175` M1-S3 verifyPost | D04 | 已完成 |
| `#178` M1-S2 WAL | D03 | 已完成 |
| `#297` 核心闭环 | D05 | 已完成 |
| `#415` CLI 断言漂移 | I04 | 进行中 |
| `#461` CI 竞态 | D06 | 已完成 |
| `#514` 架构评审 | D12 | 已完成 |
| `#515` 冒烟评审 | D12 | 已完成 |
| `#516` 产品定义 | D12 | 已完成 |
| `#517` benchmark | I05 | 进行中 |
| `#521` 冒烟门禁 5 前置 | I03 | 进行中 |
| `#522` 幻觉误报 | D07 | 已完成 |
| `#663` 功能文档 + 唯一 TODO | I01 | 进行中 |
| `#664` 测试整理 | I02 | 进行中 |
| `#665` guardrail 参数绕过缺陷 | I09 | 进行中 |

---

## 6. 与 `#3` ADR 待定项的对应关系

| `#3` ADR 待定项 | 本表对应 |
|---|---|
| 模块分级 / 变更流程 | H01、H07 |
| 图内核冻结或继续 | H02（连带 H03/H10/H11/H12/H13/H14/H15） |
| sub-agent 语义统一 | H04、H13 |
| 门禁 / 可观测统一 | H05、I07 |
| 依赖方向硬约束 | H06 |
| 核心与门面边界（M2） | H15、H16 |
| 兼容层 | X04（已定不做） |

> **原则**：`#3` ADR 没落地的待定项，不在本表里假装排期；ADR 落地后由 atlas 把对应 H 项转成 `#173` 子单（带 due/责任人/验收），本表同步改状态。

---

## 7. 维护约定

1. 改状态必须贴证据（命令回显/测试名/工单号），否则视为未完成。
2. 新增事项先判断能否归入现有 H/I 项；归不进去才新增 ID。
3. 不新增"其它/杂项"行；不确定的一律放"待定"并写谁定。
4. 每周窗口收口时，atlas 只核本表；旧 `todos/*.md` 不再作为进度依据。
