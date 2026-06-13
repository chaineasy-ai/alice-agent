---
title: "TODO - alice-memory-vault"
summary: "Task board for the alice-memory-vault module: memory management"
read_when:
  - "tracking or updating memory vault tasks"
  - "working on episodic/procedural/semantic vaults, WAL, checkpoint, or summarization"
scope:
  - "alice-memory-vault"
status: "active"
updated: "2026-06-13"
---
# TODO-memory-vault: 双轨制 WAL + Checkpoint 记忆系统

> 遵循 [GFM Markdown 任务列表规范](../../../../TODO-spec.md)
> 设计文档: [AWL&CheckPoint.md](./AWL&CheckPoint.md), [DESIGN.md](./DESIGN.md)
> 消息格式: [OpenAI Chat Completions 消息对象规范](../OpenAI%20Chat%20Completions%20消息对象规范.md)
>
> 格式约定：`- [ ]` 待办 | `- [x]` 已完成 | `- [/]` 执行中 | `- [-]` 已取消 | `- [!]` 失败/阻塞
> 行内元数据：`[key:: value]`
> 缩进 4 空格 = 子任务层级
>
> ✅ **截至 2026-06-09: 已完成第一阶段编码 (10 个 Java 类, BUILD SUCCESSFUL)**



---

# 目标：将 WAL + Checkpoint 双轨制引入 Alice Agent 记忆系统

> 核心思想：**RawMessage 充当 WAL（预写日志）** + **Checkpoint 充当控制快照**
> 运行时：顺序 Append WAL，关键节点异步 Checkpoint
> 恢复时：快照提供底座（Base），WAL 提供差量（Delta），内存重放到崩溃点

## 一、数据模型层 (Data Model)

### □ 1.1 RawMessage — WAL 预写日志实体
- [x] 定义 RawMessage 实体 [priority:: critical] [ref:: AWL&CheckPoint.md] [verify:: 单元测试通过] [file:: RawMessage.java]
    - [x] `message_id: long` — 单调递增 ID (全局/会话级)
    - [x] `session_id: string` — 所属会话
    - [x] `role: enum(system|user|assistant|tool)` — 遵循 OpenAI 消息规范 [ref:: OpenAI Chat Completions 消息对象规范.md]
    - [x] `content: string | null` — 消息内容或 null（tool_calls 时）
    - [x] `tool_calls: List<ToolCall> | null` — 工具调用指令
    - [x] `tool_call_id: string | null` — 工具调用回传配对
    - [x] `name: string | null` — 角色标识
    - [x] `timestamp: long` — 记录时间戳
    - [x] `metadata: Map<String, Object>` — 扩展元数据（token 消耗、延迟等）
- [x] 定义 ToolCall 子实体 [priority:: high] [file:: ToolCall.java]
    - [x] `id: string` — 工具调用唯一标识
    - [x] `type: "function"`
    - [x] `function.name: string`
    - [x] `function.arguments: string` (JSON 字符串)
- [ ] RawMessage 存储层 [priority:: high] [tool:: StorageBackend]
    - [x] WalStore 接口定义 [priority:: high] [file:: WalStore.java]
    - [x] InMemoryWalStore 实现 [priority:: high] [file:: InMemoryWalStore.java]
    - [ ] PostgreSQL / 生产级实现

### □ 1.2 Checkpoint — 控制流快照实体
- [x] 定义 Checkpoint 实体 [priority:: critical] [ref:: AWL&CheckPoint.md] [file:: Checkpoint.java]
    - [x] `checkpoint_id: long` — 快照 ID
    - [x] `session_id: string` — 所属会话
    - [x] `last_applied_message_id: long` — 指针：最后一条已处理的消息 ID
    - [x] `state_node: string` — 当前状态节点 (PLANNING / TOOL_EXEC / VERIFYING / FINISHED)
    - [x] `variable_snapshot: Map<String, Object>` — 变量快照 (retry 计数、当前目标等)
    - [x] `plan_snapshot: String` — Plan 任务树快照 (序列化)
    - [x] `created_at: long` — 创建时间戳
