---
title: "WorldModel - Mental Simulator"
summary: "Design of the WorldModel as a mental simulator for Slow Path (System 2) deep reasoning"
read_when:
  - "implementing or debugging WorldModel slow path reasoning"
scope:
  - "alice-core-planner"
status: "active"
updated: "2026-06-13"
---
**WorldModel** 扮演着"心智仿真器"的角色 。它是实现 **Slow Path (System 2)** 深度推演的核心，其主要职责是将原本需要通过外部环境（Environment）获取的反馈，转化为 Planner 内部的可预测信息 。

具体职责可以拆解为以下四个维度：

### 1. 状态转移预测 (State Transition Prediction)

WorldModel 的核心函数是 $P(S', O | S, A)$ 。

*
**输入**：当前的 `AgentContext` 状态快照 $S$ 和 `Planner` 拟定的动作 $A$ 。


*
**职责**：在不真正执行 Action 的情况下，预测执行该动作后环境会变成什么样（下一个状态 $S'$），以及环境会返回什么样的观察结果（模拟的 Observation $O$） 。



### 2. 虚拟观察生成 (Virtual Observation Provider)

这是让 ReAct 范式在 Plan 阶段能够闭环的关键 。

*
**职责**：为 `ThinkingNode` 提供"假"的反馈数据 。


*
**意义**：如果没有模拟的 Observation，模型在推演时只能"空想"（Chain of Thought），容易产生幻觉；有了 WorldModel 提供的模拟反馈，推演就变成了"模拟实战"（Virtual ReAct），模型可以根据预测的错误结果提前修正路径 。



### 3. 分支价值评估 (Heuristic Evaluation)

配合 MCTS（蒙特卡洛树搜索）进行路径筛选 。

*
**职责**：评估某个动作序列达成目标的概率或潜在奖励（Reward/Value） 。


*
**实现**：它会判断当前路径是否触碰了 `alice-guardrail` 定义的禁区，或者是否在消耗了大量 `TokenBudget` 后仍未接近 `Milestone` 。



### 4. 幻觉屏障 (Hallucination Barrier)

作为模型推理与物理现实之间的缓冲带 。

*
**职责**：校验 LLM 产生的 Action 是否在逻辑上可行 。


*
**示例**：如果 LLM 在 `SlowPath` 中计划"读取文件 A"，但 `WorldModel` 根据当前的 `EnvSnapshot` 发现文件 A 根本不存在，它会直接反馈一个 `FileNotFound` 的模拟 Observation，强迫 Planner 在内存里就完成纠错，而不是等真正执政时才报错 。



---

### 在 P-E-M-T-V 架构中的位置

在你的架构中，WorldModel 实际上是 **Memory (M)** 和 **Environment (E)** 的一个交集抽象 ：

* 它利用 **Procedural Memory** 中的 SOP 来预测确定性流程 。


* 它利用 **Episodic Memory** 中的历史经验来预测不确定性反馈 。


* 它利用 **EnvSnapshot** 来保证预测的实时性 。



**总结：**
WorldModel 让 Planner 具备了"预见性"。它让 ReAct 从一种**外部交互范式**变成了 Planner 内部的**思考组件**。通过 WorldModel，`SlowPathStrategy` 可以在脑子里完成多次"试错"，最终只给 `AgentCore` 交付一条经过验证的、高质量的 `Plan` 。
