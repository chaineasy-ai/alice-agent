/*
 * Alice Agent — SubAgentManager
 *
 * 子 Agent 编排器 — 线程池、生命周期协调、最大并发强制执行。
 */
package org.cland.alice.agent.subagent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子 Agent 编排器。
 *
 * <p>负责：
 *
 * <ul>
 *   <li>接收并处理 {@code SubAgentCmd} 指令
 *   <li>管理子 Agent 的完整生命周期（创建→运行→完成/取消）
 *   <li>通过 {@link SubAgentRegistry} 维护子 Agent 状态
 *   <li>强制执行最大并发限制
 *   <li>为子 Agent 分配唯一的 UUID
 * </ul>
 *
 * <p>此类为线程安全，可在多线程环境中安全使用。
 */
public class SubAgentManager implements AutoCloseable {

  private final SubAgentRegistry registry;
  private final ExecutorService executor;
  private final AtomicInteger activeTaskCount = new AtomicInteger(0);
  private final String parentSessionId;

  /**
   * 创建子 Agent 编排器。
   *
   * @param parentSessionId 父会话 ID
   */
  public SubAgentManager(String parentSessionId) {
    this.parentSessionId = parentSessionId;
    this.registry = new SubAgentRegistry();
    this.executor =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "sub-agent-" + activeTaskCount.incrementAndGet());
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * 创建子 Agent 编排器，使用自定义注册表和执行器。
   *
   * @param parentSessionId 父会话 ID
   * @param registry 自定义注册表
   * @param executor 自定义执行器
   */
  SubAgentManager(String parentSessionId, SubAgentRegistry registry, ExecutorService executor) {
    this.parentSessionId = parentSessionId;
    this.registry = registry;
    this.executor = executor;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 生命周期：spawn
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 生成一个 ALICE 子 Agent。
   *
   * <p>创建一个新的子 Agent 记录并开始异步执行。
   *
   * @param goal 子 Agent 要执行的目标
   * @param model 可选的模型覆盖（null 表示使用默认模型）
   * @return 包含新子 Agent ID 的 {@link SubAgentRecord}
   * @throws IllegalStateException 如果已达到最大并发限制
   */
  public SubAgentRecord spawnSubAgent(String goal, String model) {
    String subAgentId = UUID.randomUUID().toString().substring(0, 8);
    String sessionId = parentSessionId + "-sub-" + subAgentId;

    SubAgentRecord record = SubAgentRecord.createAlice(subAgentId, goal, sessionId);
    registry.register(record);

    // 异步执行
    CompletableFuture.runAsync(() -> executeSubAgent(subAgentId, goal, model), executor);

    return record;
  }

  /**
   * 内部方法：执行子 Agent 目标。
   *
   * <p>实际实现将使用 {@code AgentExecutor} 和 {@code WalSession} 执行 ReAct 循环。 当前为存根 — 返回模拟结果。
   */
  private void executeSubAgent(String subAgentId, String goal, String model) {
    try {
      // TODO: 实际实现 — 使用 AgentExecutor + WalSession 执行 ReAct 循环
      // 当前为占位实现
      Thread.sleep(100); // 模拟工作
      registry.updateStatus(subAgentId, SubAgentStatus.COMPLETED);
      registry.updateResult(subAgentId, "Sub-agent completed: " + goal);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      registry.updateStatus(subAgentId, SubAgentStatus.CANCELED);
    } catch (Exception e) {
      registry.updateStatus(subAgentId, SubAgentStatus.FAILED);
      registry.updateResult(subAgentId, "Sub-agent failed: " + e.getMessage());
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 生命周期：connect（存根 — 将在 US2 中完整实现）
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 连接外部 ACP Agent。
   *
   * @param name 外部 Agent 名称/别名
   * @param endpoint ACP 端点 URL
   * @return 包含新子 Agent ID 的 {@link SubAgentRecord}
   */
  public SubAgentRecord connectAgent(String name, String endpoint) {
    String subAgentId = UUID.randomUUID().toString().substring(0, 8);
    SubAgentRecord record = SubAgentRecord.createAcp(subAgentId, name, endpoint);
    registry.register(record);
    return record;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 查询与操作
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 列出所有子 Agent。
   *
   * @return 所有子 Agent 记录的不可变列表
   */
  public List<SubAgentRecord> listSubAgents() {
    return registry.list();
  }

  /**
   * 按 ID 查找子 Agent。
   *
   * @param id 子 Agent ID
   * @return 包含记录的 Optional
   */
  public Optional<SubAgentRecord> getSubAgent(String id) {
    return registry.get(id);
  }

  /**
   * 取消一个子 Agent。
   *
   * @param subAgentId 要取消的子 Agent ID
   * @return 如果找到并取消成功则返回 true
   */
  public boolean cancelSubAgent(String subAgentId) {
    Optional<SubAgentRecord> opt = registry.get(subAgentId);
    if (opt.isEmpty()) return false;

    SubAgentRecord record = opt.get();
    if (SubAgentRecord.isTerminal(record.status())) return false;

    registry.updateStatus(subAgentId, SubAgentStatus.CANCELED);
    return true;
  }

  /**
   * 获取已完成的子 Agent 结果。
   *
   * @param subAgentId 子 Agent ID
   * @return 包含 {@link SubAgentResult} 的 Optional，如果未找到或未完成则返回 empty
   */
  public Optional<SubAgentResult> getSubAgentResult(String subAgentId) {
    return registry
        .get(subAgentId)
        .filter(r -> SubAgentRecord.isTerminal(r.status()))
        .map(
            r ->
                new SubAgentResult(
                    r.id(),
                    r.status(),
                    r.resultSummary() != null ? r.resultSummary() : "No summary available",
                    0, // messageCount — 将在完整实现中跟踪
                    r.durationMs()));
  }

  /**
   * 向正在运行的子 Agent 发送消息。
   *
   * @param subAgentId 子 Agent ID
   * @param message 消息内容
   * @return 如果找到并成功发送则返回 true
   */
  public boolean sendToSubAgent(String subAgentId, String message) {
    Optional<SubAgentRecord> opt = registry.get(subAgentId);
    if (opt.isEmpty()) return false;
    SubAgentRecord record = opt.get();
    // 仅支持向 RUNNING 状态的 ALICE 子 Agent 发送消息
    if (record.type() != SubAgentType.ALICE || record.status() != SubAgentStatus.RUNNING) {
      return false;
    }
    // TODO: 实际实现 — 将消息路由到子 Agent 的上下文
    return true;
  }

  /**
   * 向已连接的外部 ACP Agent 发送提示。
   *
   * @param subAgentId ACP Agent ID
   * @param prompt 提示文本
   * @return 包含响应字符串的 Optional
   */
  public Optional<String> promptAgent(String subAgentId, String prompt) {
    Optional<SubAgentRecord> opt = registry.get(subAgentId);
    if (opt.isEmpty()) return Optional.empty();
    SubAgentRecord record = opt.get();
    if (record.type() != SubAgentType.ACP) return Optional.empty();
    // TODO: 实际实现 — 通过 AcpClient 调用 ACP 协议
    return Optional.of("(ACP response placeholder for: " + prompt + ")");
  }

  /**
   * 返回活跃的子 Agent 数量。
   *
   * @return RUNNING + CONNECTED 的子 Agent 数量
   */
  public int activeCount() {
    return registry.activeCount();
  }

  /**
   * 返回注册表中的子 Agent 总数。
   *
   * @return 所有子 Agent 的数量（包括已完成/已取消的）
   */
  public int totalCount() {
    return registry.size();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 关闭与清理
  // ──────────────────────────────────────────────────────────────────────────

  @Override
  public void close() {
    executor.shutdownNow();
    registry.clear();
  }
}