- [x] Checkpoint 存储层 [priority:: high] [已包含在 WalStore 中]
    - [x] 写入: `save(Checkpoint) → checkpoint_id` (同会话覆盖旧快照)
    - [x] 读取最新: `loadLatest(sessionId) → Checkpoint | null`
    - [x] 历史查询: `listHistory(sessionId, limit) → List<Checkpoint>`
    - [x] 清理策略: 保留最近 N 个快照

## 二、WAL 运行时引擎 (WAL Runtime)

### □ 2.1 WAL Appender — 预写日志写入器 [done]
- [x] 同步 Append 接口 [priority:: critical] [verify:: 写入后立即可读] [file:: WalAppender.java]
    - [x] 流式追加：Agent 每产生一条交互消息即写入
    - [x] 消息顺序保证：同一 session 内严格递增
    - [x] 批量刷盘优化
- [x] WAL 读取接口 [priority:: high]
    - [x] 基于 last_applied_id 的增量读取
    - [x] 全量回放: `replayAll(sessionId) → List<RawMessage>`
    - [x] 差量回放: `replayFrom(sessionId, afterId) → List<RawMessage>`
- [ ] WAL 压缩与清理 [priority:: medium]
    - [ ] 已确认 (Last_Applied_ID 之前) 的旧消息标记可压缩
    - [ ] 后台线程异步清理

### □ 2.2 Message 与 OpenAI 规范对齐 [done]
- [x] RawMessage → OpenAI Message 转换 [priority:: high] [ref:: OpenAI Chat Completions 消息对象规范.md] [file:: RawMessage.java]
    - [x] 纯文本消息映射 (system/user/assistant.content)
    - [x] tool_calls 消息映射 (assistant.content=null, tool_calls 数组)
    - [x] tool 响应消息映射 (tool_call_id 配对)
    - [x] 多模态消息映射 (content 数组 → image_url)
- [x] OpenAI Message → RawMessage 转换 [priority:: high] [通过工厂方法实现]
    - [x] 全字段保留，无信息损失
- [x] 消息链路完整性校验 [priority:: medium] [file:: WalAppender.validateLinkage()]
    - [x] tool_call_id 配对检查：每个 assistant.tool_calls 有对应的 tool 响应
    - [x] 缺失消息检测与告警

## 三、Checkpoint 管理器 (Checkpoint Manager)

### □ 3.1 Checkpoint 触发器 [done]
- [x] 安全边界 (Safe Point) 定义 [priority:: critical] [ref:: AWL&CheckPoint.md] [file:: CheckpointManager.java]
    - [x] 每个 ReAct 循环结束时触发 — `onReActCycleEnd()`
    - [x] 收到用户新输入时触发 — `onUserInput()`
    - [x] 工具调用返回时触发 — `onToolReturn()`
    - [x] 异常/错误捕获时触发 — `onError()`
- [x] Checkpoint 生成流程 [priority:: critical]
    - [x] 采集当前内存控制变量 (current_node, retry_count 等)
    - [x] 序列化 Plan 任务树快照
    - [x] 锁定当前 Last_Applied_Message_ID
    - [x] 写入存储 (异步，不阻塞主线)
- [x] 幂等性保证 [priority:: high]
    - [x] 同一 safe point 多次触发不产生重复快照 (lastState 追踪)
    - [x] 并发写入保护 (synchronized 方法)

### □ 3.2 Checkpoint 恢复引擎 [done]
- [x] 恢复主流程 [priority:: critical] [verify:: 模拟崩溃后原地恢复] [file:: RecoveryEngine.java]
    - [x] 加载最新 Checkpoint
    - [x] 根据 last_applied_message_id 读取脏 WAL 段 (Msg_ID > last_applied_id)
    - [x] 按序重放 (Replay & Redo) 脏消息，修复内存状态
    - [x] 恢复完成后生成新 Checkpoint (CP_B)，推进 last_applied_id
    - [x] 向 Planner 报告恢复完成
