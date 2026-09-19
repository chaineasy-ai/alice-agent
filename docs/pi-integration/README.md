---
title: "Pi 通信对接协议（Agent 侧实现参考）"
summary: "pi 常驻 agent 的三层通信方式（stdio RPC / 常驻 worker HTTP / 调度唤醒与插话通道）的协议、语义与踩坑，供 alice-agent 实现同构接口"
read_when:
  - "实现 agent 的 RPC / 常驻 HTTP 对接层"
  - "让 alice-agent 被外部调度器驱动（prompt / steer / abort / 健康检查）"
  - "排查 agent 通信类问题（无响应、插话丢失、会话重来）"
scope:
  - "docs"
  - "alice-core-agent"
  - "alice-facade-tui"
status: "active"
updated: "2026-09-19"
---
# Pi 通信对接协议（Agent 侧实现参考）

> **来源**：cland-reverser 项目与 pi（C-Land 编码 agent CLI）的实战对接（2026-09）。
> **参考实现**：`scripts/pi_worker.py`（常驻 worker）、`src/infra/pi_client.py`（调度侧客户端）、
> `src/infra/pi_launcher.py`（进程拉起/环境兜底）；pi 官方协议见其 `docs/rpc.md`（本机：
> `/mnt/data/npm/global/lib/node_modules/@earendil-works/pi-coding-agent/docs/rpc.md`）。
> **目标**：alice-agent 实现**同构**的三层接口后，可被同一类调度器（monitor / 看板 / CLI / 其他 agent）直接驱动。

---

## 0. 设计原则（先读这四条，再读协议）

1. **进程解耦**：agent 是独立常驻进程，调度器一重启**不掐断** agent（上下文不重来、不重复付费）。
   调度器只通过 HTTP 驱动，agent 挂了才由调度器"自愈拉起"。
2. **单一写者**：agent 的 stdin 只允许一个写者——所有 prompt / steer / abort 经同一把写锁串行，
   否则并发写入会"插错轮次"（人工插话插到别的会话上下文里 ✗）。
3. **一轮一锁**：同一时刻只允许一轮 prompt 在跑。忙时的两类语义：
   - 新 prompt 带 `streamingBehavior=steer` → 在当前助手回合的工具调用结束后、下一次 LLM 调用前插入；
   - 不带 → 拒绝（错误），调用方等待或改投插话通道。
4. **签收权在最后一跳**：投递链路（调度器 → 队列 → agent）中，`delivered` 状态只能由
   **真正把消息交给 agent** 的一方写；中间跳（入队/唤醒/排队）不得改终态——否则消费者按
   "pending"过滤时永远看不到，消息**静默丢失**（PIT-049 实锤：页面插话返回成功但 pi 毫无反应）。

---

## 1. 三层通信总览

```
                ② HTTP（本机 127.0.0.1:8094）              ① stdio JSONL（RPC）
 调度器/看板 ────────────────────────────────▶  worker 常驻进程 ──────────────▶ agent 进程
 （monitor）                                    （进程守护 + API 面）            （pi/Alice）
      ▲                                              │
      │  ③ 唤醒/插话通道（本机 HTTP :8093 + DB 收件箱） │ 自愈拉起（worker 不在时）
      └──────────────────────────────────────────────┘
```

| 层 | 传输 | 对端 | 用途 | 本项目形态 |
|---|---|---|---|---|
| ① | stdin/stdout（严格 JSONL） | agent 进程本体 | 驱动单轮 + 事件流 | `pi --mode rpc`（见 [rpc-stdio.md](./rpc-stdio.md)） |
| ② | 本机 HTTP（默认 `:8094`） | 常驻 worker 进程 | 稳定 API：健康 / 轮次 / 插话 / 新会话 | `scripts/pi_worker.py`（见 [worker-http.md](./worker-http.md)） |
| ③ | 本机 HTTP（默认 `:8093`）+ DB 收件箱 | 调度器 | 唤醒、人工插话、异常自愈 | `scripts/agent_monitor.py` |

