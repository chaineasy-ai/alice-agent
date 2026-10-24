---
title: "alice-agent 用例图（Use Cases）"
summary: "从 docs/功能说明书.md §3 的三条真实使用路径与 alice-agent-proto 的 13 个命令类反推的用例视图：参与者、系统边界内用例、外部系统依赖，以及子代理族的 7 个命令对应用例；只收有文档/代码背书的用例，不写愿景"
read_when:
  - "要一句话看懂 alice-agent 对外能做什么、谁在用、依赖哪些外部系统"
  - "决定某个能力该走 CLI / TUI / RPC 哪条路径"
  - "评审新增命令类（proto/*Cmd）时，判断它落在哪个用例里"
scope:
  - "alice-agent"
  - "alice-agent-proto"
  - "alice-core-agent"
status: "active"
updated: "2026-10-24"
---

# alice-agent 用例图

> **证据来源**：`docs/功能说明书.md` §3（三条真实使用路径，实跑验证）· `alice-agent-proto` 的 13 个命令类 · `alice-agent-runtime` 的 `AgentEngine` 方法面。
> **口径**：只收**有文档或代码背书**的用例；不写路线图里"计划中"的能力。Mermaid 无原生 UML 用例图，本文用 `flowchart` 近似（圆角=用例，胶囊=参与者）。

## 1. 参与者与主用例

> 中间三列（三条路径 → 能力用例 → 外部系统）都在 **alice-agent 系统边界**内；外部只有模型 API 与 MCP Server。虚线 = `include` 或外部依赖。

```mermaid
flowchart LR
  HU([人类用户])
  PR([上层程序 / 调用方])
  UC1(执行一次性任务<br/>CLI)
  UC2(交互式会话<br/>TUI · 真实 PTY)
  UC3(RPC 会话<br/>HTTP RPC + SSE)
  UC4(会话控制<br/>取消 · 插话 · 上下文)
  UC5(调用工具<br/>内置 / MCP)
  UC6(取用记忆与 SOP)
  UC7(派生并调度子代理)
  GUARD(护栏前置校验)
  MA[[模型 API]]
  MCPS[[外部 MCP Server]]

  HU --> UC1
  HU --> UC2
  PR --> UC3
  UC1 --> UC5
  UC2 --> UC5
  UC3 --> UC5
  UC2 -.->|include| UC4
  UC3 -.->|include| UC4
  UC5 -.->|include| GUARD
  UC1 --> UC6
  UC2 --> UC6
  UC3 --> UC6
  UC2 --> UC7
  UC3 --> UC7
  UC1 -.->|推理| MA
  UC2 -.->|推理| MA
  UC3 -.->|推理| MA
  UC5 -.->|MCP 工具| MCPS
```

## 2. 子代理族的用例（按 proto 命令类映射）

proto 里 7 个子代理命令 ⇒ 7 个用例，**这是"多 agent 协作"在 alice-agent 里的真实落点**：

```mermaid
flowchart LR
  K([Agent 内核]) --> S1(SpawnSubAgentCmd<br/>派生)
  K --> S2(PromptSubAgentCmd<br/>下发任务)
  K --> S3(SendToSubAgentCmd<br/>追加消息)
  K --> S4(ListSubAgentsCmd<br/>列出现存)
  K --> S5(GetSubAgentResultsCmd<br/>取结果)
  K --> S6(ConnectSubAgentCmd<br/>建立连接)
  K --> S7(CancelSubAgentCmd<br/>取消)
  S1 --> RT[(AgentHost<br/>InProcessTransport)]
  S2 --> RT
  S3 --> RT
  S5 --> RT
  S6 --> RT
  S7 --> RT
  S4 --> RT
```

## 3. 未收进来的（有理由）

| 事项 | 为什么不在图里 |
|---|---|
| SRE/运维视角用例（健康检查、指标） | 只有 `HostHealth`/`Metrics` 类，**没有文档化的使用路径**，不构成用例 |
| "自动编排多 agent 完成复杂任务" | 命令类支持派生/调度，但**没有端到端实跑的路径文档** ⇒ 愿望，不是用例 |
| Web/TUI 之外的图形前端 | 不存在 |
