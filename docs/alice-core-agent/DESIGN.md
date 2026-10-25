---
title: "alice-core-agent Module Architecture Design"
summary: "System design document for the core agent module - PPAO cycle, lifecycle, and state machine"
read_when:
  - "implementing or debugging the core agent lifecycle (PPAO cycle)"
  - "understanding Agent, AgentExecutor, and ReAct loop design"
scope:
  - "alice-core-agent"
status: "active"
updated: "2026-06-13"
---
基于你补充的 **Perceive-Plan-Act-Observe (PPAO)** 核心循环，这实际上是将经典的控制理论与 LLM 的推理能力结合。在 `alice-core-agent` 的工程实现中，我们需要确保每一个环节都有明确的**状态转移约束**。

以下是针对这一核心交互时序的深度设计：

---

## **1. alice-core-agent 增强类图**

为了支持“感知-规划-行动-观察-审计”的闭环，`Agent` 内部引入了 `Lifecycle` 管理和 `Result` 模式匹配。



```mermaid
classDiagram
    class AgentExecutor {
        -Strategy currentStrategy
        -AgentContext context
        +execute(Input input)
    }

    class Lifecycle {
        <<interface>>
        +onPerceive(Input i) Context
        +onPlan(Context c) Plan
        +onAct(Action a) Observation
        +onVerify(Result r) Boolean
    }

    class StepResult {
        <<sealed>>
        +Continue(Action next)
        +Finish(String answer)
        +Failure(Error err)
    }

    AgentExecutor ..> Lifecycle
    AgentExecutor --> StepResult : Match
```

---

## **2. 核心交互详细时序图 (PPAO 闭环)**

这个时序图重点体现了 **V 层 (Verify)** 作为“监督者”在 Action 执行前后的双重拦截。



```mermaid
sequenceDiagram
    participant A as Agent
    participant M as MemoryVault
    participant P as Planner
    participant V as Guardrail
    participant T as ToolGateway
    participant E as EnvAdapter
    participant OM as ObservationModel<br/>(规则型优先)

    Note over A: 1. Perceive（感知环境与记忆）
    A->>M: fetchContext(sessionId)
    A->>E: syncState()

    loop Macro-Iteration（战略层 · 事件驱动）
        Note over A: 2. Plan / 局部重分解（ADR-0001 外循环）
        A->>P: plan(dagState, events)
        P-->>A: TargetGoal + 前置条件 + 验收条件

        Note over A: 3. Verify (Pre)
        A->>V: interceptPlan(TargetGoal)
        A->>OM: precondition 推演（规则型）
        alt 前置不满足（T2）
            OM-->>A: 不可执行
            A->>P: 局部重分解该子树
        end

        rect rgb(240, 248, 255)
        Note right of A: Micro ReAct Loop（执行态闭环 · 目标内自主选工具）
        loop Micro-Iteration（直到达成验收条件或熔断）
            A->>T: dispatch(Action)
            T->>E: applyEffect()
            E-->>A: RawObservation
            A->>M: updateWorkingMemory(Observation)
            Note over A: 依 Obs 生成下一个微观 Thought/Action
        end
        end

        Note over A: 6. Verify (Post)
        A->>V: auditFinalResult(Observation)
        A->>OM: predicted vs actual 偏差
        Note over A: 7. Reflect
        A->>P: evaluate(VerificationScore)

        alt 触发重分解（T1 失败 / T3 Post-gate 拒绝 / T4 偏差超阈 / T5 依赖失效 / T6 目标变更）
            A->>P: 局部重分解（只改受影响子树，已完成的节点 id 不变）
        else 通过
            Note over A: 取下一个可执行目标（继续 Macro 循环）
        end
    end
```

> **口径**：本图即 `docs/architecture/adr/ADR-0001` 的双循环——Macro 层 = 外循环（planner 定"目标 + 前置 + 验收"，事件驱动重分解）；Micro 层 = 内循环（ReAct 在目标内自主选工具）。**入口不存在"按 prompt 长度选快慢"**。

