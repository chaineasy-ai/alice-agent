# alice-agent-runtime — 会话宿主

把**会话语义**从门面/引擎里收口出来：一轮一锁、单写者、超时中止、new/resume 策略、
内核事件 → `StepEvent` 映射、健康快照。传输与门面依赖本模块，**不直接依赖 core**。

- **Gradle 工程**：`:alice-agent-runtime`
- **JPMS**：`alice.agent.runtime.main`
- **基础包**：`org.cland.alice.runtime`
- **协议契约**：[`alice-agent-proto`](../alice-agent-proto/README.md) ｜ 帧规范 [`PROTOCOL.md`](../docs/alice-agent-proto/PROTOCOL.md)

## 组件

| 类型 | 职责 |
|---|---|
| `AgentHost` | `AgentCommandDispatcher` 实现：一轮一锁（原子 CAS）、事件映射、收口帧（每轮必发 `DONE`，失败先 `ERROR`）、控制类 ack（steer/abort/new/feedback/compact/context）、`health()` 快照 |
| `engine.AgentEngine` | 引擎端口（ask/cancel/injectFeedback/clearMemory/compactContext/currentContext/events/lastUsage）——便于打桩与解耦 |
| `engine.CoreAgentEngine` | 适配 `core.Agent`（会话标识由组合根传入；usage 待 core 上报后接入） |
| `transport.InProcessTransport` | 进程内直调（无序列化，TUI/CLI/测试首选）；`await(cmd, timeout)` 阻塞收集到 `DONE` |
| `transport.StdioJsonlTransport` | stdio JSONL（RPC 模式，外部调度器驱动）：字节级按 `\n` 分帧、错误帧、单写者输出、`runBlocking()` |
| `HostHealth` | 健康快照：sessionId / roundActive / rounds / lastRoundMs / lastUsage / startedAt |

## 会话语义（与协议 §4 对应）

| 场景 | 行为 |
|---|---|
| 一轮进行中再发 `prompt` | `DispatcherBusyException`（→409）；调用方等待或改走 `steer` |
| 轮中 `steer` / `abort` / `new` | **不抢轮锁**，立即生效（steer → `injectFeedback`，abort → `cancel`，new → cancel + 清上下文） |
| 引擎抛错 | `ERROR` 帧 + 仍以 `DONE` 收口（**会话保留**，可继续下一轮） |
| 事件订阅晚到 | `RoundEventPublisher` 回放缓冲 ⇒ **先执行后订阅也不丢帧** |
| 命令 sessionId 与宿主不一致 | `CommandValidationException`（防跨会话串轮） |
| 未支持命令（Capability/Alignment/Routine/SubAgent） | `CommandValidationException`（按版本纪律后续追加） |

> 实现注记：一轮一锁用 `AtomicBoolean` CAS，**不是** `ReentrantLock`——轮次在工作线程收口，
> ReentrantLock 既要求"谁加锁谁解锁"，又对同一调用线程可重入（实测同线程二次 dispatch 会静默成功 ✗）。

## 用法

```java
var engine = new CoreAgentEngine(agent, sessionId);   // 或自定义 AgentEngine（测试打桩）
var host   = new AgentHost(engine);

var transport = new InProcessTransport();
transport.start(host);
var frames = transport.await(
        new ExecutionCmd.AcquireGoalCmd("ping", sessionId, "t-1"), 60_000);
transport.close();

HostHealth h = host.health();                          // 协议面 /health 的数据源
```

## 测试

```bash
./gradlew :alice-agent-runtime:test        # AgentHost 12 例 + InProcessTransport 3 例
```

## 源码布局

```
alice-agent-runtime/src/main/java/org/cland/alice/runtime/
├── AgentHost.java            — 会话语义（一轮一锁 / 事件映射 / 收口 / 健康）
├── HostHealth.java
├── RoundEventPublisher.java  — 回放缓冲发布器（package-private）
├── engine/AgentEngine.java, engine/CoreAgentEngine.java, engine/EngineEvents.java
├── compose/AgentComposer.java, compose/Ids.java   — 组合根（唯一装配点）+ 统一 ID
└── transport/InProcessTransport.java, transport/StdioJsonlTransport.java
```

## 相关

- [`docs/release/20260919/协议层转正与门面收敛-清单.md`](../docs/release/20260919/协议层转正与门面收敛-清单.md) — B 组工作项与验收
- [`docs/pi-integration/worker-http.md`](../docs/pi-integration/worker-http.md) — HTTP 契约（下一个 transport）