**为什么要拆三层**（而不是调度器直接管道驱动 agent）：
- ① 是**通用能力**（任何宿主都能用），但裸管道没有健康检查/幂等/重连，调度器重启就丢会话；
- ② 把"进程生命周期 + 会话身份 + 事件缓冲 + 权限面"收口成一个稳定 HTTP 面，**调度器与 agent 解耦**；
- ③ 让"人"可以随时插话、让"看门狗"可以自愈，而不用碰 ② 的内部。

---

## 2. 会话与生命周期约定（跨层）

| 约定 | 口径 | 为什么 |
|---|---|---|
| **启动即新会话** | 每次进程启动自动生成 `souler-YYYYMMDD-HHMMSS` 新会话名 | 续接旧会话会把越滚越大的上下文反复送进模型（贵 ✗）；上下文由首轮 dossier 现灌 |
| **续接必须显式** | `--session-id X`（参数）> `SOUL_PI_SESSION_ID`（环境变量）> 自动新会话 | 防止环境残留导致"以为重启换了会话、实际续接了旧的"（PIT-045：需对残留 env 显式告警） |
| **会话落盘** | `--session-dir <dir>` 下的 `<ISO时间戳>_<会话名>.jsonl`；正文**首轮消息才落盘** | 排查/回放靠它；0 轮时目录里没有文件是正常的 |
| **重启语义** | 重启 worker = 换会话；重启调度器/monitor ≠ 动 worker | 解耦的核心收益（PIT-042 同族：调度器不得把自己的会话名传给 worker） |
| **进程自愈** | 调度器发现 worker 不可达 → detached 拉起并等 `/health` 就绪；端口被占 → 和平退出（不恐慌） | 开机/cron 链路无人值守 |
| **超时处理** | 单轮超时（预算 60s~120s）→ 发 `abort`，**会话保留** | 超时不是崩溃；下一轮还能继续用同一上下文 |

---

## 3. 观测与埋点（推荐照搬到 Alice）

三层通信只有配上观测才是可运维的。参考实现的口径：

**① worker `/health`（调度器每 5s 轮询）**
`{ok, alive, pi_pid, session_id, streaming, thinking, model, uptime_s, last_round_s, events, stderr_tail, last_round}`
——`stderr_tail` 是关键：agent 失败时"进程在但秒退/认证失败"这类问题一眼可见。
`last_round = {mode, seconds, tools, skills, req, tokens, cost, timeout, ts}`。

**② 操作埋点表（`ops_events`，我们用它当"行为轨迹"的补集）**
只记"没有归属的动作"（消息/决策/状态/审计在各自业务表里；读侧归并成一条时间轴）。字段：
`ts, actor, kind, op, subject, account, ok, ms, trace_id, ref_type, ref_id, detail`。写入口径：

| actor | kind | op 例 | 说明 |
|---|---|---|---|
| `pi` | `tool` | `tool:bash` | 每次工具调用（detail=命令/路径前 160 字）；失败再落一条 `ok=0` |
| `pi` | `skill` | `skill:read` | 读到 `…/skills/<name>/…` 即记（**渐进式披露的依从率**可统计） |
| `worker` | `round` | `prompt:event` / `prompt:dossier` | 轮末汇总：`req=«前60字» tools=[…] tokens=… cost=$… skill=[…]` |
| `human` | `steer` | `delivered` / `failed` | 人工插话投递 |
| `monitor` | `skip` / `block` / `tick` | `skip:已回应` | 跳过原因必须留痕（"为什么没回"是排查最常问的一句） |

**③ trace_id 串联**：调度器每轮生成 `t<对象id>-<epoch>` 随 prompt 传进来，agent 把本轮所有埋点
（轮次/工具/插话）都挂同一个 trace_id ⇒ 一次查询看全"唤醒 → 工具 → 决策"序列。
（Alice 侧已有 `AgentCommand.traceId()`，可直接沿用同一口径。）

