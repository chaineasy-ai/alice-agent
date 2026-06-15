package org.cland.alice.core.agent;

import io.vertx.core.Vertx;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.cland.alice.core.agent.executor.AgentExecutor;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.planner.PlannerService;
import org.cland.alice.env.adapter.EnvEvent;
import org.cland.alice.guardrail.Verificator;
import org.cland.alice.memory.agent.AgentSession;
import org.cland.alice.model.Call;
import org.cland.alice.model.ModelProvider;
import org.cland.alice.tool.gateway.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 核心类，代表一个 AI Agent 实例。
 *
 * <p>基于 PPAO (Perceive-Plan-Act-Observe-Verify) 核心循环。 通过 ModelProvider 与底层模型交互，通过 AgentExecutor
 * 驱动响应式执行循环。持有所有子模块（规划器、安全校验、工具注册中心、记忆、环境适配器）的引用。
 *
 * <p>生命周期状态机：
 *
 * <pre>
 *   START -> PERCEIVING -> PLANNING -> VERIFYING_PRE -> ACTING (Micro-ReAct)
 *       -> OBSERVING -> VERIFYING_POST -> REFLECTING -> (loop|FINISH)
 * </pre>
 *
 * <p>使用示例：
 *
 * <pre>
 *   Agent agent = new Agent();
 *   String result = agent.ask("What is the capital of France?");
 *   System.out.println(result); // "Paris"
 * </pre>
 */
public class Agent {

  private static final Logger logger = LoggerFactory.getLogger(Agent.class);

  private final String agentId;
  private final String sessionId;
  private final AgentConfig config;
  private final Vertx vertx;
  private final AgentExecutor executor;

  // ========== 子模块引用（原 AgentCore 字段） ==========

  private PlannerService plannerService;
  private Verificator guardrail;
  private ToolRegistry toolRegistry;
  private AgentSession memory;
  private EnvEvent envAdapter;

  // ========== 构造 ==========

  public Agent() {
    this(null, AgentConfig.defaults());
  }

  public Agent(String agentId) {
    this(agentId, AgentConfig.defaults());
  }

  public Agent(AgentConfig config) {
    this(null, config);
  }

  public Agent(String agentId, AgentConfig config) {
    this.agentId =
        agentId != null ? agentId : java.util.UUID.randomUUID().toString().substring(0, 8);
    this.sessionId = java.util.UUID.randomUUID().toString().substring(0, 8);
    this.config = config;
    this.vertx = Vertx.vertx();
    this.executor = new AgentExecutor(vertx, this);
  }

  // ========== 属性 ==========

  public String agentId() {
    return agentId;
  }

  public AgentConfig config() {
    return config;
  }

  public Vertx vertx() {
    return vertx;
  }

  // ========== 依赖注入（原 AgentCore 的 with* 方法） ==========

  /** 注入 {@link PlannerService} — 规划器引擎。 */
  public Agent withPlannerService(PlannerService plannerService) {
    this.plannerService = plannerService;
    return this;
  }

  public Agent withGuardrail(Verificator guardrail) {
    this.guardrail = guardrail;
    return this;
  }

  public Agent withToolRegistry(ToolRegistry toolRegistry) {
    this.toolRegistry = toolRegistry;
    return this;
  }

  public Agent withMemory(AgentSession memory) {
    this.memory = memory;
    return this;
  }

  public Agent withEnvAdapter(EnvEvent envAdapter) {
    this.envAdapter = envAdapter;
    return this;
  }

  // ========== 子模块 Getters（原 AgentCore getters） ==========

  public PlannerService plannerService() {
    return plannerService;
  }

  public Verificator guardrail() {
    return guardrail;
  }

  public ToolRegistry toolRegistry() {
    return toolRegistry;
  }

  public AgentSession memory() {
    return memory;
  }

  public EnvEvent envAdapter() {
    return envAdapter;
  }

  // ========== 同步 API ==========

  /** 使用默认上下文运行 Agent（同步阻塞）。 */
  public void run() {
    run(new AgentContext());
  }

