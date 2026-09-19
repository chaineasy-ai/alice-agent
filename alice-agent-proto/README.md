# alice-agent-proto — 协议契约层

**对内/对外同一份契约**：命令（入方向）+ 事件（出方向）+ 分发端口 + codec + 版本纪律。
传输（InProcess / stdio / HTTP+SSE / ACP）与门面只依赖本模块，不依赖 core 实现。

- **Gradle 工程**：`:alice-agent-proto`
- **JPMS**：`alice.agent.proto.main`
- **基础包**：`org.cland.alice.agent.proto`
- **帧规范（唯一真源）**：[`docs/alice-agent-proto/PROTOCOL.md`](../docs/alice-agent-proto/PROTOCOL.md)
- **设计文档**：[`docs/alice-agent-proto/DESIGN.md`](../docs/alice-agent-proto/DESIGN.md)

## 组成

| 方向/职责 | 类型 | 说明 |
|---|---|---|
| 入方向 | `AgentCommand`（密封） | 六类驱动：Execution / Capability / Alignment / Control / RoutineTime / SubAgent |
| 出方向 | `event.StepEvent` + `event.StepEventType` | v1 帧：v/type/sessionId/traceId/seq/ts/payload/usage；7 类事件 |
| 端口 | `port.AgentCommandDispatcher` | `dispatch(cmd) → Flow.Publisher<StepEvent>`（实现归 `alice-agent-runtime`） |
| 端口 | `port.SessionTransport` | transport 生命周期（InProcess / stdio / HTTP / ACP） |
| 错误 | `port.CommandValidationException`(→400) / `port.DispatcherBusyException`(→409) | 协议面映射见 PROTOCOL §4 |
| codec | `codec.CommandCodec` / `codec.EventCodec` | JSON v1；Jackson 仅内部使用，不进入公开 API |

## 命令一览（v1 codec 覆盖 ★）

| 类型 | TUI `/` | 说明 |
|---|---|---|
| `ExecutionCmd.AcquireGoalCmd` ★ | `/run` | 自主目标循环（协议 `type=prompt`） |
| `ExecutionCmd.ExecuteRawCmd` ★ | `/exec` | 直接执行（`type=exec`） |
| `ControlCmd.SteerCmd` ★ | `/steer` | **人工插话**（忙时插队；`type=steer`） |
| `ControlCmd.AbortCmd` ★ | `/abort` | 中止当前轮·**会话保留**（`type=abort`） |
| `ControlCmd.ResetSessionCmd` ★ | `/new` | 新建会话（`type=new`） |
| `ControlCmd.ResumeSessionCmd` ★ | `/resume` | 显式续接（`type=resume`） |
| `ControlCmd.ClearContextCmd` ★ | `/clear` | 清上下文 |
| `ControlCmd.ViewContextCmd` ★ | `/context` | 查看上下文 |
| `ControlCmd.CompactContextCmd` ★ | `/compact` | 压缩上下文 |
| `ControlCmd.FeedbackCmd` ★ | `/feedback` | 人类在环反馈 |
| `ControlCmd.InterruptCmd` | Ctrl+C | 退出进程（≠ abort） |
| `CapabilityCmd.*` | `/skill` `/rules` `/prompt` `/reload` | 能力装载（v1 线格式暂未覆盖） |
| `AlignmentCmd.SwitchModelCmd` | `/model` | 运行配置 |
| `RoutineTimeCmd.*` | `/routine` | 定时调度 |
| `SubAgentCmd.*` | `/sub-agent …` | 多 Agent 管理 |

## 版本纪律（IDL）

1. 字段**只增不改不删**；`type` 枚举只加不删不改名；
2. 消费者必须忽略未知字段（codec 已默认容忍）；
3. 帧必须带 `v`；破坏性变更升 v2 并保留 v1 解析 ≥1 个版本；
4. 新命令/事件先进 `PROTOCOL.md` + `codec`，再进各 transport。

## 源码布局

```
alice-agent-proto/
└── src/main/java/org/cland/alice/agent/proto/
    ├── AgentCommand.java / ExecutionCmd.java / ControlCmd.java / …
    ├── event/StepEvent.java, event/StepEventType.java
    ├── port/AgentCommandDispatcher.java, port/SessionTransport.java, port/*Exception.java
    └── codec/CommandEnvelope.java, CommandCodec.java, EventCodec.java, Json.java
```

## 相关

- [`docs/pi-integration/README.md`](../docs/pi-integration/README.md) — 与 pi 生态的三层通信对接
- [`docs/architecture/extension-layer.md`](../docs/architecture/extension-layer.md) — 扩展层（二期）
- [`AGENTS.md`](../AGENTS.md) — 项目贡献指南
