/**
 * 核心规划器 (alice-core-planner) — 双路径决策引擎。
 *
 * <p>将 Planner 从简单的"提示词包装器"提升为具有元认知能力的工程模块。
 *
 * <p><b>核心类图：</b>
 *
 * <pre>
 *   PlannerService ──→ StrategySelector ──→ DecisionStrategy (接口)
 *                        ├── FastPathStrategy (System 1)
 *                        └── SlowPathStrategy (System 2, 持有 ThinkingTree)
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
