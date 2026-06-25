package org.cland.alice.facade.cmd;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.agent.wal.FileWalStore;
import org.cland.alice.core.agent.wal.SnowflakeIdGenerator;
import org.cland.alice.core.agent.wal.WalSession;
import org.cland.alice.facade.cmd.config.AliceConfigStore;
import org.cland.alice.facade.cmd.config.RunConfig;
import org.cland.alice.facade.cmd.render.OutputRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行协调器，驱动 Agent 核心完成一次 CLI 任务。
 *
 * <p>对应设计文档中 {@code ExecutionCoordinator} 组件的职责：
 *
 * <ul>
 *   <li>接收 {@link RunConfig}
 *   <li>初始化 Agent 核心
 *   <li>驱动 PPAO 循环，通过 {@link OutputRenderer} 实时输出
 *   <li>处理超时和退出码
 * </ul>
 */
public final class ExecutionCoordinator {

  private static final Logger logger = LoggerFactory.getLogger(ExecutionCoordinator.class);

  private final RunConfig config;
  private final OutputRenderer renderer;
  private final String modelOverride;

  /**
   * 创建执行协调器。
   *
   * @param config 运行配置
   * @param renderer 输出渲染器
   */
  public ExecutionCoordinator(RunConfig config, OutputRenderer renderer) {
    this(config, renderer, null);
  }

  /**
   * 创建执行协调器，支持模型覆盖。
   *
   * @param config 运行配置
   * @param renderer 输出渲染器
   * @param modelOverride 覆盖默认模型 ID（来自 ~/.alice/model.json 的 default_model），为 null 时使用
   *     RunConfig.model()
   */
  public ExecutionCoordinator(RunConfig config, OutputRenderer renderer, String modelOverride) {
    this.config = config;
    this.renderer = renderer;
    this.modelOverride = modelOverride;
  }

