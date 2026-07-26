package org.cland.alice.core.agent;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.cland.alice.core.agent.executor.AgentExecutor;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.memory.AgentSession;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.agent.wal.RawMessage;
import org.cland.alice.core.agent.wal.SnowflakeIdGenerator;
import org.cland.alice.core.agent.wal.WalSession;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.core.planner.PlannerService;
import org.cland.alice.core.planner.strategy.FastPathStrategy;
import org.cland.alice.core.planner.strategy.SlowPathStrategy;
import org.cland.alice.core.planner.strategy.StrategySelector;
import org.cland.alice.core.planner.tree.ThinkingTree;
import org.cland.alice.env.adapter.EnvEvent;
import org.cland.alice.guardrail.Verificator;
import org.cland.alice.model.Call;
import org.cland.alice.model.CallStatus;
import org.cland.alice.model.ModelConfigLoader;
import org.cland.alice.model.ModelProvider;
import org.cland.alice.tool.gateway.ToolRegistry;
import org.cland.alice.tool.gateway.ToolRegistryHolder;
import org.cland.alice.tool.gateway.builtin.BuiltinTools;
import org.cland.alice.tool.gateway.engine.ToolDiscovery;
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
public class Agent implements AgentFacade {

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

  /** 最近一次 LLM 调用的推理/思考过程，供前端渲染 */
  private String lastReasoning;

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

  public Agent(String agentId, String sessionId, AgentConfig config) {
    this.agentId = agentId != null ? agentId : UUID.randomUUID().toString().substring(0, 8);
    this.sessionId = sessionId != null ? sessionId : SnowflakeIdGenerator.generateSessionId();
    this.config = config;
    this.vertx = Vertx.vertx();
    this.executor = new AgentExecutor(vertx, this);
  }

  public Agent(String agentId, AgentConfig config) {
    this(agentId, null, config);
  }

  // ========== 属性 ==========

  @Override
  public String agentId() {
    return agentId;
  }

  @Override
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

