---
title: "alice-agent-command DESIGN"
summary: "Complete command set design for the sealed AgentCommand interface hierarchy"
read_when:
  - "implementing or modifying sealed command interface"
scope:
  - "alice-agent-command"
status: "active"
updated: "2026-06-13"
---
## 1. Alice AgentCommand 完整指令集

我们将指令按**驱动性质**重新划分为四大类，并明确 `/rules` 与 `/skill` 的联动关系。

```mermaid
classDiagram
    class AgentCommand {
        <<interface>>
        +String sessionId
        +String traceId
    }

    %% 1. 任务驱动 (Execution) - 消耗 Token 的实际工作
    class ExecutionCmd {
        <<sealed>>
    }
    AgentCommand <|-- ExecutionCmd
    ExecutionCmd <|-- AcquireGoalCmd : /run (自主循环)
    ExecutionCmd <|-- ExecuteRawCmd : /exec (直接 Shell/工具)

    %% 2. 能力装载 (Capability) - 需要 Reload 的静态/动态资源
    class CapabilityCmd {
        <<sealed>>
    }
    AgentCommand <|-- CapabilityCmd
    CapabilityCmd <|-- RegisterSkillCmd : /skill (加载 MCP/工具集)
    CapabilityCmd <|-- UpdateRulesCmd : /rules (加载预设 Prompt/规则)
    CapabilityCmd <|-- ReloadKernelCmd : /reload (强制刷新所有 Resource)

    %% 3. 运行配置 (Alignment) - 调整内核参数
    class AlignmentCmd {
        <<sealed>>
    }
    AgentCommand <|-- AlignmentCmd
    AlignmentCmd <|-- SwitchModelCmd : /model (切换 LLM 引擎)

    %% 4. 控制与反馈 (Control) - 生命周期与 HITL
    class ControlCmd {
        <<sealed>>
    }
    AgentCommand <|-- ControlCmd
    ControlCmd <|-- ResetSessionCmd : /new (重置会话)
    ControlCmd <|-- FeedbackCmd : /feedback (人类在环响应)
    ControlCmd <|-- InterruptCmd : Ctrl+C (强制终止)
    ControlCmd <|-- ClearContextCmd : /clear (清除上下文)
    ControlCmd <|-- ViewContextCmd : /context (查看上下文)
    ControlCmd <|-- CompactContextCmd : /compact (压缩上下文)

```

---

## 2. 核心 Use Case 指令详解

| 类别 | 指令 (UC) | 映射功能 | **Reload 逻辑 (能力装载细节)** |
| --- | --- | --- | --- |
| **能力 (Capability)** | **`/skill`** | 注册工具 | 触发 `ToolGateway` 扫描新工具定义，并通知 **P (Planner)** 更新 API Schema 认知。 |
| **能力 (Capability)** | **`/rules`** | 注册提示词 | 触发 `Memory` 加载 `.prompt` 文件，并通知 **P (Planner)** 重新 Rebase 整个 `System Prompt`。 |
| **能力 (Capability)** | **`/reload`** | 热重载 | 强制重新扫描 `alice-core-agent` 的所有外部能力源，确保本地 Dell R730 上的文件变更立即生效。 |
| **执行 (Execution)** | **`/run`** | 目标驱动 | 开启 P-E-M-T-V 循环。 |
| **执行 (Execution)** | **`/exec`** | 原生驱动 | 直接执行 `ls`, `git`, `nvidia-smi` 等底层指令。 |
| **对齐 (Alignment)** | **`/model`** | 引擎驱动 | 切换 LLM 后，同步刷新 **V (Verification)** 模块的审计敏感度。 |
| **控制 (Control)** | **`/clear`** | 上下文管理 | 清空 Session 的短期记忆（保留 System Prompt/Rules），重置 Token 计数器。 |
| **控制 (Control)** | **`/context`** | 上下文管理 | 从 Memory 拉取当前全量滑动窗口内的消息及 Token 占用统计，格式化输出。 |
| **控制 (Control)** | **`/compact`** | 上下文管理 | 将历史对话写入 WAL，提炼为 Summary 事实快照，释放 Context Window。 |
| **控制 (Control)** | **`/feedback`** | HITL 驱动 | 响应内核的 `AskHumanCmd`，解锁挂起状态。 |

