/**
 * alice-core-planner 模块 — 双路径决策推理机。
 * <p>
 * 依赖 alice-model 实现 LLM 调用，对外暴露 PlannerService 作为规划入口。
 */
module alice.agent.alice.core.planner.main {
    exports org.cland.alice.core.planner;
    exports org.cland.alice.core.planner.model;
    exports org.cland.alice.core.planner.strategy;
    exports org.cland.alice.core.planner.tree;
    exports org.cland.alice.core.planner.sop;
    exports org.cland.alice.core.planner.budget;
}
