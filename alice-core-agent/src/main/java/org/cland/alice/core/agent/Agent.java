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

  Agent(String agentId, AgentConfig config) {
    this.agentId =
        agentId != null ? agentId : java.util.UUID.randomUUID().toString().substring(0, 8);
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