  /**
   * 使用指定上下文运行 Agent（同步阻塞）。
   *
   * <p>内部执行 PPAO 循环，等待结果返回。
   */
  public void run(AgentContext context) {
    String prompt = context.containsKey("prompt") ? context.get("prompt").toString() : "Hello!";

    String modelId =
        context.containsKey("model") ? context.get("model").toString() : config.defaultModelId();

    logger.info("Agent {} running with model {}", agentId, modelId);

    // 同步执行 PPAO 循环
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<AgentContext> resultRef = new AtomicReference<>();

    executor
        .execute(prompt, context)
        .onSuccess(
            ctx -> {
              resultRef.set(ctx);
              latch.countDown();
            })
        .onFailure(
            err -> {
              logger.error("Agent {} PPAO loop failed", agentId, err);
              context.put("error", err.getMessage());
              context.put("status", "FATAL_ERROR");
              latch.countDown();
            });

    try {
      latch.await(config.actionTimeoutMs() * 2, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      context.put("error", "Interrupted: " + e.getMessage());
    }

    AgentContext resultCtx = resultRef.get();
    if (resultCtx != null) {
      context.putAll(resultCtx.asMap());
    }

    logger.info("Agent {} completed");
  }

  /**
   * 向 Agent 发送 prompt，返回响应内容（同步阻塞）。
   *
   * <p>对应设计文档中 Agent 的 ask() 方法。
   *
   * @param prompt 用户输入的提示词
   * @return 模型返回的响应文本
   */
  public String ask(String prompt) {
    return ask(prompt, config.defaultModelId());
  }

  /**
   * 向 Agent 发送 prompt，指定模型（同步阻塞）。
   *
   * <p>使用 PPAO 循环执行，如果循环产生 Finish 结果则返回 answer， 否则回退到直接调用 ModelProvider。
   *
   * @param prompt 用户输入的提示词
   * @param modelId 目标模型 ID
   * @return 模型返回的响应文本
   */
  public String ask(String prompt, String modelId) {
    logger.info("Agent {} ask model={}", agentId, modelId);

    AgentContext context = new AgentContext(config.maxIterations());
    context.put("prompt", prompt);
    context.put("model", modelId);

    // 同步执行 PPAO 循环
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> resultRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    executor
        .execute(prompt, context)
        .onSuccess(
            ctx -> {
              String result = ctx.containsKey("result") ? ctx.get("result").toString() : null;
              if (result == null) {
                // 回退：直接调用 LLM
                result = callLlmDirect(prompt, modelId);
              }
              resultRef.set(result);
              latch.countDown();
            })
        .onFailure(
            err -> {
              errorRef.set(err);
              latch.countDown();
            });

    try {
      latch.await(config.actionTimeoutMs() * 2, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Agent ask interrupted", e);
    }

    if (errorRef.get() != null) {
      throw new RuntimeException("Agent ask failed", errorRef.get());
    }

    String result = resultRef.get();
    if (result == null) {
      throw new RuntimeException("Agent ask returned null result");
    }

    logger.info("Agent {} response length={}", agentId, result.length());
    return result;
  }

  // ========== 异步 API ==========

  /**
   * 异步执行 PPAO 循环。
   *
   * @param prompt 用户输入
   * @return 异步结果（io.vertx.core.Future）
   */
  public io.vertx.core.Future<AgentContext> askAsync(String prompt) {
    AgentContext context = new AgentContext(config.maxIterations());
    context.put("prompt", prompt);
    return executor.execute(prompt, context);
  }

  // ========== 验证钩子（原 AgentCore 方法，供 AgentExecutor 调用） ==========

  /**
   * Pre-Verify: 在执行 Action 前拦截检查安全性和策略合规性。
   *
   * @param action 待验证的 Action
   * @return true 表示通过，false 表示被拦截
   */
  public boolean verifyPre(Action action) {
    if (!config.preVerifyEnabled() || guardrail == null) {
      return true;
    }
    logger.debug("Pre-verify action: {}", action);
    return guardrail.intercept(
        Map.of(
            "type", action.type().name(),
            "target", action.target() != null ? action.target() : "",
            "actionId", action.actionId()));
  }

  /**
   * Post-Verify: 执行完成后审计观测结果。
   *
   * @param stepResult 当前步骤的结果
   * @return true 表示通过，false 表示需要 Revision
   */
  public boolean verifyPost(StepResult stepResult) {
    if (!config.postVerifyEnabled() || guardrail == null) {
      return true;
    }
    logger.debug("Post-verify result: {}", stepResult);
    return guardrail.audit(stepResult);
  }

  /** 判断 PPAO 循环是否需要终止。 */
  public boolean shouldFinish(AgentContext context, StepResult result) {
    if (result instanceof StepResult.Finish) {
      return true;
    }
    if (result instanceof StepResult.Failure) {
      return true;
    }
    if (context.currentPhase() == AgentContext.Phase.FINISH) {
      return true;
    }
    if (context.isMaxIterationsReached()) {
      logger.warn("Agent {} reached max iterations ({})", agentId, config.maxIterations());
      return true;
    }
    return false;
  }

  // ========== 上下文管理与查询接口 ==========

  /**
   * 获取当前全量 Context 状态（Token 占用、消息滑动窗口、变量快照）。
   *
   * <p>支持 {@code /context} 命令查询。返回的字符串以 Markdown 格式呈现。
   *
   * @return 格式化后的上下文状态信息
   */
  public String getActiveContext() {
    AgentContext ctx = new AgentContext();
    StringBuilder sb = new StringBuilder();
    sb.append("── 上下文状态 ──\n");
    sb.append("| 属性 | 值 |\n");
    sb.append("|---|---|\n");
    sb.append("| 会话 ID | ").append(ctx.sessionId()).append(" |\n");
    sb.append("| Agent ID | ").append(agentId).append(" |\n");
    sb.append("| 默认模型 | ").append(config.defaultModelId()).append(" |\n");
    sb.append("| 最大迭代 | ").append(config.maxIterations()).append(" |\n");

    if (memory != null) {
      try {
        String shortTerm = memory.getShortTerm(ctx.sessionId());
        int msgCount = shortTerm.isEmpty() ? 0 : shortTerm.split("\n").length;
        sb.append("| 消息条数 | ").append(msgCount).append(" |\n");
        sb.append("| Token 占用 | N/A (Token 计数器待集成) |\n");
      } catch (Exception e) {
        sb.append("| Memory 状态 | 异常: ").append(e.getMessage()).append(" |\n");
      }
    } else {
      sb.append("| Token 占用 | N/A (Memory 未注入) |\n");
      sb.append("| 消息滑动窗口 | N/A |\n");
    }

    sb.append("| 变量快照 | ")
        .append(ctx.asMap().isEmpty() ? "空" : ctx.asMap().keySet())
        .append(" |\n");
    return sb.toString();
  }

  /**
   * 清空短期记忆（保留 System Prompt / Rules），重置 Token 计数器。
   *
   * <p>支持 {@code /clear} 命令执行。
   */
  public void clearMemory() {
    logger.info("Clearing memory for agent {}", agentId);
    if (memory != null) {
      memory.clearSession(sessionId);
    }
  }

  /**
   * 将历史对话写入 WAL（如果启用了 WAL），提炼历史为 Summary 事实快照，释放 Context Window。
   *
   * <p>支持 {@code /compact} 命令执行。
   *
   * @return 压缩结果信息
   */
  public String compactContext() {
    logger.info("Compacting context for agent {}", agentId);
    // 如果有 WAL，先写入（持久化短期记忆到长期记忆作为 checkpoint 的替代）
    if (memory != null) {
      memory.putLongTerm("__last_compact_ts_" + sessionId, java.time.Instant.now().toString());
    }
    // TODO: 触发 LLM 总结（需等待 Memory 模块提供总结接口）
    return "上下文压缩完成（释放 Token: N/A，待 Memory 模块提供总结接口）";
  }

  /**
   * 动态切换 LLM 引擎。
   *
   * <p>支持 {@code /model} 命令执行。切换后同步刷新 Verification 模块的审计敏感度。
   *
   * @param modelId 目标模型标识
   */
  public void switchModel(String modelId) {
    logger.info(
        "Switching model from {} to {} for agent {}", config.defaultModelId(), modelId, agentId);
    // 更新配置中的默认模型
    // TODO: 当 AgentConfig 支持动态修改时，更新 defaultModelId
  }

  /**
   * 注入人类反馈到 Context。
   *
   * <p>支持 {@code /feedback} 命令执行。
   *
   * @param feedback 用户的反馈内容
   */
  public void injectFeedback(String feedback) {
    logger.info("Injecting feedback for agent {}: {}", agentId, feedback);
    // 将反馈注入到 Agent 上下文
    AgentContext ctx = new AgentContext();
    ctx.put("lastFeedback", feedback);
    // TODO: 解除 Agent 的 HITL 挂起状态（待 AgentExecutor 暴露 HumanInTheLoop 接口）
    if (executor != null) {
      executor.resumeWithFeedback(feedback);
    }
  }

  /**
   * 获取最后一条反馈（如果有）。
   *
   * @return 反馈内容，或 {@code null}
   */
  public String feedback() {
    // TODO: 从 AgentContext 或 Memory 中提取最后一条反馈
    return null;
  }

  /** 关闭 Agent 释放资源。 */
  public void close() {
    vertx.close();
  }

  // ========== 辅助 ==========

  /** 直接调用 LLM（回退逻辑） */
  private String callLlmDirect(String prompt, String modelId) {
    logger.debug("Falling back to direct LLM call: model={}", modelId);
    ModelProvider provider = ModelProvider.getInstance();
    Call result = provider.dispatch(modelId, prompt);

    if (result.result() == null) {
      throw new RuntimeException("Agent call failed: " + result.status());
    }

    logger.info(
        "Agent {} direct LLM response status={}, tokens={}",
        agentId,
        result.status(),
        result.metrics().tokenUsage() != null
            ? result.metrics().tokenUsage().totalTokens()
            : "N/A");

    return result.result().content();
  }
}