**④ 日志（worker 侧 stdout → 单文件）**：启动 `🧩 argv: …`（挂了哪些 agent/技能/会话目录一眼可核验）；
每轮 `▶ 轮次 mode=… trace=… req=«…»` 与 `✓ 轮次完成 5.2s tools=… tokens=… skills=[…]`；
读技能 `📚 读取技能：<name>`。**多线程只经一把日志锁**（避免交错）。

---

## 4. Alice Agent 落地清单

> **落地进度（2026-09-19）**：本文建议已被吸收执行——协议契约层 `alice-agent-proto`（命令/事件/端口/codec）、
> 会话宿主 `alice-agent-runtime`（AgentHost + InProcess/Stdio 传输 + 组合根 + 启动横幅）、
> HTTP RPC 2.0 `alice-facade-rpc`（`/api/v1/*` + SSE，bootstrap `--facade rpc`）均已落地。
> 工作项/验收/剩余项见 [`docs/release/20260919/协议层转正与门面收敛-清单.md`](../release/20260919/协议层转正与门面收敛-清单.md)（防口径漂移 ✓）。

### 4.1 最小闭环（MVP，先让调度器能驱动）

| 能力 | 落地形态建议 | 对应现有模块 |
|---|---|---|
| stdio RPC 服务 | 新模块 `alice-facade-rpc`（或 bootstrap 加 `--mode rpc`）：stdin 逐行读 JSON → `AgentCommand`；stdout 逐行写事件 | `alice-agent-proto`（契约层，原 `alice-agent-command`，2026-09-19 更名）+ `alice-agent-runtime`（会话语义）+ `alice-core-agent`（`Agent.events()`） |
| 常驻 HTTP 面 | 轻量 HTTP server（Vert.x 已在 TUI 依赖里）：`/health` `/prompt` `/steer` `/abort` `/new_session` `/shutdown` | `alice-facade-tui` 的 Vert.x 或新 `alice-facade-http` |
| 单写者 + 轮锁 | `ReentrantLock` 包住 stdin/命令入口；轮级 `Semaphore(1)` | `alice-core-agent` |
| 事件缓冲 | 环形缓冲（默认 600 条）供 `/prompt?events=true` 回传留档 | `EventStream` |
| 轮次汇总 | 每轮统计 tools/tokens/cost，暴露在 `/health.last_round` | `alice-model` 的 usage + `EventStream` |
| 超时→abort | 单轮预算 + 超时取消（会话保留） | `Agent.cancel()` |

### 4.2 指令映射（建议）

| pi RPC 命令 | Alice `AgentCommand`（现有） | 备注 |
|---|---|---|
| `prompt` | `ExecutionCmd`（/run、/exec） | 带 `traceId`、`sessionId` |
| `steer` | `ControlCmd`（新增子类型，如 `SteerCmd`） | 忙时插队语义需在 executor 实现 |
| `abort` / `clear_queue` | `ControlCmd`（/cancel、/new 同族） | 会话保留 |
| `new_session` | `ControlCmd`（/new） | 清上下文，进程不重启 |
| `set_model` / `set_thinking_level` | `AlignmentCmd`（/model） | 运行中调整 |
| `bash` | `ExecutionCmd` 的工具路径（`alice-tool-gateway`） | RPC 直连 bash 仅调试用 |
| `get_state` / `get_session_stats` | 新增只读查询指令或健康面 | 建议走 HTTP 面，不进对话 |

### 4.3 实现顺序（每步可独立验收）

1. **RPC 雏形**：`--mode rpc` 起进程；实现 `prompt` 命令 + `agent_start/agent_end/message_update` 事件
   → 验收：`printf '{"type":"prompt","message":"hi"}\n' | alice --mode rpc` 能出事件流。
2. **轮次语义**：`abort` / `new_session` / 忙时 `streamingBehavior=steer`；单写者锁
   → 验收：轮次进行中发 steer 不丢、插到当前轮之后。