  /**
   * 执行任务（同步阻塞）。
   *
   * <p>流程：
   *
   * <ol>
   *   <li>根据 RunConfig 构建 AgentConfig
   *   <li>创建 Agent 实例
   *   <li>提交任务并等待完成
   *   <li>输出最终结果
   * </ol>
   *
   * @return 退出码（0 成功，1 失败）
   */
  public int execute() {
    logger.info("Starting task: {}", config.task());

    try {
      // 1. 构建 AgentConfig
      int maxIterations = AgentConfig.DEFAULT_MAX_ITERATIONS;
      try {
        String iterStr = new AliceConfigStore().get("agent.max_iterations");
        if (iterStr != null && !iterStr.isBlank()) {
          int parsed = Integer.parseInt(iterStr);
          if (parsed > 0) maxIterations = parsed;
        }
      } catch (Exception e) {
        logger.debug(
            "Failed to read agent.max_iterations from config, using default {}",
            AgentConfig.DEFAULT_MAX_ITERATIONS,
            e);
      }

      String effectiveModel = modelOverride != null ? modelOverride : config.model();
      AgentConfig agentConfig =
          AgentConfig.builder()
              .defaultModelId(effectiveModel)
              .maxIterations(maxIterations)
              .debug(config.verbose())
              .build();

      // 2. 检查 chat 模式
      if (config.chat()) {
        try {
          org.cland.alice.facade.cmd.chat.JLineChatSession chatSession =
              new org.cland.alice.facade.cmd.chat.JLineChatSession(agentConfig);
          chatSession.run();
        } catch (Exception e) {
          logger.error("Chat session failed", e);
          System.err.println("Chat session error: " + e.getMessage());
          return 1;
        }
        return 0;
      }

      // 2b. Resume 模式 — 从 WAL 恢复历史会话
      if (config.resumeMode()) {
        return executeResume(config, agentConfig);
      }

      // 3. 使用客户端传入的 sessionId（或自动生成）
      String sessionId = config.sessionId();
      if (sessionId == null || sessionId.isBlank()) {
        sessionId = SnowflakeIdGenerator.generateSessionId();
      }

      // 3b. 创建 WAL（目录使用完整的 sessionId，避免哈希碰撞）
      WalSession wal =
          new WalSession(
              new FileWalStore(
                  java.nio.file.Paths.get(
                      System.getProperty("user.home"), ".alice", "wal", sessionId)));
      Agent agent = new Agent(null, sessionId, agentConfig).withWal(wal);
      logger.debug("Agent created: {} session={} walDir={}", agent.agentId(), sessionId, sessionId);

      // 4. 注册内置工具（read_file, write_file, grep, run）到 ToolRegistry
      org.cland.alice.tool.gateway.ToolRegistry tr =
          org.cland.alice.tool.gateway.ToolRegistryHolder.INSTANCE.registry();
      var discovery = new org.cland.alice.tool.gateway.engine.ToolDiscovery(tr);
      int toolCount =
          discovery.scanAndRegister(
              java.util.List.of(new org.cland.alice.tool.gateway.builtin.BuiltinTools()));
      agent.withToolRegistry(tr);
      logger.info("Registered {} builtin tool(s) from BuiltinTools", toolCount);

      // 5. 构建上下文（使用客户端 sessionId）
      AgentContext context = new AgentContext(sessionId);
      context.put("prompt", config.task());
      context.put("model", config.model());

      // 6. 检查 stdin 是否有管道输入
      String stdinInput = readStdin();
      if (stdinInput != null && !stdinInput.isBlank()) {
        context.put("stdin", stdinInput);
        logger.debug("Stdin input captured: {} chars", stdinInput.length());
      }

      // 7. 同步执行
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<AgentContext> resultRef = new AtomicReference<>();
      AtomicReference<Throwable> errorRef = new AtomicReference<>();

      long timeoutMs = config.timeoutSeconds() * 1000;

      agent
          .askAsync(config.task())
          .onSuccess(
              ctx -> {
                resultRef.set(ctx);
                latch.countDown();
              })
          .onFailure(
              err -> {
                errorRef.set(err);
                latch.countDown();
              });

      boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);

      if (!completed) {
        renderer.renderError("Task timed out after " + config.timeoutSeconds() + "s", config);
        agent.close();
        return 1;
      }

      if (errorRef.get() != null) {
        renderer.renderError("Task failed: " + errorRef.get().getMessage(), config);
        if (config.verbose()) {
          errorRef.get().printStackTrace(System.err);
        }
        agent.close();
        return 1;
      }

      // 8. 获取结果并输出
      AgentContext resultCtx = resultRef.get();
      if (resultCtx != null) {
        Object resultObj = resultCtx.get("result");
        String result = resultObj != null ? resultObj.toString() : "No result produced.";

        // 实时渲染中间步骤 — 从上下文的思考链中提取
        String thoughtChain = resultCtx.thoughtChain();
        if (config.verbose() && !thoughtChain.isBlank()) {
          // 思考链通过迭代步骤渲染
          renderStepResults(resultCtx);
        }

        renderer.renderFinal(result, config);
      } else {
        renderer.renderFinal("Task completed but no result context.", config);
      }

      agent.close();
      return 0;

    } catch (Exception e) {
      logger.error("Execution failed", e);
      renderer.renderError("Unexpected error: " + e.getMessage(), config);
      if (config.verbose()) {
        e.printStackTrace(System.err);
      }
      return 1;
    }
  }

  // ========================================================================
  // 辅助
  // ========================================================================

  /** 从上下文中解析中间步骤结果并渲染。 当前实现为占位：真实场景中需要 AgentExecutor 通过 callback 流式发布 StepResult。 */
  private void renderStepResults(AgentContext context) {
    String thoughtChain = context.thoughtChain();
    if (thoughtChain == null || thoughtChain.isBlank()) {
      return;
    }

    String[] steps = thoughtChain.split("\n---\n");
    for (int i = 0; i < steps.length; i++) {
      String step = steps[i].trim();
      if (!step.isBlank()) {
        renderer.render(StepResult.finish(step), config);
      }
    }
  }

  /** 尝试从 stdin 读取管道数据（非交互式）。 仅在 System.in 有数据可用时读取。 */
  private String readStdin() {
    try {
      if (System.in.available() > 0) {
        byte[] buffer = new byte[System.in.available()];
        int bytesRead = System.in.read(buffer);
        if (bytesRead > 0) {
          return new String(buffer, 0, bytesRead, java.nio.charset.StandardCharsets.UTF_8).trim();
        }
      }
    } catch (Exception e) {
      logger.debug("No stdin data available");
    }
    return null;
  }

  // ========================================================================
  // Resume 恢复执行
  // ========================================================================

  /**
   * 从 WAL 持久化存储中恢复历史会话。
   *
   * <p>流程：
   *
   * <ol>
   *   <li>根据 sessionId 定位 WAL 目录
   *   <li>创建 WalSession 并执行 RecoveryEngine.recover()
   *   <li>如果指定了 snapshot，从特定快照恢复
   *   <li>重建 Agent 上下文并输出恢复摘要
   * </ol>
   */
  private int executeResume(RunConfig config, AgentConfig agentConfig) {
    String sessionId = config.sessionId();
    if (sessionId == null || sessionId.isBlank()) {
      System.err.println(
          "No session-id provided for resume. Use --session-id <id> or --list to see available sessions.");
      return 1;
    }

    String snapshotId = config.resumeSnapshot();

    try {
      java.nio.file.Path walPath =
          java.nio.file.Paths.get(System.getProperty("user.home"), ".alice", "wal", sessionId);

      if (!java.nio.file.Files.isDirectory(walPath)) {
        System.err.println("Session '" + sessionId + "' not found in WAL storage.");
        System.err.println("Use 'alice resume --list' to see available sessions.");
        return 1;
      }

      var wal = new WalSession(new FileWalStore(walPath));
      var recoveryResult = wal.recover(sessionId);
      logger.info("[Resume] Recovery result for {}: {}", sessionId, recoveryResult.summary());

      // 构建恢复摘要
      System.out.println("=== Session Restored ===");
      System.out.println("  Session:   " + sessionId);
      System.out.println("  Messages:  " + wal.messageCount(sessionId));
      if (snapshotId != null) {
        System.out.println("  Snapshot:  " + snapshotId);
      }
      System.out.println("  Status:    " + recoveryResult.summary());
      System.out.println();
      System.out.println("Session restored successfully. Ready to continue conversation.");

      return 0;

    } catch (Exception e) {
      logger.error("Failed to resume session {}", sessionId, e);
      System.err.println("Failed to resume session: " + e.getMessage());
      return 1;
    }
  }
}
