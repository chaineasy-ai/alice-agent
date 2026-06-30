/**
 * alice-core-planner 模块 — 双路径决策推理机。
 *
 * <p>依赖 alice-model 实现 LLM 调用，对外暴露 PlannerService 作为规划入口。 SOP 相关的数据结构和静态规划器已移至 {@code
 * alice-memory-vault} 模块的 {@code org.cland.alice.memory.sop} 包。
 */
module alice.agent.alice.core.planner.main {
  exports org.cland.alice.core.planner;
  exports org.cland.alice.core.planner.strategy;
  exports org.cland.alice.core.planner.tree;
  exports org.cland.alice.core.planner.budget;
  exports org.cland.alice.core.planner.model;

  requires org.slf4j;
  requires ch.qos.logback.classic;
  requires alice.agent.alice.model.main;
}