3. **HTTP 面**：`/health` + `/prompt` + `/steer` + `/abort`，加轮锁与超时
   → 验收：`curl` 全通；`/health` 在 agent 挂掉时 `alive=false`。
4. **会话落盘**：`--session-dir` + `--session-id`，首轮落盘
   → 验收：会话文件可在新进程里显式续接。
5. **观测埋点**：轮次/工具/技能三条（见 §3）
   → 验收：按 trace_id 能拉出完整一轮的时间线。
6. **自愈**：端口占用和平退出 + 调度器侧 ensure_up（detached 拉起）
   → 验收：kill 掉 agent 进程，调度器 20s 内自动恢复。

### 4.4 Java 侧注意事项

- **JSONL 解析别用 `BufferedReader.readLine()` 的宽松语义**：pi 协议要求**只以 `\n` 分帧**
  （允许 `\r\n` 时剥 `\r`；`U+2028/U+2029` 在 JSON 字符串里合法，不能被当行分隔符——Node 的
  `readline` 因此不合规）。推荐 `BufferedInputStream` 手动按 `\n`(0x0A) 切分 + 严格 UTF-8 解码。
- **进程环境**：拉 agent 时前置稳定 node/工具链目录、补齐 API key、强制 UTF-8
  （`LANG=C.UTF-8`、`PYTHONIOENCODING=utf-8` 对脚本 agent 同理），否则"交互 shell 能跑、开机/cron 跑不了"。
- **端口占用 = 已有实例**：启动时 `bind` 失败应**和平退出并打一行日志**，不要 traceback 恐慌。
- **健康面必须暴露 agent 子进程的 stderr 尾部**（`stderr_tail`）与最后轮次摘要，否则"进程在但已经废了"很难发现。

---

## 5. 踩坑清单（对接前必读，来自 cland-reverser 实战）

| # | 坑 | 结论 |
|---|---|---|
| 1 | 调度器重启掐断 agent（旧形态：agent 是调度器子进程） | agent 必须独立进程；调度器只 HTTP 驱动 |
| 2 | cron/开机链路找不到 `node`（`pi` 是 node 脚本），表现为"agent 突然不回复" | 启动器统一解析**稳定安装目录**（排除 fnm multishell 临时路径）+ PATH 前置 |
| 3 | API key 只写在 `~/.bashrc` ⇒ 开机链路认证失败、agent 秒退 | 启动器从 shell 配置兜底读取（只读不回显）；失败原因要进 `/health.stderr_tail` |
| 4 | 并发写 stdin 导致插话插错轮次 | 单一写者锁 |
| 5 | 重启续接旧会话 → 上下文越滚越贵 | 启动即新会话；续接必须显式 |
| 6 | 中间跳提前把投递标 `delivered` ⇒ 最终消费者看不到 pending，插话静默丢失（PIT-049） | 签收权只归最后一跳 |
| 7 | 角色/人设文件两份漂移，改仓库那份不生效 | 单一真源 + 软链（PIT-044） |
| 8 | 以为技能/规则会自动进上下文 | 技能是**渐进式披露**：system prompt 只有名字+描述，正文要模型自己读；不强制就不会读（PIT 观测：读技能埋点 `skill:read`） |
| 9 | 面板/调度器把 `busy` 当失败 | 忙时要么带 `streamingBehavior=steer`，要么排队；返回结构里区分 `busy` 与 `error` |

---

## 6. 相关文档

- [rpc-stdio.md](./rpc-stdio.md) — 层①：stdio JSONL 的命令/事件帧完整规范
- [worker-http.md](./worker-http.md) — 层②：常驻 worker HTTP API 契约与轮次语义
- [../acp/README.md](../acp/README.md) — ACP（Agent Client Protocol）标准对比：ACP 面向"编辑器↔编码 agent"，
  本协议面向"调度器↔运营 agent"，两者可并存（对外 ACP，对内本协议）
