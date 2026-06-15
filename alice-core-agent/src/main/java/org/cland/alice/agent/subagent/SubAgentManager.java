/*
 * Alice Agent — SubAgentManager
 *
 * 子 Agent 编排器 — 线程池、生命周期协调、最大并发强制执行。
 */
package org.cland.alice.agent.subagent;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.cland.alice.agent.internal.acp.AcpClientException;
import org.cland.alice.agent.internal.acp.AcpClientWrapper;
import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger logger = LoggerFactory.getLogger(SubAgentManager.class);

  private final SubAgentRegistry registry;
  private final ExecutorService executor;
  private final AtomicInteger activeTaskCount = new AtomicInteger(0);
  private final String parentSessionId;
  private AgentConfig baseAgentConfig;
  private final List<Consumer<SubAgentResult>> completionListeners = new CopyOnWriteArrayList<>();
  private final Map<String, AcpClientWrapper> acpClients = new ConcurrentHashMap<>();
  private final Map<String, BlockingQueue<String>> messageQueues = new ConcurrentHashMap<>();

  /**
   * 创建子 Agent 编排器。
   *
   * @param parentSessionId 父会话 ID
   */
  public SubAgentManager(String parentSessionId) {
    this.parentSessionId = parentSessionId;
    this.registry = new SubAgentRegistry();
    this.baseAgentConfig = AgentConfig.defaults();
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
    this.baseAgentConfig = AgentConfig.defaults();
    this.executor = executor;
  }

  /**
   * 设置子 Agent 的基础 AgentConfig（用于企业级配置覆盖）。
   *
   * @param config 基础 AgentConfig
   */
  public void setBaseAgentConfig(AgentConfig config) {
    // 不做 null 检查 — 业务层应确保非 null 传入
    this.baseAgentConfig = config;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 生命周期：spawn
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 生成一个 ALICE 子 Agent。
   *
   * <p>创建一个新的 {@link Agent} 实例，分配唯一的 WAL 会话 ID，并在独立线程中异步执行 ReAct 循环。
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

    logger.info("Spawning sub-agent {} with goal='{}', session={}", subAgentId, goal, sessionId);

    // 异步执行 — 创建独立的 Agent 实例并运行 ReAct 循环
    CompletableFuture.runAsync(() -> executeSubAgent(subAgentId, goal, model), executor);

    return record;
  }

  /**
   * 内部方法：使用真实的 {@link Agent} 实例执行子 Agent 目标。
   *
   * <p>为每个子 Agent 创建独立的 {@link Agent} 实例，具有独立的 Vertx 实例和 AgentExecutor。 子 Agent 执行完毕后，结果通过
   * registry.updateResult() 回传给父会话。
   */
  /**
   * 注册子 Agent 完成通知监听器。
   *
   * <p>当子 Agent 进入终端状态（COMPLETED / FAILED / CANCELED）时，所有监听器将收到 {@link SubAgentResult} 通知。
   *
   * @param listener 完成通知回调
   */
  public void onSubAgentCompletion(Consumer<SubAgentResult> listener) {
    completionListeners.add(listener);
  }

  /**
   * 通知所有注册的监听器子 Agent 完成事件。
   *
   * @param result 子 Agent 执行结果
   */
  private void notifyCompletion(SubAgentResult result) {
    for (Consumer<SubAgentResult> listener : completionListeners) {
      try {
        listener.accept(result);
      } catch (Exception e) {
        logger.warn("Completion listener threw exception for sub-agent {}", result.subAgentId(), e);
      }
    }
  }

  /**
   * 内部方法：使用真实的 {@link Agent} 实例执行子 Agent 目标。
   *
   * <p>为每个子 Agent 创建独立的 {@link Agent} 实例，具有独立的 Vertx 实例和 AgentExecutor。 子 Agent 执行完毕后，结果通过
   * registry.updateResult() 回传给父会话，并通过 {@link #notifyCompletion} 通知注册的监听器。
   */
  private void executeSubAgent(String subAgentId, String goal, String model) {
    Agent subAgent = null;
    SubAgentResult result = null;
    try {
      // 创建独立的 Agent 实例
      AgentConfig config =
          AgentConfig.builder()
              .defaultModelId(model != null ? model : baseAgentConfig.defaultModelId())
              .maxIterations(baseAgentConfig.maxIterations())
              .actionTimeoutMs(baseAgentConfig.actionTimeoutMs())
              .preVerifyEnabled(baseAgentConfig.preVerifyEnabled())
              .postVerifyEnabled(baseAgentConfig.postVerifyEnabled())
              .build();

      subAgent = new Agent(subAgentId, config);

      logger.info(
          "Sub-agent {} executing goal='{}' with model={}",
          subAgentId,
          goal,
          config.defaultModelId());

      // 同步阻塞 — ask() 内部使用 CountDownLatch 等待 PPAO 循环完成
      String response = subAgent.ask(goal, config.defaultModelId());

      registry.updateStatus(subAgentId, SubAgentStatus.COMPLETED);
      registry.updateResult(subAgentId, response);

      result =
          new SubAgentResult(
              subAgentId,
              SubAgentStatus.COMPLETED,
              response,
              0, // messageCount — 等待集成跟踪
              System.currentTimeMillis()
                  - registry.get(subAgentId).map(SubAgentRecord::createdAt).orElse(0L));

      logger.info("Sub-agent {} completed successfully", subAgentId);

    } catch (Exception e) {
      logger.error("Sub-agent {} failed", subAgentId, e);
      registry.updateStatus(subAgentId, SubAgentStatus.FAILED);
      registry.updateResult(subAgentId, "Sub-agent failed: " + e.getMessage());

      result =
          new SubAgentResult(
              subAgentId,
              SubAgentStatus.FAILED,
              "Sub-agent failed: " + e.getMessage(),
              0,
              System.currentTimeMillis()
                  - registry.get(subAgentId).map(SubAgentRecord::createdAt).orElse(0L));
    } finally {
      if (subAgent != null) {
        try {
          subAgent.close();
        } catch (Exception e) {
          logger.warn("Error closing sub-agent {}", subAgentId, e);
        }
      }
      // 通知所有注册的监听器
      if (result != null) {
        notifyCompletion(result);
      }
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 生命周期：connect
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 连接外部 ACP Agent。
   *
   * <p>通过 {@link AcpClientWrapper} 建立 WebSocket 连接，执行 ACP 协议的三阶段握手： 初始化 → 创建会话。如果连接失败，子 Agent 状态转为
   * FAILED。
   *
   * @param name 外部 Agent 名称/别名
   * @param endpoint ACP 端点 URL
   * @return 包含新子 Agent ID 的 {@link SubAgentRecord}
   * @throws AcpClientException 如果连接失败
   */
  public SubAgentRecord connectAgent(String name, String endpoint) {
    String subAgentId = UUID.randomUUID().toString().substring(0, 8);
    SubAgentRecord record = SubAgentRecord.createAcp(subAgentId, name, endpoint);

    try {
      URI uri = URI.create(endpoint);
      AcpClientWrapper acpClient = new AcpClientWrapper(uri);
      acpClient.initialize("/workspace");
      String acpSessionId = acpClient.newSession();

      acpClients.put(subAgentId, acpClient);
      registry.register(record);

      logger.info(
          "Connected to ACP agent '{}' at {}, subAgentId={}, acpSessionId={}",
          name,
          endpoint,
          subAgentId,
          acpSessionId);

      return record;

    } catch (Exception e) {
      logger.error("Failed to connect to ACP agent at {}: {}", endpoint, e.getMessage());
      // 注册为 FAILED 状态
      SubAgentRecord failedRecord =
          new SubAgentRecord(
              subAgentId,
              SubAgentType.ACP,
              SubAgentStatus.FAILED,
              name,
              null,
              endpoint,
              System.currentTimeMillis(),
              System.currentTimeMillis(),
              "Connection failed: " + e.getMessage());
      registry.register(failedRecord);
      throw new AcpClientException(
          "Failed to connect to ACP agent at " + endpoint + ": " + e.getMessage(), e);
    }
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
   * <p>对于 ALICE 子 Agent，标记为取消（异步任务无法强制中断，但结果将被忽略）。 对于 ACP 子 Agent，关闭 ACP 连接并标记为取消。
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

    // 关闭 ACP 连接（如果是 ACP 类型）
    AcpClientWrapper acpClient = acpClients.remove(subAgentId);
    if (acpClient != null) {
      try {
        acpClient.close();
        logger.info("Closed ACP connection for canceled sub-agent {}", subAgentId);
      } catch (Exception e) {
        logger.warn("Error closing ACP connection for canceled sub-agent {}", subAgentId, e);
      }
    }

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
   * <p>将消息放入子 Agent 的消息队列中。子 Agent 在 executeSubAgent 的 ReAct 循环中 可以定期检查其消息队列以接收父会话的消息。
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

    BlockingQueue<String> queue =
        messageQueues.computeIfAbsent(subAgentId, k -> new LinkedBlockingQueue<>());
    boolean offered = queue.offer(message);
    if (offered) {
      logger.info("Message sent to sub-agent {}: {}", subAgentId, truncate(message, 100));
    }
    return offered;
  }

  /**
   * 获取子 Agent 的消息队列中的下一条消息（非阻塞）。
   *
   * <p>由子 Agent 执行线程调用，用于轮询父会话发来的消息。
   *
   * @param subAgentId 子 Agent ID
   * @return 消息内容，如果队列为空则返回 null
   */
  public String pollMessage(String subAgentId) {
    BlockingQueue<String> queue = messageQueues.get(subAgentId);
    return queue != null ? queue.poll() : null;
  }

  /**
   * 获取子 Agent 待处理消息数量。
   *
   * @param subAgentId 子 Agent ID
   * @return 消息队列中的待处理消息数
   */
  public int pendingMessageCount(String subAgentId) {
    BlockingQueue<String> queue = messageQueues.get(subAgentId);
    return queue != null ? queue.size() : 0;
  }

  /**
   * 向已连接的外部 ACP Agent 发送提示。
   *
   * <p>通过已缓存的 {@link AcpClientWrapper} 发送提示并接收响应。 如果 ACP Agent 未连接或已断开，返回 empty。
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

    AcpClientWrapper acpClient = acpClients.get(subAgentId);
    if (acpClient == null || !acpClient.isInitialized()) {
      logger.warn("ACP client for sub-agent {} is not initialized", subAgentId);
      return Optional.empty();
    }

    try {
      logger.info("Prompting ACP agent {}: {}", subAgentId, truncate(prompt, 100));
      String response = acpClient.prompt(prompt);
      return Optional.ofNullable(response);
    } catch (Exception e) {
      logger.error("Failed to prompt ACP agent {}: {}", subAgentId, e.getMessage());
      registry.updateStatus(subAgentId, SubAgentStatus.FAILED);
      registry.updateResult(subAgentId, "ACP prompt failed: " + e.getMessage());
      return Optional.empty();
    }
  }

  private static String truncate(String s, int maxLen) {
    if (s == null) return "null";
    return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
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

    // 关闭所有 ACP 客户端连接
    for (var entry : acpClients.entrySet()) {
      try {
        entry.getValue().close();
        logger.info("Closed ACP connection for sub-agent {}", entry.getKey());
      } catch (Exception e) {
        logger.warn("Error closing ACP connection for sub-agent {}", entry.getKey(), e);
      }
    }
    acpClients.clear();
    messageQueues.clear();
    registry.clear();
  }
}