- [x] 恢复边界处理 [priority:: high]
    - [x] 无 Checkpoint 时：全量从 WAL 开头重放 (fullReplay)
    - [x] 脏 WAL 段为空时：直接使用 Checkpoint 状态 (CLEAN_RECOVERY)
    - [x] 重放失败时的降级策略
- [ ] 恢复性能指标 [priority:: medium]
    - [ ] 恢复耗时监控
    - [ ] 重放消息数统计

## 四、上下文熔炼 (Prompt Melter)

### □ 4.1 双轨上下文组装 [done]
- [x] 三段式 Prompt 结构 [priority:: high] [ref:: AWL&CheckPoint.md §4] [file:: PromptMelter.java]
    - [x] **静态主干区**: System Prompt + Static SOP + Tool Schemas (传入参数)
    - [x] **快照状态区**: Checkpoint 还原出的结构化状态变量 (buildSnapshotState)
    - [x] **极短消息尾部**: 最近 2 轮纯文本对话 (buildShortTail, ≤400 chars)
- [x] 缓存最优策略 [priority:: high]
    - [x] MeltedPrompt.cacheKey() — session checkpoint_id 组合
    - [x] 静态主干区固定不变，适配 Disk Prompt Cache

### □ 4.2 消息压缩与提炼
- [ ] 历史消息快照化 [priority:: medium]
    - [ ] 将 Checkpoint 之前的完整消息压缩为状态摘要
    - [ ] 错误日志归约为结构化摘要 (非散列原文)
- [ ] 语义向量化归档 [priority:: medium]
    - [ ] 将压缩后的历史消息存入 Semantic Vault (向量库)
    - [ ] 支持语义检索历史上下文

## 五、与现有模块集成 (Integration)

### □ 5.1 与 AgentExecutor 集成 [done]
- [x] WAL 5 个生命周期钩子注入 [priority:: critical] [ref:: AgentExecutor.java]
    - [x] Perceive → append user 消息 + onUserInput Checkpoint
    - [x] Micro-ReAct Dispatch (LLM) → append assistant 回复
    - [x] Micro-ReAct Dispatch (Tool) → 执行前 append assistant_tool_calls，执行后 append tool 结果 + 工具返回 Checkpoint
    - [x] Observe (Macro) → onReActCycleEnd Checkpoint
    - [x] Fatal Error → onError 紧急 Checkpoint
    - [x] Micro-ReAct Revision → Checkpoint
    - [x] Micro-ReAct Circuit Breaker → 紧急 Checkpoint
    - [x] Post-Verify 失败 → Checkpoint
- [x] AgentExecutor 增加 WalSession 可选注入 [priority:: high]
    - [x] `withWal(WalSession)` 方法 — 链式调用注入
    - [x] `isWalEnabled()` / `wal()` 查询方法
    - [x] 所有 WAL 操作以 `if (wal != null)` 保护，零侵入原有逻辑

### □ 5.2 与 Memory Vault (三级记忆) 集成
- [ ] EpisodicVault 基于 WAL 重构 [priority:: high] [ref:: DESIGN.md]
    - [ ] EpisodicVault 成为 WAL 的查询视图
    - [ ] getRecentTrace() 基于 WAL replay 实现
- [ ] 合并 Consolidation 流程 [priority:: medium] [ref:: AWL&CheckPoint.md §4]
    - [ ] Checkpoint 触发后，后台启动 Consolidation
    - [ ] 将 last_applied_id 之前的消息提炼进 Semantic Vault
    - [ ] 成功路径提取为 Procedural SOP