  /**
   * 注入静态规划（SOP）函数，重建 {@link PlannerService} 以支持 SOP 匹配。
   *
   * <p>如果当前已有 PlannerService，会复用其 StrategySelector 和现有的 SOP 函数。 该函数通常来自 {@code alice-memory-vault}
   * 的 {@code StaticPlanner::plan}。
   *
   * @param staticPlannerFn 接收上下文 Map 返回 Plan 的函数（null 表示无 SOP）
   */
  public Agent withStaticPlanner(
      java.util.function.Function<Map<String, Object>, Plan> staticPlannerFn) {
    if (this.plannerService != null) {
      this.plannerService =
          PlannerService.builder()
              .strategySelector(this.plannerService.strategySelector())
              .staticPlannerFn(staticPlannerFn)
              .build();
    }
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

  /** 注入 {@link WalSession}，启用 WAL 双轨制持久化与上下文压缩能力。 */
  public Agent withWal(WalSession wal) {
    this.executor.withWal(wal);
    return this;
  }

  // ========== 子模块 Getters（原 AgentCore getters） ==========

  @Override
  public PlannerService plannerService() {
    return plannerService;
  }

  public Verificator guardrail() {
    return guardrail;
  }

  @Override
  public ToolRegistry toolRegistry() {
    return toolRegistry;
  }

  @Override
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
              try {
                String result = ctx.containsKey("result") ? ctx.get("result").toString() : null;
                if (result == null) {
                  // 回退：直接调用 LLM
                  result = callLlmDirect(prompt, modelId);
                }
                resultRef.set(result);
                // 提取推理内容供前端渲染
                lastReasoning =
                    ctx.containsKey("__llm_reasoning") ? ctx.get("__llm_reasoning").toString() : "";
              } catch (Exception e) {
                errorRef.set(e);
              } finally {
                latch.countDown();
              }
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

  /**
   * 获取最近一次 LLM 调用的推理内容（reasoning_content），供 TUI 渲染。
   *
   * @return 推理文本，若无则返回空字符串
   */
  /**
   * 获取 Agent 执行器，用于注册事件监听等。
   *
   * @return AgentExecutor 实例
   */
  public AgentExecutor getExecutor() {
    return executor;
  }

  public String getLastReasoning() {
    return lastReasoning != null ? lastReasoning : "";
  }

  // ========== 异步 API ==========

  /**
   * 异步执行 PPAO 循环。
   *
   * @param prompt 用户输入
   * @return 异步结果（io.vertx.core.Future）
   */
  public Future<AgentContext> askAsync(String prompt) {
    AgentContext context = new AgentContext(this.sessionId, config.maxIterations());
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
  @Override
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
  @Override
  public boolean verifyPost(StepResult stepResult) {
    if (!config.postVerifyEnabled() || guardrail == null) {
      return true;
    }
    logger.debug("Post-verify result: {}", stepResult);
    return guardrail.audit(stepResult);
  }

  /** 判断 PPAO 循环是否需要终止。 */
  @Override
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
   * <p>完整流程：
   *
   * <ol>
   *   <li>从 WAL 获取该 session 所有消息（排除已存在的 compact + system）
   *   <li>组装为对话文本，调用 LLM 总结为一段紧凑摘要
   *   <li>以 role {@code compact} 写入 WAL
   *   <li>写 Checkpoint 标记旧消息可被 WalCompactor 清理
   * </ol>
   *
   * @return 压缩结果信息
   */
  public String compactContext() {
    logger.info("Compacting context for agent {}", agentId);

    WalSession wal = executor.wal();
    if (wal == null) {
      return "上下文压缩失败：WAL 未注入，无法获取历史消息";
    }

    // 1. 获取该 session 所有消息
    List<RawMessage> allMessages = wal.getAllMessages(sessionId);
    if (allMessages.isEmpty()) {
      return "没有历史消息需要压缩";
    }

    // 2. 选出可压缩的消息（排除 system + 已存在的 compact）
    List<RawMessage> compressible =
        allMessages.stream()
            .filter(m -> !"compact".equals(m.role()))
            .filter(m -> !"system".equals(m.role()))
            .collect(Collectors.toList());
    if (compressible.isEmpty()) {
      return "没有可压缩的消息（全部已为 compact 或 system）";
    }

    // 3. 组装 LLM 总结 prompt
    StringBuilder dialogBuilder = new StringBuilder();
    dialogBuilder.append("请将以下对话提炼为一段紧凑的中文摘要，保留关键事实、决策和工具调用结果。\n\n");
    for (RawMessage msg : compressible) {
      String roleLabel =
          switch (msg.role()) {
            case "user" -> "用户";
            case "assistant" -> "助手";
            case "tool" -> "工具结果";
            default -> msg.role();
          };
      dialogBuilder.append("【").append(roleLabel).append("】");
      if (msg.content() != null) {
        dialogBuilder.append(" ").append(msg.content());
      }
      if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
        String toolCallStr =
            msg.toolCalls().stream()
                .map(tc -> tc.function().name() + "(" + tc.function().arguments() + ")")
                .collect(Collectors.joining(", "));
        dialogBuilder.append(" [调用工具: ").append(toolCallStr).append("]");
      }
      dialogBuilder.append("\n");
    }

    // 4. 调用 LLM 总结
    String summaryContent;
    try {
      ModelProvider provider = ModelProvider.getInstance();
      Call result = provider.dispatch(config.defaultModelId(), dialogBuilder.toString());
      if (result.status() == CallStatus.FINISHED && result.result() != null) {
        summaryContent = result.result().content();
      } else {
        logger.warn("LLM compact summary failed: status={}", result.status());
        summaryContent = "[压缩摘要生成失败] " + compressible.size() + " 条历史消息";
      }
    } catch (Exception e) {
      logger.error("LLM compact summary error", e);
      summaryContent = "[压缩摘要生成异常] " + compressible.size() + " 条历史消息: " + e.getMessage();
    }

    // 5. 以 compact role 写入 WAL
    wal.compact(sessionId, summaryContent);

    // 6. 写时间戳到 longTermMemory
    if (memory != null) {
      memory.putLongTerm("__last_compact_ts_" + sessionId, Instant.now().toString());
    }

    logger.info(
        "Context compacted: {} messages → 1 compact summary (session={})",
        compressible.size(),
        sessionId);
    return "上下文压缩完成：将 " + compressible.size() + " 条历史消息提炼为 1 条摘要";
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

  /** 取消当前 PPAO 执行。可用于 TUI ESC / CLI Ctrl+C 中断。 */
  public void cancel() {
    executor.cancel();
    logger.info("[Agent] Execution cancelled");
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

  // ================================================================
  // 静态工厂方法 — 供 TUI/CLI 外观模块使用，避免外观直接依赖子模块
  // ================================================================

  /**
   * 初始化 ModelProvider：从 {@code ~/.alice/model.json} 加载配置，注册提供商和内置模型枚举。
   *
   * <p>此方法负责：
   *
   * <ul>
   *   <li>加载 {@code ~/.alice/model.json} 配置文件
   *   <li>将配置中的提供商注册到 {@link ModelProvider}
   *   <li>注册内置模型枚举
   *   <li>如果环境变量中存在 DEEPSEEK_API_KEY，注册 DeepSeek 供应商
   * </ul>
   *
   * @return 默认模型 ID（配置中指定），若无则返回 {@code null}
   */
  public static String initModelProvider() {
    ModelConfigLoader configLoader = new ModelConfigLoader();
    try {
      configLoader.load();
      configLoader.registerTo(ModelProvider.getInstance());
      logger.info("[Agent] Loaded {} model(s) from config", configLoader.getModelPool().size());
    } catch (Exception e) {
      logger.warn("[Agent] Failed to load model config, using defaults: {}", e.getMessage());
    }

    // 注册内置模型枚举
    ModelProvider.getInstance().registerBuiltinModels();

    // 确定默认模型
    String defaultModel = configLoader.getDefaultModel();
    if (defaultModel == null || defaultModel.isBlank()) {
      defaultModel = "gpt-4o-mini";
      logger.info(
          "[Agent] No default_model in ~/.alice/model.json, using built-in: {}", defaultModel);
    } else {
      logger.info("[Agent] Using default model from config: {}", defaultModel);
    }

    // 注册 DeepSeek 供应商（如果环境变量存在且尚未注册）
    String deepseekKey = System.getenv("DEEPSEEK_API_KEY");
    if (deepseekKey != null && !deepseekKey.isEmpty()) {
      if (ModelProvider.getInstance().getSupplier("deepseek-v4-flash") == null) {
        ModelProvider.getInstance()
            .registerSupplier(
                new org.cland.alice.model.supplier.OpenAiSupplier(
                    "deepseek", deepseekKey, "https://api.deepseek.com/v1/chat/completions"));
        logger.info("[Agent] Registered DeepSeek supplier via OpenAiSupplier (OpenAI-compatible)");
      }
    }

    return defaultModel;
  }

  /**
   * 创建完全初始化的 Agent 实例，自动装配所有子模块（PlannerService、ToolRegistry 等）。
   *
   * <p>外观模块可以直接使用此工厂方法创建 Agent，无需直接依赖 planner/tool-gateway/model 模块。
   *
   * @param config Agent 配置
   * @return 已装配所有子模块的 Agent 实例
   */
  public static Agent createDefault(AgentConfig config) {
    Agent agent = new Agent(config);

    // 1. 初始化工具注册中心并发现内置工具
    ToolRegistry toolRegistry = ToolRegistryHolder.INSTANCE.registry();
    try {
      int count = new ToolDiscovery(toolRegistry).scanAndRegister(List.of(new BuiltinTools()));
      logger.info("[Agent] Registered {} builtin tool(s)", count);
    } catch (Exception e) {
      logger.warn("[Agent] Failed to discover builtin tools", e);
    }
    agent.withToolRegistry(toolRegistry);

    // 2. 确定双路径模型：
    //    - 推理/慢路径 (System 2)：使用 config.defaultModelId()
    //    - 指令/快路径 (System 1)：从 model.json 的 planner.instruction_model_id 读取，
    //      未设置则回退到 defaultModelId()
    String reasoningModelId = config.defaultModelId();
    String instructionModelId = reasoningModelId;
    org.cland.alice.model.ModelConfigLoader.PlannerConfig plannerCfg = null;
    try {
      var configLoader = new org.cland.alice.model.ModelConfigLoader();
      configLoader.load();
      plannerCfg = configLoader.getPlannerConfig();
      if (plannerCfg != null) {
        if (plannerCfg.instructionModelId() != null) {
          instructionModelId = plannerCfg.instructionModelId();
        }
        if (plannerCfg.reasoningModelId() != null) {
          reasoningModelId = plannerCfg.reasoningModelId();
        }
      }
    } catch (Exception e) {
      logger.warn("[Agent] Failed to load model config: {}", e.getMessage());
    }

    // 3. 初始化 PlannerService（双路径规划引擎）
    var plannerSupplier =
        DefaultPlannerModelSupplier.builder()
            .provider(ModelProvider.getInstance())
            .instructionModelId(instructionModelId)
            .reasoningModelId(reasoningModelId)
            .plannerConfig(plannerCfg)
            .build();
    var fastPath = new FastPathStrategy(plannerSupplier);
    var thinkingTree = new ThinkingTree(Map.of());
    var slowPath =
        SlowPathStrategy.builder()
            .tree(thinkingTree)
            .modelSupplier(plannerSupplier)
            .mctsIterations(10)
            .build();
    var selector = StrategySelector.builder().fastPath(fastPath).slowPath(slowPath).build();
    // SOP 静态规划器（可选）可通过 withPlannerService() 注入。
    // 调用者（如 alice-bootstrap 或 facade 模块）若有 alice-memory-vault 访问权限，
    // 可创建 StaticPlanner + SopRegistry 并通过 PlannerService.builder().staticPlannerFn() 注入。
    var planner = PlannerService.builder().strategySelector(selector).build();
    agent.withPlannerService(planner);

    logger.info(
        "[Agent] Default Agent created: reasoningModel={}, instructionModel={}",
        config.defaultModelId(),
        instructionModelId);

    return agent;
  }
}
