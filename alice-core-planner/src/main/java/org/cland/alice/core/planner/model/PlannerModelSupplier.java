package org.cland.alice.core.planner.model;

import org.cland.alice.model.ModelSupplier;

/**
 * 规划器模型供应者抽象 — 桥接 alice-model 的 {@link ModelSupplier}。
 *
 * <p>在 {@code alice-model.ModelSupplier.request(Call)} 基础上，增加双路径模型会话管理：
 *
 * <ul>
 *   <li>{@link #getReasoningModel()} — 复杂推理模型（System 2 / Slow Path）
 *   <li>{@link #getInstructionModel()} — 快速分类模型（System 1 / Fast Path）
 * </ul>
 *
 * <p>实现类需同时实现 {@code request(Call)} 方法完成实际模型调用。
 */
public interface PlannerModelSupplier extends ModelSupplier {

  /** 获取用于复杂推理的模型会话（System 2 / Slow Path）。 */
  ModelSession getReasoningModel();

  /** 获取用于快速分类或简单指令的模型会话（System 1 / Fast Path）。 */
  ModelSession getInstructionModel();

  @Override
  default String name() {
    return getClass().getSimpleName();
  }
}
