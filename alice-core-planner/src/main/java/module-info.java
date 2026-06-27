/**
 * alice-core-planner 模块 — 双路径决策推理机。
 *
 * <p>依赖 alice-model 实现 LLM 调用，对外暴露 PlannerService 作为规划入口。 {@code
 * org.cland.alice.core.planner.model} 包是内部 SPI，不对外导出 — 外部通过 {@code alice-model} 的 {@code
 * org.cland.alice.model.ModelSupplier} 桥接。
 */
module alice.agent.alice.core.planner.main {
  exports org.cland.alice.core.planner;
  exports org.cland.alice.core.planner.strategy;
  exports org.cland.alice.core.planner.tree;
  exports org.cland.alice.core.planner.sop;
  exports org.cland.alice.core.planner.budget;
  exports org.cland.alice.core.planner.model;

  requires org.slf4j;
  requires ch.qos.logback.classic;
  requires alice.agent.alice.model.main;
}