---

## 3. 时序图：能力装载的通用驱动流 (Skill & Rules)

由于 `/rules` 和 `/skill` 的相似性，它们共用这一套“加载-通知-重载”的时序。

```mermaid
sequenceDiagram
    participant Facade as Facade (CLI/ACP)
    participant Alice as AliceAgent (App/UC)
    participant Manager as ResourceLoader
    participant Core as Agent (Kernel)
    participant Planner as P (Planner)

    Facade->>Alice: dispatch(CapabilityCmd)
    
    rect rgb(235, 245, 255)
        Note over Alice, Manager: 类似 Reload 的装载过程
        Alice->>Manager: 查找并加载资源 (Prompt文件/MCP配置)
        Manager-->>Alice: 返回解析后的能力实体
    end

    Alice->>Core: attach(Capability)
    
    rect rgb(240, 240, 240)
        Note over Core, Planner: 内核重载与认知对齐
        Core->>Planner: refreshSystemKnowledge()
        Planner->>Planner: 重新计算 Token 优先级与 API 限制
    end
    
    Core-->>Facade: AckCommand (Ready: /rules 或 /skill 生效)

```

---

## 4. 时序图：上下文管理驱动流 (/clear, /context, /compact)

```mermaid
sequenceDiagram
    participant User as User (TUI/CLI)
    participant Facade as Facade (TUI/CLI)
    participant Handler as CommandHandler
    participant Agent as Agent (Kernel)
    participant Memory as Memory (AgentSession)

    %% /clear
    User->>Facade: /clear
    Facade->>Handler: parse("/clear") → ClearContextCmd
    Handler->>Agent: dispatch(ClearContextCmd)
    Agent->>Memory: clearSession(sessionId)
    Memory-->>Agent: done
    Agent-->>Facade: context cleared
    Facade-->>User: "上下文已清除" + UI clear

    Note over User,Memory: ─────────────────────────

    %% /context
    User->>Facade: /context
    Facade->>Handler: parse("/context") → ViewContextCmd
    Handler->>Agent: dispatch(ViewContextCmd)
    Agent->>Agent: buildContextState()
    Agent->>Memory: getShortTerm(sessionId)
    Memory-->>Agent: short-term data
    Agent-->>Facade: formatted context info
    Facade-->>User: Markdown 表格 (Token/消息/变量)

    Note over User,Memory: ─────────────────────────

    %% /compact
    User->>Facade: /compact
    Facade->>Handler: parse("/compact") → CompactContextCmd
    Handler->>Agent: dispatch(CompactContextCmd)
    Agent->>Memory: putLongTerm(compact timestamp)
    Note over Agent: TODO: 触发 LLM 总结<br/>将历史提炼为 Summary
    Agent-->>Facade: "上下文压缩完成"
    Facade-->>User: "上下文压缩完成，释放 Token: xxxx"

```

---

## 5. 时序图：HITL 反馈流 (/feedback)

```mermaid
sequenceDiagram
    participant User as User (TUI/CLI)
    participant Facade as Facade (TUI/CLI)
    participant Agent as Agent (Kernel)
    participant Executor as AgentExecutor

    %% Agent 在 PPAO 循环中需要人工反馈
    Executor->>Agent: HITL 需要反馈
    Agent->>Executor: suspendForHuman()
    Note over Executor: CompletableFuture 挂起
    Executor-->>Agent: await feedback...

    Note over User,Executor: ─── 外部：用户输入 /feedback ───

    User->>Facade: /feedback <内容>
    Facade->>Agent: dispatch(FeedbackCmd)
    Agent->>Executor: resumeWithFeedback(feedback)
    Executor->>Executor: complete CompletableFuture
    Executor-->>Agent: feedback delivered
    Agent-->>Facade: "反馈已注入"
    Facade-->>User: "反馈已提交"

    Note over User,Executor: ─── Agent 继续 PPAO 循环 ───

```
