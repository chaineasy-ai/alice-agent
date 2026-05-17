---
description: record your changes
---

# Changelog

## 20260517

### Breaking Changes

- `AgentCore/planner` 字段: 移除 `ReAct planner` 字段，仅保留 `PlannerService plannerService`。`planner()` getter 变更为 `plannerService()`。`withPlanner(ReAct)` 方法移除。
- `AgentExecutor` 规划接口: 从 `agentCore.planner().reason()` 变更为 `agentCore.plannerService().plan()`，`PlannerService` 是唯一的规划入口。
- `alice-core-planner`: 完全移除 `ReAct` 和 `ReActContext`。规划器模块 (`alice-core-planner`) 不再包含循环模板代码，专注于决策引擎职责（`PlannerService`、策略、MCTS 树）。
- `ReAct.proposeNext()`: 移除旧方法，所有规划通过 `PlannerService.plan()` 进行。
- `PlannerServiceSpec.groovy`: 移除旧 `ReAct` 类的向后兼容测试用例，更新集成测试直接构造 `PlannerService`（依赖 `ModelSession`、`StrategySelector`）。

### Changes

- `alice-core-agent/lifecycle/ReAct`: 新增 `@FunctionalInterface` 循环模板，定义 `reason(Map)` 单抽象方法 + 默认 `loop()` 模板（Reason→Act→Observe→...→FINISH）。提供 `from(PlannerService)` 适配器。
- `alice-core-agent/lifecycle/ReActContext`: 新增循环运行时上下文，跟踪迭代次数、token 消耗、行动历史。
- `alice-core-agent/AgentExecutor`: `act()` 阶段重构为 `actWithMicroReAct()`，嵌入 Micro-ReAct 战术循环（Dispatch→Observe→Reason→loop）。新增 `dispatchLlmInference()`、`dispatchToolCall()`、`microReActLoop()`、`microReActStep()`、`planToIntent()` 方法。
- `alice-core-agent/AgentContext.Phase`: 状态机支持 `ACTING → ACTING` 自循环，允许 Micro-ReAct 循环保持在 ACTING 阶段内迭代。
- `alice-core-agent/AgentCore`: 新增 `plannerService` 字段 + `withPlannerService(PlannerService)` 注入方法。
- `alice-core-planner/package-info`: 更新文档描述，移除 ReAct 相关内容，阐明规划器模块仅聚焦决策引擎。

### Fixes

- 无