## **3. 数据流转化图 (Transformation Flow)**

描述信息从“原始信号”到“知识沉淀”的流转过程：

```mermaid
graph TD
    Raw[User Input / Env Signal] -->|Perceive| Structured[AgentContext]
    Structured -->|Plan| StrategicGoal[Sub-Goal / Milestone]
    StrategicGoal -->|Verify Pre| AuthorizedGoal[Authorized Strategy]
    
    subgraph Execution_Session [Tactical ReAction Loop]
        AuthorizedGoal -->|Reason| MicroIntent[Micro Action]
        MicroIntent -->|Execute| SideEffect[Env Side Effect]
        SideEffect -->|Observe| LocalObs[Micro Observation]
        LocalObs -->|In-loop Update| MicroIntent
    end
    
    LocalObs -->|Session Exit| FinalResult[Cumulative Result]
    FinalResult -->|Verify Post| AuditedFact[Validated Fact]
    AuditedFact -->|Reflect| Knowledge[Memory / Experience]
```

---

## **4. Agent 核心决策状态机 (ASCII)**

引入了 **REVISION** 状态，用于处理当 V 层拦截或执行结果不符合预期时的逻辑修正。

```text
[ START ]
           |
           v
    +--------------+
    |  PERCEIVING  | <------------------------------------------+
    +--------------+                                            |
           |                                                    |
           v                                                    |
    +--------------+           (Strategic Re-Plan)              |
    |   PLANNING   | <---------------------------------------+  |
    +--------------+                                         |  |
           |                                                 |  |
           v                                                 |  |
    +--------------+         (Pre-Verify Blocked)            |  |
    |  VERIFYING   | ----------------------------------------+  |
    +--------------+                                            |
           | (Pass)                     | (Fail/Blocked)        |
           v                            v                       |
    +-------------------------------------------------------+   |
    |  ACTING (Tactical ReAction Session)                   |   |
    |                                                       |   |
    |  +-----------+       +-----------+       +---------+  |   |
    |  | Reasoning | ----> | Dispatch  | ----> | Observe |  |   |
    |  +-----------+       +-----------+       +---------+  |   |
    |        ^                                     |        |   |
    |        +----------(Micro Loop)---------------+        |   |
    |                                                       |   |
    +-------------------------------------------------------+   |
           |                                                    |
           | (Sub-Goal Done / Execution Error)                  |
           v                                                    |
    +--------------+                                            |
    |  REFLECTING  | -------------------------------------------+
    +--------------+        (Post-Verify & Strategy Update)
           |
           | (Reflection Pass) | (Reflection Fail / Result Mismatch)
           v                   v
       [ FINISH ]        +--------------+
                         |   REVISION   |
                         +--------------+
                                |
                                v
                        (Return to PERCEIVING)
```

---

## **5. 架构师实现建议 (Deep Insights)**

1.  **关于 Verify (Post) 的工程实现**：
    在 `alice-guardrail` 中，`verifyPost` 不仅是安全检查，更应包含 **自省 (Self-Correction)**。如果 `Observation` 与 `Plan` 的预期偏差过大，V 层应强制触发 `Reflect` 状态，而不是直接进入下一轮 Plan。
2.  **Env E 的副作用回滚**：
    在 `alice-env-adapter` 中，如果是一个受限沙箱（Sandbox），建议实现 `Snapshot/Restore` 能力。当 `Verify (Post)` 判定执行结果导致系统不稳时，能够配合 `alice-core-agent` 进行环境回溯。
3.  **异步 PPAO**：
    考虑到 LLM 推理（Plan）和工具执行（Act）都可能是长耗时操作，建议在 `AgentExecutor` 中使用 **Reactor/Mutiny** 等响应式框架，将整个 PPAO Loop 建模为一个 `Flux` 或 `Multi` 流，方便进行超时控制和背压处理。
使用 vert.x
