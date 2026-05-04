package org.cland.alice.core.planner.strategy;

import org.cland.alice.core.planner.Plan;

import java.util.Map;

/**
 * 双路径决策策略接口，对应设计文档中的 {@code DecisionStrategy}。
 * <p>
 * 采用策略模式 (Strategy Pattern) 实现双路径决策：
 * <ul>
 *   <li>{@link FastPathStrategy} — System 1：快速路径，直接 LLM 调用或模板匹配</li>
 *   <li>{@link SlowPathStrategy} — System 2：慢速路径，MCTS 树搜索</li>
 * </ul>
 *
 * @see FastPathStrategy
 * @see SlowPathStrategy
 */
@FunctionalInterface
public interface DecisionStrategy {

    /**
     * 基于当前上下文做出规划决策。
     *
     * @param context 规划器上下文的只读快照（Map 视图）
     * @return 规划结果 Plan
     */
    Plan decide(Map<String, Object> context);
}
