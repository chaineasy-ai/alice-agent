/**
 * 核心规划器 (alice-core-planner) — 双路径决策引擎。
 *
 * <p>将 Planner 从简单的"提示词包装器"提升为具有元认知能力的工程模块。
 *
 * <p><b>核心观点：</b>
 *
 * <ul>
 *   <li><b>{@link org.cland.alice.core.planner.PlannerService}</b> — 规划器根入口，真正的双路径决策引擎。
 *       负责"下一步做什么"的决策。
 *   <li>ReAct 是一个循环模板（在 {@code alice-core-agent} 的 {@code lifecycle} 包中），定义了 Reason→Act→Observe 模式。
 *       PlannerService 可以作为 ReAct 的 Reason 阶段实现。
 * </ul>
 *
 * <p><b>核心类图：</b>
 *
 * <pre>
 *   PlannerService (规划器根)
 *     ├── StaticPlanner (SOP 模板)
 *     └── StrategySelector (复杂度评估)
 *           ├── FastPathStrategy (System 1)
 *           └── SlowPathStrategy (System 2, MCTS)
 * </pre>
 *
 * <p><b>双路径决策流：</b>
 *
 * <pre>
 *   AgentContext → StaticPlanner (SOP 模板命中?)
 *                  ├── Yes → Plan (STATIC, 完全确定性)
 *                  └── No  → StrategySelector (复杂度评估)
 *                             ├── Low → FastPathStrategy (直接 LLM)
 *                             └── High → SlowPathStrategy (MCTS 树搜索)
 * </pre>
 *
 * <p><b>包结构：</b>
 *
 * <ul>
 *   <li>{@code model} — 模型抽象层 (ModelSession, ModelSupplier, ModelCapabilities)
 *   <li>{@code strategy} — 策略模式实现 (FastPathStrategy, SlowPathStrategy, StrategySelector)
 *   <li>{@code tree} — MCTS 思维树 (ThinkingNode, ThinkingTree)
 *   <li>{@code sop} — 静态规划 (SopRegistry, StaticPlanner)
 *   <li>{@code budget} — Token 预算控制 (TokenBudget)
 * </ul>
 */
package org.cland.alice.core.planner;
