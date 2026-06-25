---
title: "alice-core-agent 模块索引"
summary: "Alice Agent 核心引擎模块文档索引 — 架构设计、WAL & Checkpoint、生命周期、PPAO 循环、Hole 测试"
read_when:
  - "getting an overview of the alice-core-agent module"
  - "navigating core agent documentation"
  - "understanding the PPAO cycle, WAL subsystem, or agent lifecycle"
scope:
  - alice-core-agent
status: "active"
updated: "2026-06-26"
---

# alice-core-agent 模块文档索引

`alice-core-agent` 是 Alice Agent 的核心引擎模块，负责 Agent 的生命周期管理、PPAO 执行循环、WAL 预写日志与 Checkpoint 快照系统、以及子 Agent (ACP) 调度。

## 文档清单

| 文档 | 说明 |
|------|------|
| [DESIGN.md](./DESIGN.md) | 核心架构设计 — PPAO 循环、Lifecycle 接口、StepResult 密封层级、状态机 |
| [AWL&CheckPoint.md](./AWL&CheckPoint.md) | WAL + Checkpoint 双轨制记忆系统设计 — 预写日志、控制流快照、崩溃恢复、上下文熔炼 |
| [KEY_LOG.md](./KEY_LOG.md) | AgentExecutor 关键日志索引 — PPAO 各阶段的 INFO/WARN/DEBUG 日志标记速查 |
| [STORY.md](./STORY.md) | PPAO 提示词流故事 — 从前端页面编写场景看多层次提示词流转 |
| [Agent WAL RawMessage Storage & SFT Training Data Export Technical Specification.md](./Agent%20WAL%20RawMessage%20Storage%20%26%20SFT%20Training%20Data%20Export%20Technical%20Specification.md) | WAL RawMessage 消息规范 — 分布式追踪、SFT 训练数据导出的技术规范 (V1.1) |
| [e2e/hole_test_core_agent.py](./e2e/hole_test_core_agent.py) | Core Agent 模块 Hole 测试 — 模块边界探测脚本 |
| [e2e/scene-executor-endpoints.md](./e2e/scene-executor-endpoints.md) | Executor 端点场景文档 — 端到端测试用例 |

## 模块结构

```
org.cland.alice.core.agent
├── Agent.java                  # Agent 核心接口（ask, withMemory, withWal, compactContext 等）
├── AgentConfig.java            # Agent 配置
├── AgentContext.java           # Agent 运行时上下文
├── executor/
│   └── AgentExecutor.java      # PPAO 执行引擎（Perceive → Plan → Act → Observe）
├── lifecycle/
│   ├── Lifecycle.java          # 生命周期接口
│   ├── ReAct.java              # ReAct 循环
│   ├── ReActContext.java       # ReAct 上下文
│   ├── Action.java             # 动作封装
│   └── Observation.java        # 观察封装
├── memory/
│   └── AgentSession.java       # 会话内存（短期/长期记忆管理器）
├── prompt/
│   └── PromptManager.java      # 提示词管理
├── result/
│   └── StepResult.java         # 步骤结果密封层级（Continue / Finish / Failure）
├── wal/                        # WAL + Checkpoint 双轨制子系统
│   ├── WalSession.java         # 统一门面 — 组合 WalAppender + CheckpointManager
│   ├── WalStore.java           # 存储接口（抽象）
│   ├── FileWalStore.java       # 文件存储实现（JSONL + Checkpoint）
│   ├── InMemoryWalStore.java   # 内存存储实现（测试用）
│   ├── WalAppender.java        # 流式追加 + 批量刷盘
│   ├── WalCompactor.java       # 后台异步压缩清理
│   ├── Checkpoint.java         # 控制流快照数据模型
│   ├── CheckpointManager.java  # 5 个安全边界触发器
│   ├── RawMessage.java         # WAL 消息实体（OpenAI 兼容，支持 6 种角色）
│   ├── ToolCall.java           # 工具调用实体
│   ├── PromptMelter.java       # 三段式上下文熔炼
│   └── RecoveryEngine.java     # 崩溃恢复引擎
└── subagent/                   # 子 Agent (ACP 协议)
    ├── SubAgentManager.java
    ├── SubAgentRegistry.java
    ├── SubAgentRecord.java
    ├── SubAgentResult.java
    ├── SubAgentStatus.java
    └── SubAgentType.java

internal/acp/                   # ACP 协议内部实现
├── AcpClientWrapper.java
├── AcpConnection.java
└── AcpClientException.java
```

## 核心概念

### PPAO 循环

`AgentExecutor` 实现经典的四阶段闭环：

1. **Perceive（感知）** — 组装系统上下文 + 用户输入 + WAL 消息
2. **Plan（规划）** — 决定本次迭代策略（FastPath / SlowPath）
3. **Act（行动）** — 执行 Micro-ReAct 子循环（LLM 推理 → 工具调用 → 观察 → ... 迭代）
4. **Observe（观察）** — 收集宏观观察结果，进行事后审计
5. **Reflect（反思）** — 战略层面审查，决定继续还是终止

### WAL + Checkpoint 双轨制

WAL 子系统从 `v20260626` 起位于本模块的 `wal/` 包（原为 `alice-memory-vault` 模块），是 Agent 的工业级持久化基石：

- **WAL** — 只追加的 JSONL 日志流，记录每条原始消息（支持 6 种角色：system/user/assistant/tool/compact/tool_register）
- **Checkpoint** — 控制流快照，在 5 个安全边界触发，含变量快照与计划快照
- **RecoveryEngine** — 崩溃后加载最新 Checkpoint + 脏 WAL 差量重放
- **PromptMelter** — 将 WAL + Checkpoint 双轨数据熔炼为 LLM 提示词
- **消息角色** — `system` / `user` / `assistant` / `tool` / `compact` / `tool_register`
- **SpanType** — 所有消息通过 metadata.spanType 标记语义类别：`user_input` / `system_prompt_init` / `history_compact` / `llm_think` / `llm_final_response` / `tool_call` / `tool_call_result` / `tool_register` / `sub_agent_container` / `llm_sub_response`
- **会话 ID** — 使用 Snowflake 算法生成（`SnowflakeIdGenerator.generateSessionId()`），WAL 存储路径为 `~/.alice/wal/{sessionId}/`
- **SFT 训练数据** — WAL 原生支持两种导出场景：
  - Scenario A：纯对话（过滤推理中间过程，保留最终回复）
  - Scenario B：工具调用 + 多 Agent CoT（保留全部消息，含 tool_register 动态工具变更）

### 依赖关系

- `alice-core-agent` **被** `alice-memory-vault` 依赖（获取 WAL 类型和 AgentSession）
- `alice-core-agent` 依赖：model, core-planner, guardrail, tool-gateway, env-adapter

## 快速参考

| 操作 | 命令 |
|------|------|
| 编译本模块 | `./gradlew :alice-core-agent:compileJava` |
| 运行本模块测试 | `./gradlew :alice-core-agent:test` |
| Hole 测试 | `./gradlew :alice-core-agent:runHoleTest --args="all"` |
| 构建全项目 | `./gradlew build` |
| 运行全项目测试 | `./gradlew check` |

## 相关模块

- [alice-memory-vault](../alice-memory-vault/) — 记忆库（Episodic/Semantic/Procedural Vault, Dreaming Engine）
- [alice-facade-cmd](../alice-facade-cmd/) — CLI 门面
- [alice-facade-tui](../alice-facade-tui/) — TUI 门面
