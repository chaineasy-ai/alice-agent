/*
 * Alice Agent — SubAgentRegistry
 *
 * 父会话作用域、线程安全的子 Agent 生命周期注册表。
 */
package org.cland.alice.agent.subagent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子 Agent 注册表 — 父会话作用域、线程安全。
 *
 * <p>使用 {@link ConcurrentHashMap} 实现，以子 Agent ID（UUID）为键。 所有变更操作为原子操作，支持被多个线程安全访问。
 *
 * <p>最大并发子 Agent 数量：默认 5（通过 {@link #MAX_CONCURRENT} 配置）。
 */
public class SubAgentRegistry {

  /** 默认最大并发子 Agent 数量 */
  public static final int MAX_CONCURRENT = 5;

  private final ConcurrentHashMap<String, SubAgentRecord> records = new ConcurrentHashMap<>();

  /**
   * 注册一个新的子 Agent 记录。
   *
   * @param record 要注册的记录（ID 不能为 null）
   * @return 注册后的记录
   * @throws IllegalStateException 如果已达到最大并发限制
   * @throws NullPointerException 如果 record 为 null
   */
  public SubAgentRecord register(SubAgentRecord record) {
    if (activeCount() >= MAX_CONCURRENT) {
      throw new IllegalStateException(
          "Maximum concurrent sub-agents (" + MAX_CONCURRENT + ") reached");
    }
    records.put(record.id(), record);
    return record;
  }

  /**
   * 按 ID 查找子 Agent。
   *
   * @param id 子 Agent ID
   * @return 包含记录的 Optional，未找到时返回 empty
   */
  public Optional<SubAgentRecord> get(String id) {
    return Optional.ofNullable(records.get(id));
  }

  /**
   * 返回所有子 Agent 记录的不可变列表。
   *
   * @return 按创建时间顺序排列的记录列表
   */
  public List<SubAgentRecord> list() {
    List<SubAgentRecord> sorted = new ArrayList<>(records.values());
    sorted.sort((a, b) -> Long.compare(a.createdAt(), b.createdAt()));
    return Collections.unmodifiableList(sorted);
  }

  /**
   * 原子性地更新子 Agent 状态。
   *
   * @param id 子 Agent ID
   * @param newStatus 新状态
   * @return 如果找到并更新了记录则返回 true
   */
  public boolean updateStatus(String id, SubAgentStatus newStatus) {
    return records.computeIfPresent(id, (k, record) -> record.withStatus(newStatus)) != null;
  }

  /**
   * 设置子 Agent 的结果摘要（通常在 COMPLETED 转换时使用）。
   *
   * @param id 子 Agent ID
   * @param resultSummary 结果摘要
   * @return 如果找到并更新了记录则返回 true
   */
  public boolean updateResult(String id, String resultSummary) {
    return records.computeIfPresent(id, (k, record) -> record.withResult(resultSummary)) != null;
  }

  /**
   * 从注册表中移除子 Agent。
   *
   * @param id 子 Agent ID
   * @return 如果记录被移除则返回 true
   */
  public boolean remove(String id) {
    return records.remove(id) != null;
  }

  /**
   * 统计具有指定状态的子 Agent 数量。
   *
   * @param status 要统计的状态
   * @return 匹配的子 Agent 数量
   */
  public int countByStatus(SubAgentStatus status) {
    return (int) records.values().stream().filter(r -> r.status() == status).count();
  }

  /**
   * 返回当前活跃（RUNNING + CONNECTED）的子 Agent 数量。
   *
   * @return 活跃数量
   */
  public int activeCount() {
    return (int)
        records.values().stream()
            .filter(
                r -> r.status() == SubAgentStatus.RUNNING || r.status() == SubAgentStatus.CONNECTED)
            .count();
  }

  /**
   * 返回注册表中的子 Agent 总数（包括已完成/已取消的）。
   *
   * @return 总数
   */
  public int size() {
    return records.size();
  }

  /** 清空所有子 Agent 记录（重置操作）。 */
  public void clear() {
    records.clear();
  }
}