### □ 5.3 与 ToolGateway 集成
- [ ] 工具调用追踪 [priority:: high]
    - [ ] 每个工具调用记录为 WAL 中的 tool_calls 消息
    - [ ] 工具返回记录为 tool 消息
    - [ ] tool_call_id 确保调用-返回配对
- [ ] 沙箱兼容性 [priority:: medium]
    - [ ] 沙箱内工具执行结果仍写入 WAL

## 六、测试与验证 (Testing)

### □ 6.1 单元测试 [done]
- [x] RawMessage 实体测试 [priority:: high] [9 tests] [file:: WalEntitySpec.groovy]
- [x] ToolCall 实体测试 [priority:: high] [3 tests]
- [x] Checkpoint 实体测试 [priority:: high] [4 tests]
- [x] WAL Append & Read 测试 [priority:: critical] [6 tests] [file:: WalStoreSpec.groovy]
- [x] Checkpoint Save & Load 测试 [priority:: critical] [3 tests]
- [x] WalAppender 消息追加测试 [priority:: critical] [6 tests] [file:: WalAppenderCheckpointSpec.groovy]
- [x] CheckpointManager 触发测试 [priority:: critical] [6 tests]
- [x] 恢复重放逻辑测试 [priority:: critical] [6 tests] [file:: RecoveryEngineSpec.groovy]
- [x] PromptMelter 熔炼测试 [priority:: high] [6 tests] [file:: PromptMelterSpec.groovy]
- [x] WalSession 集成门面测试 [priority:: high] [8 tests] [file:: WalSessionSpec.groovy]

### □ 6.2 集成测试
- [ ] 模拟崩溃恢复 E2E 测试 [priority:: critical] [verify:: 完整流程]
    - [ ] Step 1: 启动 Agent，执行若干工具调用
    - [ ] Step 2: 模拟进程崩溃 (Kill)
    - [ ] Step 3: 重启 Agent
    - [ ] Step 4: 验证状态恢复至崩溃点，无重复工具调用
- [ ] 消息链路完整性测试 [priority:: high]
    - [ ] 多轮 tool_calls 链的完整恢复
    - [ ] 穿插用户中断场景的恢复

### □ 6.3 性能测试
- [ ] WAL 写入吞吐 [priority:: medium] [verify:: 1000 msg/s 以上]
- [ ] Checkpoint 生成延迟 [priority:: medium] [verify:: < 50ms 不阻塞主线]
- [ ] 恢复耗时 [priority:: medium] [verify:: 1000 条脏消息恢复 < 1s]

## 七、文档与规范 (Docs)

### □ 7.1 文档同步
- [ ] 更新 DESIGN.md 补充双轨制设计 [priority:: medium] [ref:: DESIGN.md]
- [ ] 更新 module-tree.md 展示新增实体与类 [priority:: low]

### □ 7.2 Java 实现规范 [done]
- [x] 实体类定义 (POJO) [priority:: high] [file:: RawMessage.java, ToolCall.java, Checkpoint.java]
- [x] 服务类定义 [priority:: high] [file:: WalAppender.java, CheckpointManager.java, RecoveryEngine.java, PromptMelter.java, InMemoryWalStore.java, WalStore.java]

---

## 状态汇总

| 状态 | 计数 | 说明 |
|------|------|------|
| `- [x]` 已完成 | 44 | 10 Java 类 + 5 个 Spock spec (63 tests ✅) + AgentExecutor 集成 |
| `- [/]` 执行中 | 0 | — |
| `- [ ]` 待执行 | 12 | 剩余待实现（内存 vault 集成、性能测试、文档） |
| `- [!]` 失败/阻塞 | 0 | — |
| **总计** | **56** | — |

> 最后更新：2026-06-09
> ✅ **第三阶段完成**: AgentExecutor WAL 集成 — 5 个 PPAO 生命周期点注入
> 下一步：Memory Vault 三级记忆集成（§5.2）→ E2E 崩溃恢复测试（§6.2）
