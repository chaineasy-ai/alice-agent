package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.guardrail.GuardrailToolProxy;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.Observation;
import org.cland.alice.core.agent.prompt.PromptManager;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.agent.wal.Checkpoint;
import org.cland.alice.core.agent.wal.SnowflakeIdGenerator;
import org.cland.alice.core.agent.wal.WalSession;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.model.Call;
import org.cland.alice.model.ModelProvider;
import org.cland.alice.tool.gateway.engine.ExecutionEngine;
import org.cland.alice.tool.gateway.engine.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent PPAO 循环的响应式执行器。
 *
 * <p>基于 Vert.x 的 {@link Future} 实现异步 PPAO 闭环。 根据设计文档，PPAO 循环分为两层：
 *
 * <p><b>Macro Loop (战略层 — PPAO)：</b>
 *
 * <pre>
 *   Perceive → Plan → Verify(Pre) → [Act: Micro-ReAct] → Verify(Post) → Reflect → (loop|FINISH)
 * </pre>
 *
 * <p><b>Micro Loop (战术层 — ReAct，在 Act 阶段内部)：</b>
 *
 * <pre>
 *   Reason → Dispatch → Observe → (loop until sub-goal done or circuit break)
 * </pre>
 *
 * <p>对应设计文档中的 AgentExecutor 类。 整个 PPAO Loop 被建模为一个 Future 链， 每个阶段接收并传递 {@link AgentContext} 作为状态载体。
 * 其中 Act 阶段内部包含一个 ReAct 微循环（战术执行态闭环）。
 */
public class AgentExecutor {

  private static final Logger logger = LoggerFactory.getLogger(AgentExecutor.class);

  private final Vertx vertx;
  private final Agent agent;
  private final AgentConfig config;
  private volatile ExecutionEngine executionEngine;

  /** 可选的 WAL 会话，注入后启用双轨制持久化与崩溃恢复 */
  private WalSession wal;

  /** 可选的工具调用守卫代理，注入后每个 TOOL_CALL 会经过 Guardrail 预检/后检 */
  private GuardrailToolProxy guardrailToolProxy;

  /** Agent 执行流事件监听器列表（Observer 模式） */
  private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();

  /** 取消标志 — 设置后 PPAO 循环在下一个安全点终止 */
  private volatile boolean cancelled;

  public AgentExecutor(Vertx vertx, Agent agent) {
    this.vertx = Objects.requireNonNull(vertx, "vertx must not be null");
    this.agent = Objects.requireNonNull(agent, "agent must not be null");
    this.config = agent.config();
    // ExecutionEngine 替换已过时的 ToolRegistry.execute()，提供沙箱/超时控制
    // 惰性初始化：允许 toolRegistry 在 Agent 创建后注入
    this.executionEngine = null;
    this.guardrailToolProxy = null;
  }

  // ========================================================================
  // GuardrailToolProxy 注入
  // ========================================================================

  /**
   * 注入 {@link GuardrailToolProxy}，为每个 TOOL_CALL 启用 Guardrail 预检/后检。
   *
   * <p>注入后，Micro-ReAct 循环中的 {@link #dispatchToolCall} 会通过代理调用 {@link ExecutionEngine}，在工具执行前后自动运行
   * PreValidator/PostValidator 链 （工具存在性检查、微循环检测、结果一致性校验等）。
   *
   * @param proxy 已配置的 GuardrailToolProxy 实例
   * @return this（链式调用）
   */
  public AgentExecutor withGuardrailToolProxy(GuardrailToolProxy proxy) {
    this.guardrailToolProxy = Objects.requireNonNull(proxy, "guardrailToolProxy must not be null");
    logger.info("[GuardrailToolProxy] Guardrail tool proxy enabled for AgentExecutor");
    return this;
  }

  /** 检查 GuardrailToolProxy 是否已注入。 */
  public boolean isGuardrailToolProxyEnabled() {
    return guardrailToolProxy != null;
  }

  // ========================================================================
  // WAL 注入
  // ========================================================================

  /**
   * 注入 {@link WalSession}，启用 WAL + Checkpoint 双轨制持久化与崩溃恢复。
   *
   * <p>注入后，AgentExecutor 会在以下生命周期点自动写入 WAL/Checkpoint：
   *
   * <ul>
   *   <li><b>Perceive</b> — append user 消息 + onUserInput Checkpoint
   *   <li><b>Micro-ReAct Dispatch (LLM)</b> — 在 LLM 响应后 append assistant 消息
   *   <li><b>Micro-ReAct Dispatch (Tool)</b> — 执行前 append assistant_tool_calls，执行后 append tool 结果
   *   <li><b>Observe (Macro)</b> — 每个 Macro ReAct 循环结束时触发 onReActCycleEnd Checkpoint
   *   <li><b>Fatal Error</b> — 触发 onError 紧急 Checkpoint
   * </ul>
   *
   * @param wal 已配置的 WalSession 实例
   * @return this（链式调用）
   */
  public AgentExecutor withWal(WalSession wal) {
    this.wal = Objects.requireNonNull(wal, "wal must not be null");
    logger.info("[WAL] WAL integration enabled for AgentExecutor");
    return this;
  }

  /**
   * 注册 Agent 执行流事件监听器（Observer 模式）。
   *
   * <p>监听 Micro-ReAct 循环中的 PPAO 事件序列：thought → action → observe。 支持多个监听器并发注册。
   *
   * @param listener 事件监听器
   * @return this（链式调用）
   */
  public AgentExecutor addListener(AgentEventListener listener) {
    if (listener != null) {
      this.listeners.add(listener);
    }
    return this;
  }

  /** 检查 WAL 是否已注入。 */
  public boolean isWalEnabled() {
    return wal != null;
  }

  /** 获取注入的 WalSession（可能为 null）。 */
  public WalSession wal() {
    return wal;
  }

  // ========================================================================
  // 公共 API
  // ========================================================================

  /** 取消当前 PPAO 执行。可在任意线程安全调用。 */
  public void cancel() {
    this.cancelled = true;
    logger.info("[Cancel] PPAO execution cancelled");
  }

  /**
   * 启动 PPAO 循环。
   *
   * @param input 用户输入或环境信号
   * @return 完成后的 AgentContext（包含最终结果）
   */
  public Future<AgentContext> execute(String input) {
    AgentContext context = new AgentContext(config.maxIterations());
    return executeLoop(input, context);
  }

  /**
   * 使用客户端指定的 sessionId 启动 PPAO 循环。
   *
   * @param input 用户输入或环境信号
   * @param sessionId 客户端传入的会话 ID（WAL 恢复用）；为空时自动生成
   * @return 完成后的 AgentContext
   */
  public Future<AgentContext> execute(String input, String sessionId) {
    String sid =
        (sessionId != null && !sessionId.isBlank())
            ? sessionId
            : SnowflakeIdGenerator.generateSessionId();
    AgentContext context = new AgentContext(sid);
    return executeLoop(input, context);
  }

  /**
   * 使用预填充的上下文启动 PPAO 循环。
   *
   * @param input 用户输入
   * @param context 预填充的 Agent 上下文
   * @return 完成后的 AgentContext
   */
  public Future<AgentContext> execute(String input, AgentContext context) {
    return executeLoop(input, context);
  }

  // ========================================================================
  // Macro Loop (PPAO)
  // ========================================================================

  /**
   * 启动整个 PPAO 递归循环（Macro Loop）。
   *
   * <p>1. Perceive 输入，初始化上下文 2. 进入 loopBody 递归 3. 任何 fatal error 被 handleFatalError 捕获
   */
  private Future<AgentContext> executeLoop(String input, AgentContext context) {
    logger.info("[PPAO] START Agent {} maxIterations={}", agent.agentId(), config.maxIterations());

    return perceive(input, context)
        .compose(this::loopBody)
        .otherwise(err -> handleFatalError(err, context));
  }

  /**
   * PPAO 递归循环体（Macro Loop body）。
   *
   * <p>每一轮 Macro 迭代执行： Plan → Verify(Pre) → [Act: Micro-ReAct] → Verify(Post) → Reflect
   * 然后根据终止条件决定是退出还是递归进入下一轮。
   */
  private Future<AgentContext> loopBody(AgentContext context) {
    // 检查取消信号
    if (cancelled) {
      logger.warn("[PPAO] Cancelled at loopBody start");
      context.put("result", "[Cancelled]");
      context.transitionTo(AgentContext.Phase.FINISH);
      return Future.succeededFuture(context);
    }
    // 检查是否应提前终止
    if (agent.shouldFinish(context, null)) {
      logger.info(
          "[PPAO] Agent {} early finish at iteration {}/{}",
          agent.agentId(),
          context.iteration(),
          config.maxIterations());
      // WAL: FINISHED checkpoint
      if (wal != null) {
        var vars = new java.util.LinkedHashMap<>(context.asMap());
        vars.put("iteration", context.iteration());
        vars.put("phase", context.currentPhase().name());
        vars.put("reason", "early_finish");
        wal.checkpointOnReActEnd(context.sessionId(), Checkpoint.NODE_FINISHED, vars, "");
      }
      return Future.succeededFuture(context);
    }

    return Future.succeededFuture(context)
        .compose(this::plan) // Macro: Plan (制定阶段性目标)
        .compose(this::verifyPre) // Macro: Verify(Pre) (目标拦截)
        .compose(this::actWithMicroReAct) // Macro: Act (内含 Micro-ReAct 战术循环)
        .compose(this::observe) // Macro: Observe (汇总观测结果)
        .compose(this::verifyPost) // Macro: Verify(Post) (结果审计)
        .compose(this::reflect) // Macro: Reflect (战略复盘)
        .compose(
            ctx -> {
              // 递归：如果不应终止则继续下一轮 Macro 迭代
              if (agent.shouldFinish(ctx, null)) {
                logger.info(
                    "[PPAO] Agent {} normal finish at iteration {}/{}",
                    agent.agentId(),
                    ctx.iteration(),
                    config.maxIterations());
                // WAL: FINISHED checkpoint
                if (wal != null) {
                  var vars = new java.util.LinkedHashMap<>(ctx.asMap());
                  vars.put("iteration", ctx.iteration());
                  vars.put("phase", ctx.currentPhase().name());
                  vars.put("reason", "normal_finish");
                  wal.checkpointOnReActEnd(ctx.sessionId(), Checkpoint.NODE_FINISHED, vars, "");
                }
                return Future.succeededFuture(ctx);
              }
              // 每轮 Macro 迭代递增计数器，确保 isMaxIterationsReached 兜底生效
              ctx.incrementIteration();
              return loopBody(ctx);
            });
  }

  // ========================================================================
  // 内部记录
  // ========================================================================

  /** 内部记录，将一个 {@link StepResult} 与当前 {@link AgentContext} 关联。 用于在 PPAO 阶段之间传递两者。 */
  private record StepWithContext(AgentContext context, StepResult result) {

    StepWithContext {
      Objects.requireNonNull(context, "context must not be null");
      Objects.requireNonNull(result, "result must not be null");
    }

    /** 便捷方法：获取步骤中的 Continue action */
    Action nextAction() {
      return result instanceof StepResult.Continue c ? c.nextAction() : null;
    }

    /** 便捷方法：获取步骤中的 Observation */
    Observation observation() {
      return result instanceof StepResult.Continue c ? c.observation() : null;
    }
  }

  // ========================================================================
  // Macro PPAO 各阶段
  // ========================================================================

  /** 1. Perceive: 感知输入，构建上下文 */
  private Future<AgentContext> perceive(String input, AgentContext context) {
    logger.info(
        "[PPAO] Perceive: input={}", input.length() > 80 ? input.substring(0, 80) + "..." : input);
    logger.debug("[Perceive] input={}", input);
    context.transitionTo(AgentContext.Phase.PERCEIVING);

    // WAL: 记录 system prompt + tool_register + 用户输入 + 用户输入 Checkpoint
    if (wal != null) {
      String sysPrompt = org.cland.alice.core.agent.prompt.PromptManager.buildSystemPrompt();
      wal.system(context.sessionId(), sysPrompt);

      // 记录 tool_register: 将当前工具集写入 WAL
      if (agent.toolRegistry() != null) {
        try {
          var allTools = agent.toolRegistry().allTools();
          if (!allTools.isEmpty()) {
            var tools =
                allTools.stream()
                    .<java.util.Map<String, Object>>map(
                        meta -> {
                          var function = new java.util.LinkedHashMap<String, Object>();
                          function.put("name", meta.name());
                          function.put("description", meta.description());
                          function.put("parameters", meta.inputSchema());
                          var tool = new java.util.LinkedHashMap<String, Object>();
                          tool.put("type", "function");
                          tool.put("function", function);
                          return tool;
                        })
                    .collect(java.util.stream.Collectors.toList());
            String toolsJson =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tools);
            wal.toolRegister(context.sessionId(), toolsJson);
          }
        } catch (Exception e) {
          logger.warn("[Perceive] Failed to record tool_register", e);
        }
      }

      wal.user(context.sessionId(), input);
      wal.checkpointOnUserInput(context.sessionId());
      wal.checkpointOnReActEnd(
          context.sessionId(),
          Checkpoint.NODE_PERCEIVING,
          java.util.Map.of(
              "input_length", input.length(),
              "iteration", context.iteration(),
              "phase", context.currentPhase().name()),
          "");
    }

    context.put("input", input);
    context.put("prompt", input);
    context.incrementIteration();

    context.transitionTo(AgentContext.Phase.PLANNING);
    return Future.succeededFuture(context);
  }

  /** 2. Plan: 基于上下文制定阶段性目标（Macro 规划） */
  private Future<StepWithContext> plan(AgentContext context) {
    logger.info("[PPAO] Plan: iteration={}", context.iteration());
    logger.debug("[Plan] iteration={}", context.iteration());
    context.transitionTo(AgentContext.Phase.PLANNING);

    // 通过状态机判断终态：如果已经是终态，跳过规划器直接 FINISH
    if (context.phaseGraph().isTerminal(context.currentPhase())) {
      logger.info("[Plan] Phase {} is terminal, skipping planner", context.currentPhase());
      Action finishAction = Action.finish();
      context.appendThought("Plan: " + finishAction.type() + " -> " + finishAction.target());
      return Future.succeededFuture(
          new StepWithContext(context, new StepResult.Continue(finishAction)));
    }

    Action nextAction;
    if (agent.plannerService() != null) {
      // 用 PromptManager 渲染 planner.ftl 并注入 context，供 FastPathStrategy 做意图分类
      String plannerPrompt = PromptManager.buildPlannerPrompt(context.asMap());
      context.put("plannerPrompt", plannerPrompt);

      // PlannerService.plan() 返回 Plan，转为意图 Map
      // PlannerService 内部也有 result 检查作为防御兜底
      Plan plan = agent.plannerService().plan(context.asMap());

      // 记录 planner 的两条消息到 WAL：prompt（请求）→ intent（响应），通过 plannerTraceId 串联
      String plannerTraceId = "planner_" + System.currentTimeMillis();
      if (wal != null) {
        wal.plannerPrompt(
            context.sessionId(),
            plannerPrompt,
            java.util.Map.of(
                "prompt", context.containsKey("prompt") ? context.get("prompt").toString() : ""));
      }
      Object planIntent = plan.metadata().get("intent");
      if (planIntent != null) {
        context.put("plannerIntent", planIntent);
        logger.info("[Plan] planner intent: {}", planIntent);
        if (wal != null) {
          String rawResponse =
              plan.metadata().containsKey("plannerRawResponse")
                  ? plan.metadata().get("plannerRawResponse").toString()
                  : planIntent.toString();
          Object chain = plan.metadata().get("intentChain");
          java.util.Map<String, Object> plannerMeta = new java.util.LinkedHashMap<>();
          plannerMeta.put(
              "prompt", context.containsKey("prompt") ? context.get("prompt").toString() : "");
          if (chain != null) plannerMeta.put("intentChain", chain);
          wal.plannerIntent(context.sessionId(), rawResponse, planIntent.toString(), plannerMeta);
        }
      }

      // SOP 状态：从 Plan.metadata 提取，写入 AgentContext（可变）
      Object sopActive = plan.metadata().get("sopActive");
      if (sopActive != null) {
        context.put("sopActive", sopActive);
        context.put("sopId", plan.metadata().getOrDefault("sopId", ""));
        context.put("sopStepIdx", plan.metadata().getOrDefault("sopStepIdx", 0));
        context.put("sopSteps", plan.metadata().get("sopSteps"));
        logger.debug(
            "[Plan] SOP state synced: active={}, stepIdx={}",
            sopActive,
            plan.metadata().get("sopStepIdx"));
      }

      Map<String, Object> intent = planToIntent(plan, context.asMap());
      nextAction = mapToAction(intent);
    } else {
      String rawPrompt =
          context.containsKey("prompt") ? context.get("prompt").toString() : "Hello!";
      String modelId = config.defaultModelId();
      // 通过 PromptManager 构建 Core Loop Prompt
      String lastObservation =
          context.containsKey("lastObservation") ? context.get("lastObservation").toString() : null;
      String lastFeedback =
          context.containsKey("lastFeedback") ? context.get("lastFeedback").toString() : null;
      String enhancedPrompt =
          PromptManager.buildCoreLoopPrompt(rawPrompt, lastObservation, lastFeedback);

      nextAction = Action.llmInference(modelId, enhancedPrompt);
    }

    logger.info("[Plan] action={}", nextAction);
    context.appendThought("Plan: " + nextAction.type() + " -> " + nextAction.target());
    return Future.succeededFuture(
        new StepWithContext(context, new StepResult.Continue(nextAction)));
  }

  /** 3. Verify (Pre): 在 Act 前拦截检查战略目标的安全性/策略合规性 */
  private Future<StepWithContext> verifyPre(StepWithContext stepWithCtx) {
    logger.info("[PPAO] Verify(Pre): checking action");
    Action action = stepWithCtx.nextAction();
    AgentContext ctx = stepWithCtx.context();

    if (action == null) {
      return Future.succeededFuture(stepWithCtx);
    }

    logger.debug("[Verify/Pre] action={}", action);
    ctx.transitionTo(AgentContext.Phase.VERIFYING_PRE);

    if (agent.verifyPre(action)) {
      logger.debug("[Verify/Pre] approved");
      return Future.succeededFuture(stepWithCtx);
    }

    logger.warn("[Verify/Pre] blocked action={}", action);
    Action revision = Action.revision("Blocked by pre-verify: " + action);
    ctx.appendThought("Pre-verify blocked: " + action.type());
    return Future.succeededFuture(new StepWithContext(ctx, new StepResult.Continue(revision)));
  }

  /**
   * 4. Act (with Micro-ReAct Loop): 执行战略目标，内部嵌入战术级 ReAct 循环。
   *
   * <p>对应设计文档中的 Micro-ReAct Loop（执行态闭环）：
   *
   * <pre>
   *   Reasoning → Dispatch → Observe → (loop until sub-goal done or circuit break)
   * </pre>
   *
   * <p>如果是 {@code LLM_INFERENCE} 或 {@code TOOL_CALL} 类型的 Action， 进入 Micro-ReAct 循环：
   *
   * <ol>
   *   <li>执行当前 Action（Dispatch）
   *   <li>收集观察结果（Observe）
   *   <li>基于观察，通过 planner.reason() 生成下一步微意图（Reason）
   *   <li>若为 FINISH 或达到熔断条件则退出循环
   *   <li>否则继续 Dispatch → Observe → Reason
   * </ol>
   */
  private Future<StepWithContext> actWithMicroReAct(StepWithContext stepWithCtx) {
    Action action = stepWithCtx.nextAction();
    AgentContext ctx = stepWithCtx.context();

    if (action == null) {
      return Future.succeededFuture(stepWithCtx);
    }

    // skipMicro: 跳过 Micro-ReAct 战术循环，仅执行 Macro 循环
    if (config.skipMicro()) {
      logger.info(
          "[PPAO] Act: skipMicro=true, skipping Micro-ReAct loop, initial action={}", action);
      ctx.transitionTo(AgentContext.Phase.ACTING);
      ctx.appendThought("Act: skipMicro enabled, skipped tactical loop");
      ctx.put("__skip_micro", "true");
      return Future.succeededFuture(
          new StepWithContext(ctx, new StepResult.Continue(Action.finish())));
    }

    logger.info("[PPAO] Act: entering Micro-ReAct loop, initial action={}", action);
    ctx.transitionTo(AgentContext.Phase.ACTING);

    // 根据初始 Action 类型进入不同的执行路径
    return switch (action.type()) {
      case LLM_INFERENCE -> microReActLoop(ctx, action);
      case TOOL_CALL -> microReActLoop(ctx, action);
      case FINISH ->
          Future.succeededFuture(
              new StepWithContext(
                  ctx.put("result", action.target() != null ? action.target() : ""),
                  new StepResult.Finish(
                      action.target() != null ? action.target() : "",
                      "Agent finished by explicit FINISH action")));
      case REVISION -> {
        String feedback =
            action.parameters().getOrDefault("feedback", "Revision requested").toString();
        ctx.appendThought("Revision triggered: " + feedback);
        yield Future.succeededFuture(new StepWithContext(ctx, stepWithCtx.result()));
      }
      case OBSERVE ->
          Future.succeededFuture(
              new StepWithContext(ctx, new StepResult.Continue(Action.finish())));
      case WAIT -> {
        Promise<StepWithContext> promise = Promise.promise();
        vertx.setTimer(
            1000, id -> promise.complete(new StepWithContext(ctx, stepWithCtx.result())));
        yield promise.future();
      }
    };
  }

  /**
   * 运行 Micro-ReAct 循环并消费所有排队的后续动作（__next_action_type）。
   *
   * <p>dispatchToolCall 可能返回一个 Continue(Action.llmInference) 来触发后续 LLM 调用。 为了避免嵌套 compose 链，该
   * action 被保存为 __next_action_type/__next_action_target/ __next_action_prompt，而 microReActStep 返回
   * Continue(null)。当 micro 循环退出时， 此方法检查这些字段，若存在则用新的 Micro-ReAct 循环执行该 action，防止宏循环过早接管。
   */

  /**
   * Micro-ReAct 循环：Reason → Dispatch → Observe → (loop | break)。
   *
   * <p>这是设计文档中"战术执行态闭环"的实现。 在当前 Action 执行完毕后，基于观察结果调用 planner 生成下一个微意图， 直到满足终止条件（FINISH / 熔断 /
   * 最大微迭代次数）。
   */
  private Future<StepWithContext> microReActLoop(AgentContext ctx, Action initialAction) {
    // Micro-ReAct 熔断参数
    final int maxMicroIterations = config.maxMicroDepth();
    final String originalPrompt = ctx.containsKey("prompt") ? ctx.get("prompt").toString() : "";

    // 缓存 Micro-ReAct 系统 prompt（静态，用于 system role）
    ctx.put(
        "__micro_system_prompt",
        org.cland.alice.core.agent.prompt.PromptManager.buildMicroLoopSystemPrompt());

    return microReActStep(ctx, initialAction, originalPrompt, 0, maxMicroIterations);
  }

  /**
   * Micro-ReAct 单步递归：执行 Action → 观察 → 推理下一步 → 递归/终止。
   *
   * @param ctx 当前 Agent 上下文
   * @param currentAction 当前要执行的 Action
   * @param originalPrompt 原始的 Macro 级别 prompt
   * @param depth 当前 Micro 迭代深度
   * @param maxDepth 最大 Micro 迭代深度（熔断阈值）
   * @return 执行结果（包含最终 Action 和观察）
   */
  private Future<StepWithContext> microReActStep(
      AgentContext ctx, Action currentAction, String originalPrompt, int depth, int maxDepth) {

    logger.debug("[Micro-ReAct] step depth={}/{} action={}", depth, maxDepth, currentAction);

    // 熔断检查
    if (depth >= maxDepth) {
      logger.warn("[Micro-ReAct] circuit breaker triggered at depth={}", depth);
      ctx.appendThought("[Micro-ReAct] Circuit breaker: max depth reached");

      // WAL: 熔断紧急 Checkpoint
      if (wal != null) {
        wal.checkpointOnError(
            ctx.sessionId(), "CIRCUIT_BREAKER", "Micro-ReAct circuit breaker at depth " + depth);
      }

      // 收集已执行工具的累积结果，避免熔断后丢失进度
      String actionLog = ctx.containsKey("__action_log") ? ctx.get("__action_log").toString() : "";
      if (!actionLog.isBlank()) {
        ctx.put(
            "__system_event",
            "[System] Circuit breaker: max depth ("
                + maxDepth
                + ") reached after "
                + actionLog.split("\n\n").length
                + " tool calls");
        ctx.appendThought("[System] Circuit breaker at depth " + depth);
        Observation progressObs = Observation.success(actionLog);
        ctx.put("lastObservation", progressObs);
        ctx.put("lastActionResult", "Micro-ReAct completed with " + depth + " steps");
        ctx.put("result", actionLog);
        logger.info(
            "[Micro-ReAct] Circuit breaker preserved {} chars of tool results", actionLog.length());

        if (wal != null) {
          wal.checkpointOnReActEnd(ctx.sessionId(), "ACTING_FINISHED", ctx.asMap(), actionLog);
        }

        return Future.succeededFuture(
            new StepWithContext(
                ctx,
                new StepResult.Finish(
                    actionLog,
                    "Micro-ReAct circuit breaker at depth "
                        + depth
                        + " with "
                        + actionLog.length()
                        + " chars of results")));
      }

      return Future.succeededFuture(
          new StepWithContext(ctx, new StepResult.Continue(Action.finish())));
    }

    // === Dispatch (执行) ===
    Future<StepWithContext> dispatchFuture =
        switch (currentAction.type()) {
          case LLM_INFERENCE -> dispatchLlmInference(ctx, currentAction);
          case TOOL_CALL -> dispatchToolCall(ctx, currentAction);
          default ->
              // 非 LLM/TOOL 类型不进入 Micro-ReAct
              Future.succeededFuture(
                  new StepWithContext(ctx, new StepResult.Continue(currentAction)));
        };

    return dispatchFuture.compose(
        stepResult -> {
          AgentContext updatedCtx = stepResult.context();
          StepResult result = stepResult.result();

          // === Observe (观察结果) ===
          if (result instanceof StepResult.Finish || result instanceof StepResult.Failure) {
            // 终态：退出 Micro-ReAct
            return Future.succeededFuture(stepResult);
          }

          // 提取观察
          Observation obs = stepResult.observation();
          if (obs != null) {
            updatedCtx.appendThought("[Micro-ReAct] Observed: " + obs.summary());
            updatedCtx.put("lastObservation", obs);
            updatedCtx.put("lastActionResult", obs.summary());
          }

          // === Reason (基于观察推理下一步微意图) ===
          // === Reason (基于观察推理下一步微意图) ===
          // Micro-ReAct 的 Reason 阶段直接分派 tool_call / follow-up LLM。
          // PlannerService 只用于 Macro Plan 阶段（plan() 方法），不参与 Micro 循环。

          Action continueAction = result instanceof StepResult.Continue c ? c.nextAction() : null;

          logger.warn(
              "[Micro-ReAct/Reason] exit: stepResult type={} continueAction={}",
              result.getClass().getSimpleName(),
              continueAction != null
                  ? continueAction.type() + "/" + continueAction.target()
                  : "null");

          if (continueAction != null
              && continueAction.type() != Action.Type.FINISH
              && continueAction.type() != Action.Type.REVISION) {
            // Clean up stale state from previous LLM iteration to prevent
            // stale tool_calls/__finish_reason from leaking into the follow-up.
            updatedCtx.remove("__tool_calls");
            updatedCtx.remove("__tool_call_index");
            updatedCtx.remove("__finish_reason");
            updatedCtx.remove("__turn_end");
            updatedCtx.remove("__true_start");
            // Dispatch follow-up LLM action directly (tool result inserted into prompt)
            logger.warn(
                "[Micro-ReAct/Reason] dispatching follow-up LLM: type={} target={} depth={}",
                continueAction.type(),
                continueAction.target(),
                depth);
            return microReActStep(updatedCtx, continueAction, originalPrompt, depth + 1, maxDepth);
          }

          // 1. Dispatch structured tool_calls from Function Calling (PARALLEL via virtual threads)
          Object rawToolCalls = updatedCtx.get("__tool_calls");
          String finishReason =
              updatedCtx.containsKey("__finish_reason")
                  ? updatedCtx.get("__finish_reason").toString()
                  : "stop";

          if (rawToolCalls instanceof java.util.List<?> tcList && !tcList.isEmpty()) {
            @SuppressWarnings("unchecked")
            java.util.List<Call.ToolCall> toolCalls = (java.util.List<Call.ToolCall>) tcList;

            // ── Parallel dispatch: fire all tool calls concurrently via virtual threads ──
            // Lazily init ExecutionEngine (thread-safe)
            if (executionEngine == null) {
              synchronized (this) {
                if (executionEngine == null && agent.toolRegistry() != null) {
                  executionEngine =
                      ExecutionEngine.builder().registry(agent.toolRegistry()).build();
                  logger.info("[Micro-ReAct/Tool] ExecutionEngine lazily initialized (parallel)");
                }
              }
            }
            if (executionEngine == null) {
              logger.warn("[Micro-ReAct/Tool] no ExecutionEngine for parallel dispatch");
              return Future.succeededFuture(
                  new StepWithContext(
                      updatedCtx,
                      new StepResult.Continue(
                          Action.revision("No ExecutionEngine for parallel tool dispatch"),
                          Observation.failure("ExecutionEngine not configured"))));
            }

            @SuppressWarnings("unchecked")
            java.util.Map<String, String> readFiles =
                (java.util.Map<String, String>) updatedCtx.get("__read_files");

            var virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
            List<CompletableFuture<ParallelToolResult>> parallelFutures = new ArrayList<>();

            for (Call.ToolCall tc : toolCalls) {
              java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
              if (tc.arguments() != null && !tc.arguments().isBlank()) {
                params.putAll(parseToolArgsJson(tc.arguments()));
              }
              // read_file cache skip
              if ("read_file".equals(tc.name()) && readFiles != null) {
                Object pathObj = params.get("path");
                if (pathObj instanceof String path && readFiles.containsKey(path)) {
                  String cachedContent = readFiles.get(path);
                  logger.info(
                      "[Dispatch/TOOL_CALL] read_file skipped (already read, parallel): {} (serving {} chars from cache)",
                      path,
                      cachedContent != null ? cachedContent.length() : 0);
                  parallelFutures.add(
                      CompletableFuture.completedFuture(
                          new ParallelToolResult(
                              tc,
                              params,
                              ToolResult.builder()
                                  .status(
                                      org.cland.alice.tool.gateway.engine.ToolResult.Status.SUCCESS)
                                  .summary(
                                      "[CACHED] "
                                          + path
                                          + " was already read ("
                                          + (cachedContent != null ? cachedContent.length() : 0)
                                          + " chars).")
                                  .rawData(
                                      cachedContent != null ? cachedContent : "[CACHED] " + path)
                                  .metadata(Map.of("toolName", tc.name(), "cached", "true"))
                                  .build(),
                              true)));
                  continue;
                }
              }

              // WAL record before execution
              if (wal != null) {
                wal.assistantToolCalls(
                    updatedCtx.sessionId(),
                    java.util.List.of(
                        org.cland.alice.core.agent.wal.ToolCall.of(
                            String.valueOf(SnowflakeIdGenerator.getInstance().nextId()),
                            tc.name(),
                            params)));
              }

              // Parallel execution via virtual thread
              parallelFutures.add(
                  CompletableFuture.supplyAsync(
                      () -> {
                        try {
                          ToolResult r =
                              guardrailToolProxy != null
                                  ? guardrailToolProxy.invoke(tc.name(), params)
                                  : executionEngine.invoke(tc.name(), params);
                          return new ParallelToolResult(tc, params, r, false);
                        } catch (Exception e) {
                          return new ParallelToolResult(
                              tc,
                              params,
                              ToolResult.failure(tc.name() + " error: " + e.getMessage()),
                              false);
                        }
                      },
                      virtualExecutor));
            }

            // Await all, bridge to Vert.x Future
            Promise<StepWithContext> parallelPromise = Promise.promise();
            CompletableFuture.allOf(parallelFutures.toArray(new CompletableFuture[0]))
                .whenComplete(
                    (v, err) -> {
                      if (err != null) {
                        parallelPromise.fail(err);
                        return;
                      }

                      var batchLog = new StringBuilder();
                      @SuppressWarnings("unchecked")
                      java.util.Map<String, String> updatedReadFiles =
                          (java.util.Map<String, String>) updatedCtx.get("__read_files");

                      for (int i = 0; i < parallelFutures.size(); i++) {
                        ParallelToolResult ptr = parallelFutures.get(i).join();
                        Call.ToolCall tc = ptr.toolCall;
                        ToolResult tr = ptr.result;

                        if (!ptr.cached) {
                          boolean ok =
                              tr.status()
                                  == org.cland.alice.tool.gateway.engine.ToolResult.Status.SUCCESS;
                          // Fire action first so TUI ObserveBlock gets the correct action prefix
                          fireOnAction(tc.name(), ptr.params);
                          fireOnObserve(
                              tr.rawData() != null && !tr.rawData().isBlank()
                                  ? tr.rawData()
                                  : (tr.summary() != null ? tr.summary() : ""),
                              tr.summary() != null ? tr.summary() : "",
                              0L);

                          if (wal != null) {
                            String rc =
                                tr.rawData() != null && !tr.rawData().isBlank()
                                    ? tr.rawData()
                                    : (tr.summary() != null ? tr.summary() : "");
                            wal.toolResult(
                                updatedCtx.sessionId(),
                                String.valueOf(SnowflakeIdGenerator.getInstance().nextId()),
                                rc);
                            wal.checkpointOnToolReturn(updatedCtx.sessionId(), tc.name(), ok);
                          }

                          if ("read_file".equals(tc.name())) {
                            Object pathObj = ptr.params.get("path");
                            if (pathObj instanceof String p && !p.isBlank()) {
                              if (updatedReadFiles == null) {
                                updatedReadFiles = new java.util.HashMap<>();
                                updatedCtx.put("__read_files", updatedReadFiles);
                              }
                              String raw =
                                  tr.rawData() != null
                                      ? tr.rawData()
                                      : (tr.summary() != null ? tr.summary() : "");
                              updatedReadFiles.put(p, raw);
                            }
                          }

                          if ("write_file".equals(tc.name())) {
                            batchLog.append("Tool ").append(tc.name()).append(" succeeded.\n\n");
                          } else {
                            batchLog
                                .append("Tool ")
                                .append(tc.name())
                                .append(" returned:\n")
                                .append(
                                    tr.rawData() != null && !tr.rawData().isBlank()
                                        ? tr.rawData()
                                        : (tr.summary() != null ? tr.summary() : ""))
                                .append("\n\n");
                          }
                        } else {
                          // Fire action + observe for cached tools too (TUI pairing)
                          fireOnAction(tc.name(), ptr.params);
                          String cachedRaw =
                              tr.rawData() != null && !tr.rawData().isBlank()
                                  ? tr.rawData()
                                  : (tr.summary() != null ? tr.summary() : "");
                          fireOnObserve(
                              "[CACHED] " + tc.name() + " was already read in a previous step.",
                              "[CACHED] " + tc.name(),
                              0L);
                          batchLog
                              .append("Tool ")
                              .append(tc.name())
                              .append(" returned (cached):\n")
                              .append(cachedRaw)
                              .append("\n\n");
                        }
                      }

                      updatedCtx.remove("__tool_calls");
                      updatedCtx.remove("__tool_call_index");
                      updatedCtx.remove("__finish_reason");
                      updatedCtx.remove("__turn_end");
                      updatedCtx.remove("__true_start");

                      String actionLog = batchLog.toString();
                      if (!actionLog.isBlank()) {
                        updatedCtx.put("__action_log", actionLog);
                        logger.info(
                            "[Micro-ReAct/Reason] {} parallel tool(s) via virtual threads, feeding back LLM, depth={}",
                            toolCalls.size(),
                            depth);
                        String rawPrompt =
                            updatedCtx.containsKey("prompt")
                                ? updatedCtx.get("prompt").toString()
                                : "";
                        java.util.Set<String> readFilePaths =
                            updatedReadFiles != null ? updatedReadFiles.keySet() : null;
                        String userContent =
                            org.cland.alice.core.agent.prompt.PromptManager.buildMicroUserContent(
                                actionLog, rawPrompt, readFilePaths);
                        microReActStep(
                                updatedCtx,
                                Action.llmInference(config.defaultModelId(), userContent),
                                originalPrompt,
                                depth + 1,
                                maxDepth)
                            .onSuccess(parallelPromise::complete)
                            .onFailure(parallelPromise::fail);
                        return;
                      }

                      logger.info(
                          "[Micro-ReAct/Reason] All {} parallel tool(s) done, no results",
                          toolCalls.size());
                      parallelPromise.complete(
                          new StepWithContext(updatedCtx, new StepResult.Continue(null)));
                    });

            return parallelPromise.future();
          }

          // 2. No tool calls - determine next action from finish_reason
          logger.info(
              "[Micro-ReAct/Reason] finish_reason={} responseLength={} depth={}",
              finishReason,
              updatedCtx.containsKey("result") ? updatedCtx.get("result").toString().length() : 0,
              depth);

          if ("stop".equals(finishReason) || "tool_calls".equals(finishReason)) {
            // Natural completion - agent is done
            String llmOutput =
                updatedCtx.containsKey("result") ? updatedCtx.get("result").toString() : "";
            if (!llmOutput.isEmpty()) {
              Observation finalObs = Observation.success(llmOutput);
              updatedCtx.put("lastObservation", finalObs);
              updatedCtx.put("lastActionResult", llmOutput);
            }
            if (wal != null) {
              wal.checkpointOnReActEnd(
                  updatedCtx.sessionId(), "ACTING_FINISHED", updatedCtx.asMap(), "");
            }
            return Future.succeededFuture(
                new StepWithContext(
                    updatedCtx,
                    new StepResult.Finish(
                        llmOutput, "Micro-ReAct completed: finish_reason=" + finishReason)));
          }

          // Handle error finish reasons (length, content_filter, error)
          logger.warn(
              "[Micro-ReAct/Reason] Non-success finish_reason={}, finishing with error",
              finishReason);
          String llmOutput =
              updatedCtx.containsKey("result") ? updatedCtx.get("result").toString() : "";
          if (wal != null) {
            wal.checkpointOnError(
                updatedCtx.sessionId(), "FINISH_REASON_" + finishReason.toUpperCase(), llmOutput);
          }
          return Future.succeededFuture(
              new StepWithContext(
                  updatedCtx, new StepResult.Failure("LLM finished with reason: " + finishReason)));
        });
  }

  // ========================================================================
  // Dispatch (Micro-ReAct 中的执行阶段)
  // ========================================================================

  /** Dispatch LLM_INFERENCE */
  private Future<StepWithContext> dispatchLlmInference(AgentContext ctx, Action action) {
    Promise<StepWithContext> promise = Promise.promise();

    String modelId = action.target();
    String prompt = action.parameters().getOrDefault("prompt", "").toString();

    vertx
        .<StepResult>executeBlocking(
            () -> {
              try {
                ModelProvider provider = ModelProvider.getInstance();
                logger.info(
                    "[Micro-ReAct/LLM] Calling model={} promptLength={}", modelId, prompt.length());

                // 从 Action 参数中转发 thinking 参数（由 planner 注入）
                java.util.Map<String, Object> callParams = new java.util.LinkedHashMap<>();
                if (action.parameters() != null) {
                  for (var entry : action.parameters().entrySet()) {
                    String k = entry.getKey();
                    if ("enable_thinking".equals(k) || "reasoning_effort".equals(k)) {
                      callParams.put(k, entry.getValue());
                      logger.info(
                          "[Micro-ReAct/LLM] Forwarding thinking param: {}={}",
                          k,
                          entry.getValue());
                    }
                  }
                }

                // 如果 ToolRegistry 可用，附加 tools 参数以实现 Function Calling
                if (agent.toolRegistry() != null) {
                  try {
                    var allTools = agent.toolRegistry().allTools();
                    if (!allTools.isEmpty()) {
                      var tools =
                          allTools.stream()
                              .<java.util.Map<String, Object>>map(
                                  meta -> {
                                    var function = new java.util.LinkedHashMap<String, Object>();
                                    function.put("name", meta.name());
                                    function.put("description", meta.description());
                                    function.put("parameters", meta.inputSchema());
                                    var tool = new java.util.LinkedHashMap<String, Object>();
                                    tool.put("type", "function");
                                    tool.put("function", function);
                                    return tool;
                                  })
                              .collect(java.util.stream.Collectors.toList());
                      callParams.put("tools", tools);
                      logger.info("[Micro-ReAct/LLM] Attached {} tools to LLM call", tools.size());
                      logger.debug("[Micro-ReAct/LLM] Tools schema: {}", tools);
                    }
                  } catch (Exception e) {
                    logger.warn(
                        "[Micro-ReAct/LLM] Failed to generate tools schema, falling back to text-only",
                        e);
                  }
                }

                // 如果有 Micro-ReAct system prompt，通过 system role 传递
                String microSystemPrompt =
                    ctx.containsKey("__micro_system_prompt")
                        ? ctx.get("__micro_system_prompt").toString()
                        : null;
                if (microSystemPrompt != null) {
                  logger.debug(
                      "[Micro-ReAct/LLM] Using system prompt ({} chars) + user prompt ({} chars)",
                      microSystemPrompt.length(),
                      prompt.length());
                }
                Call call =
                    microSystemPrompt != null
                        ? provider.dispatch(modelId, microSystemPrompt, prompt, callParams)
                        : provider.dispatch(modelId, prompt, callParams);

                if (call.status() == org.cland.alice.model.CallStatus.FINISHED
                    && call.result() != null) {
                  Call.Response response = call.result();
                  String content = response.content() != null ? response.content() : "";
                  java.util.List<Call.ToolCall> toolCalls = response.toolCalls();

                  logger.info(
                      "[Micro-ReAct/LLM] Response model={} responseLength={} toolCalls={} rawMetadata={}",
                      modelId,
                      content.length(),
                      toolCalls.size(),
                      response.metadata().containsKey("raw")
                          ? response
                              .metadata()
                              .get("raw")
                              .toString()
                              .substring(
                                  0,
                                  Math.min(
                                      3000, response.metadata().get("raw").toString().length()))
                          : "no-raw");
                  // Only set result if content is non-empty, so that reflect() can
                  // set a fallback message when the LLM returns empty content.
                  if (content != null && !content.isBlank()) {
                    ctx.put("result", content);
                  }
                  ctx.put("__llm_response", content != null ? content : "");
                  ctx.put("__llm_reasoning", extractReasoningFromRaw(response));
                  // PPAO: fire thought event for TUI ThinkBlock
                  Object reasoning = ctx.get("__llm_reasoning");
                  fireOnThought(reasoning != null ? reasoning.toString() : "");
                  String finishReason = extractFinishReasonFromRaw(response);
                  ctx.put("__finish_reason", finishReason);
                  ctx.put("__turn_end", "stop".equals(finishReason));
                  ctx.put(
                      "__true_start",
                      "stop".equals(finishReason)
                          || finishReason == null
                          || finishReason.isBlank());
                  ctx.remove("__tool_call_index");

                  // 如果 LLM 返回了结构化 tool_calls，存入上下文
                  if (toolCalls != null && !toolCalls.isEmpty()) {
                    ctx.put("__tool_calls", toolCalls);
                    logger.info(
                        "[Micro-ReAct/LLM] Received {} structured tool call(s) via Function Calling",
                        toolCalls.size());
                  }

                  // WAL: 记录 assistant 回复（含 reasoning/原始输出，跳过空消息）
                  if (wal != null) {
                    String walContent = content;
                    boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
                    // 当 content 为空且有 tool_calls 时，从原始元数据中提取 reasoning_content
                    if ((walContent == null || walContent.isEmpty()) && hasToolCalls) {
                      Object raw = response.metadata().get("raw");
                      if (raw != null) {
                        String rawStr = raw.toString();
                        int idx = rawStr.indexOf("\"reasoning_content\":\"");
                        if (idx >= 0) {
                          idx += 21;
                          StringBuilder sb = new StringBuilder();
                          while (idx < rawStr.length()) {
                            char c = rawStr.charAt(idx);
                            if (c == '\\' && idx + 1 < rawStr.length()) {
                              sb.append(rawStr.charAt(idx + 1));
                              idx += 2;
                            } else if (c == '"') {
                              break;
                            } else {
                              sb.append(c);
                              idx++;
                            }
                          }
                          walContent = "<thought>" + sb.toString() + "</thought>";
                        }
                      }
                    }
                    // 跳过完全空的 assistant 消息
                    if (walContent != null && !walContent.isEmpty()) {
                      if (hasToolCalls) {
                        // 有 tool calls 时，此内容为推理思考
                        wal.think(ctx.sessionId(), walContent);
                      } else {
                        // 无 tool calls 时，此内容为最终回复
                        wal.finalAnswer(ctx.sessionId(), walContent);
                      }
                    }
                  }

                  // 将 LLM 输出包装为 Observation，让 Reason 阶段处理 tool_calls 或文本标记
                  return new StepResult.Continue(null, Observation.success(content));
                } else {
                  // WAL: 记录失败的 LLM 回复
                  if (wal != null) {
                    wal.finalAnswer(ctx.sessionId(), "[LLM Error: " + call.status() + "]");
                  }

                  return new StepResult.Failure("LLM call failed: " + call.status());
                }
              } catch (Exception e) {
                logger.error("[Micro-ReAct/LLM] error", e);
                return new StepResult.Failure("LLM call error: " + e.getMessage());
              }
            })
        .onComplete(
            ar -> {
              if (ar.succeeded()) {
                promise.complete(new StepWithContext(ctx, ar.result()));
              } else {
                promise.fail(ar.cause());
              }
            });

    return promise.future();
  }

  // ========================================================================
  // Dispatch TOOL_CALL（统一走 ExecutionEngine）
  // ========================================================================

  private Future<StepWithContext> dispatchToolCall(AgentContext ctx, Action action) {
    // PPAO: fire action event for TUI ActionBlock
    fireOnAction(action.target(), action.parameters());

    logger.info("[Dispatch/TOOL_CALL] target={} params={}", action.target(), action.parameters());

    if (agent.toolRegistry() == null) {
      logger.info("[Micro-ReAct/Tool] no ToolRegistry available");
      return Future.succeededFuture(
          new StepWithContext(
              ctx,
              new StepResult.Continue(
                  Action.revision("No ToolRegistry available for tool: " + action.target()),
                  Observation.failure("ToolRegistry not configured"))));
    }

    Promise<StepWithContext> promise = Promise.promise();

    // WAL: 在执行前记录工具调用
    if (wal != null) {
      wal.assistantToolCalls(
          ctx.sessionId(),
          java.util.List.of(
              org.cland.alice.core.agent.wal.ToolCall.of(
                  action.actionId(), action.target(), action.parameters())));
    }

    // 执行前拦截：read_file 路径已读取过则跳过，避免重复执行
    if ("read_file".equals(action.target())) {
      Object pathObj = action.parameters().get("path");
      if (pathObj instanceof String path && !path.isBlank()) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> readFiles =
            (java.util.Map<String, String>) ctx.get("__read_files");
        if (readFiles != null && readFiles.containsKey(path)) {
          String cachedContent = readFiles.get(path);
          logger.info(
              "[Dispatch/TOOL_CALL] read_file skipped (already read): {} (serving {} chars from cache)",
              path,
              cachedContent != null ? cachedContent.length() : 0);
          return Future.succeededFuture(
              new StepWithContext(
                  ctx,
                  new StepResult.Continue(
                      null,
                      Observation.success(
                          cachedContent != null
                              ? cachedContent
                              : "[CACHED] " + path + " was already read in a previous step."))));
        }
      }
    }

    vertx
        .<StepResult>executeBlocking(
            () -> {
              try {
                if (executionEngine == null) {
                  // 惰性初始化：toolRegistry 可能在 Agent 创建后通过 withToolRegistry 注入
                  synchronized (this) {
                    if (executionEngine == null) {
                      if (agent.toolRegistry() != null) {
                        executionEngine =
                            ExecutionEngine.builder().registry(agent.toolRegistry()).build();
                        logger.info("[Micro-ReAct/Tool] ExecutionEngine lazily initialized");
                      } else {
                        logger.warn("[Micro-ReAct/Tool] no ExecutionEngine available");
                        return new StepResult.Continue(
                            Action.revision("No ExecutionEngine for tool: " + action.target()),
                            Observation.failure("ExecutionEngine not configured"));
                      }
                    }
                  }
                }

                // 通过 GuardrailToolProxy 执行（含预检/后检/历史记录），
                // 未注入代理时回退到裸 ExecutionEngine
                ToolResult result;
                if (guardrailToolProxy != null) {
                  result = guardrailToolProxy.invoke(action.target(), action.parameters());
                } else {
                  result = executionEngine.invoke(action.target(), action.parameters());
                }
                boolean success =
                    result.status()
                        == org.cland.alice.tool.gateway.engine.ToolResult.Status.SUCCESS;

                logger.info(
                    "[Dispatch/TOOL_CALL] {} result status={} summary={}",
                    action.target(),
                    result.status(),
                    result.summary() != null
                        ? result.summary().substring(0, Math.min(200, result.summary().length()))
                        : "null");

                // PPAO: fire observe event for TUI ObserveBlock (使用 rawData 以获取完整工具输出)
                fireOnObserve(
                    result.rawData() != null && !result.rawData().isBlank()
                        ? result.rawData()
                        : (result.summary() != null ? result.summary() : "(empty)"),
                    result.summary() != null ? result.summary() : "",
                    0L);

                // WAL: 记录工具执行结果（含实际返回数据）
                if (wal != null) {
                  String rawData = result.rawData();
                  String summary = result.summary();
                  String resultContent =
                      rawData != null && !rawData.isBlank()
                          ? rawData
                          : (summary != null ? summary : "");
                  wal.toolResult(ctx.sessionId(), action.actionId(), resultContent);
                  wal.checkpointOnToolReturn(ctx.sessionId(), action.target(), success);
                }

                if (success) {
                  // 检查是否还有未消耗的 structured tool_calls
                  boolean hasMoreMarkers = false;
                  Object rawTc = ctx.get("__tool_calls");
                  if (rawTc instanceof java.util.List<?> tcList && !tcList.isEmpty()) {
                    int currentIdx =
                        ctx.containsKey("__tool_call_index")
                            ? Integer.parseInt(ctx.get("__tool_call_index").toString())
                            : 0;
                    hasMoreMarkers = currentIdx < tcList.size();
                  }

                  // 跟踪已读取的文件路径（必须在 hasMoreMarkers 检查之前，确保每个 tool call 都记录）
                  if ("read_file".equals(action.target())) {
                    Object pathObj = action.parameters().get("path");
                    if (pathObj instanceof String path && !path.isBlank()) {
                      @SuppressWarnings("unchecked")
                      java.util.Map<String, String> readFiles =
                          (java.util.Map<String, String>) ctx.get("__read_files");
                      if (readFiles == null) {
                        readFiles = new java.util.HashMap<>();
                        ctx.put("__read_files", readFiles);
                      }
                      String fileContent =
                          result.rawData() != null
                              ? result.rawData()
                              : (result.summary() != null ? result.summary() : "");
                      readFiles.put(path, fileContent);
                    }
                  }

                  if (hasMoreMarkers) {
                    // 仍有未消耗的标记，不调用 LLM，直接让 Reason 解析原始回复
                    return new StepResult.Continue(
                        null,
                        Observation.success(
                            "Tool "
                                + action.target()
                                + " executed successfully: "
                                + result.summary()));
                  }

                  String toolResultContent =
                      result.rawData() != null && !result.rawData().isBlank()
                          ? result.rawData()
                          : result.summary();

                  // 将本次工具执行结果累积到上下文中（仅保留最近2条，避免日志过长）
                  StringBuilder actionLogBuilder = new StringBuilder();
                  if (ctx.containsKey("__action_log")) {
                    actionLogBuilder.append(ctx.get("__action_log").toString());
                    // 如果已经有太多内容，截断保留尾部
                    String existing = actionLogBuilder.toString();
                    int idx = existing.lastIndexOf("\n\n", existing.length() - 3);
                    if (idx > 0 && existing.length() > 2000) {
                      actionLogBuilder = new StringBuilder(existing.substring(idx + 2));
                    }
                  }
                  // 跳过 write_file 结果（只报告成功，不传递文件内容）
                  if ("write_file".equals(action.target())) {
                    actionLogBuilder.append("Tool " + action.target() + " succeeded.\n\n");
                  } else {
                    actionLogBuilder.append(
                        "Tool " + action.target() + " returned:\n" + toolResultContent + "\n\n");
                  }
                  ctx.put("__action_log", actionLogBuilder.toString());

                  // 通过 PromptManager 构建 Micro User Content（user role 部分）
                  String rawPrompt = ctx.containsKey("prompt") ? ctx.get("prompt").toString() : "";
                  @SuppressWarnings("unchecked")
                  java.util.Map<String, String> readFiles =
                      (java.util.Map<String, String>) ctx.get("__read_files");
                  java.util.Set<String> readFilePaths =
                      readFiles != null ? readFiles.keySet() : null;
                  String userContent =
                      PromptManager.buildMicroUserContent(
                          actionLogBuilder.toString(), rawPrompt, readFilePaths);
                  return new StepResult.Continue(
                      Action.llmInference(config.defaultModelId(), userContent),
                      Observation.success(
                          "Tool "
                              + action.target()
                              + " executed successfully: "
                              + result.summary()));
                } else {
                  return new StepResult.Continue(
                      Action.revision(
                          "Tool execution failed: " + action.target() + " - " + result.summary()),
                      Observation.failure(
                          "Tool " + action.target() + " returned failure: " + result.summary()));
                }
              } catch (Exception e) {
                // WAL: 工具异常
                if (wal != null) {
                  wal.toolResult(
                      ctx.sessionId(), action.actionId(), "[Tool Error: " + e.getMessage() + "]");
                  wal.checkpointOnError(ctx.sessionId(), "TOOL_ERROR", e.getMessage());
                }

                // 工具调用异常，直接熔断退出循环，避免无限重试
                return new StepResult.Failure("Tool call error: " + e.getMessage());
              }
            })
        .onComplete(
            ar -> {
              if (ar.succeeded()) {
                promise.complete(new StepWithContext(ctx, ar.result()));
              } else {
                promise.fail(ar.cause());
              }
            });

    return promise.future();
  }

  // ========================================================================
  // Macro PPAO 剩余阶段 (Observe / VerifyPost / Reflect)
  // ========================================================================

  /** 5. Observe: 收集并持久化 Macro 级的观测结果 */
  private Future<StepWithContext> observe(StepWithContext stepWithCtx) {
    AgentContext ctx = stepWithCtx.context();
    StepResult result = stepWithCtx.result();

    logger.info("[PPAO] Observe: collecting macro observation");
    logger.debug("[Observe] result={}", result);
    ctx.transitionTo(AgentContext.Phase.OBSERVING);

    // 提取 Observation — 优先使用 __action_log（累积的多步工具结果）
    String actionLog = ctx.containsKey("__action_log") ? ctx.get("__action_log").toString() : null;
    if (actionLog != null && !actionLog.isBlank()) {
      int toolCount = actionLog.split("\n\n").length;
      String systemMsg = "[System] " + toolCount + " tool calls executed during this iteration";
      ctx.put("__system_event", systemMsg);
      ctx.appendThought(systemMsg);
      Observation combinedObs = Observation.success(actionLog);
      ctx.appendThought(
          "Observed: " + actionLog.length() + " chars from " + toolCount + " tool results");
      ctx.put("lastObservation", combinedObs);
      ctx.put("lastActionResult", "Tool results: " + actionLog.length() + " chars");
      logger.info(
          "[Observe] Collected {} tool results ({} chars)",
          actionLog.split("\n\n").length,
          actionLog.length());

      // fire observe event: 系统提示，不传递原始工具结果
      fireOnObserve(
          "[System] " + actionLog.split("\n\n").length + " tool calls executed",
          actionLog.length() + " chars from " + actionLog.split("\n\n").length + " tool results",
          0L);
    } else {
      Observation obs = stepWithCtx.observation();
      if (obs != null) {
        ctx.appendThought("Observed: " + obs.summary());
        ctx.put("lastObservation", obs);
      }
    }

    // 持久化到 Memory
    if (agent.memory() != null) {
      vertx.executeBlocking(
          () -> {
            agent.memory().persist(ctx.sessionId(), result.toString());
            return null;
          });
    }

    // WAL: Macro ReAct 循环结束，触发 Checkpoint（含迭代和阶段信息）
    if (wal != null) {
      String stateNode = ctx.currentPhase().name();
      var vars = new java.util.LinkedHashMap<>(ctx.asMap());
      vars.put("iteration", ctx.iteration());
      vars.put("phase", stateNode);
      vars.put("messageCount", wal != null ? wal.messageCount(ctx.sessionId()) : -1);
      wal.checkpointOnReActEnd(ctx.sessionId(), stateNode, vars, "");
    }

    ctx.transitionTo(AgentContext.Phase.VERIFYING_POST);
    return Future.succeededFuture(stepWithCtx);
  }

  /** 6. Verify (Post): 审计宏观执行结果 */
  private Future<StepWithContext> verifyPost(StepWithContext stepWithCtx) {
    AgentContext ctx = stepWithCtx.context();
    StepResult result = stepWithCtx.result();

    logger.debug("[Verify/Post] result={}", result);

    if (agent.verifyPost(result)) {
      logger.debug("[Verify/Post] audit passed");
      if (agent.shouldFinish(ctx, result)) {
        ctx.transitionTo(AgentContext.Phase.FINISH);
      }
      return Future.succeededFuture(stepWithCtx);
    }

    logger.warn("[Verify/Post] audit failed, forcing revision");
    ctx.appendThought("Post-verify failed");

    // WAL: Post-verify 失败
    if (wal != null) {
      wal.checkpointOnError(
          ctx.sessionId(),
          "POST_VERIFY_FAIL",
          "Post-verify audit rejected at iteration " + ctx.iteration());
    }

    Action revision = Action.revision("Post-verify audit rejected: " + result);
    return Future.succeededFuture(
        new StepWithContext(
            ctx,
            new StepResult.Continue(revision, Observation.blocked("Post-verify audit failed"))));
  }

  /** 7. Reflect: 注入验证反馈，准备下一轮 Macro 规划 */
  private Future<AgentContext> reflect(StepWithContext stepWithCtx) {
    AgentContext ctx = stepWithCtx.context();
    logger.debug("[Reflect] phase={}", ctx.currentPhase());

    // 如果已经是终态，直接返回
    if (ctx.currentPhase() == AgentContext.Phase.FINISH) {
      return Future.succeededFuture(ctx);
    }

    ctx.transitionTo(AgentContext.Phase.REFLECTING);

    // 检查是否应该终止（Continue with FINISH action = complete）
    if (stepWithCtx.result() instanceof StepResult.Continue cont) {
      Action nextAction = cont.nextAction();
      if (nextAction != null && nextAction.type() == Action.Type.FINISH) {
        ctx.transitionTo(AgentContext.Phase.FINISH);
        // Check both missing and blank result — dispatchLlmInference may skip
        // setting "result" when LLM returns empty content.
        if (!ctx.containsKey("result") || ctx.get("result").toString().isBlank()) {
          ctx.put("result", "Agent completed without explicit result.");
        }
        // WAL: FINISHED checkpoint
        if (wal != null) {
          var vars = new java.util.LinkedHashMap<>(ctx.asMap());
          vars.put("iteration", ctx.iteration());
          vars.put("phase", "FINISH");
          vars.put("reason", "finish_action");
          wal.checkpointOnReActEnd(ctx.sessionId(), Checkpoint.NODE_FINISHED, vars, "");
        }
        return Future.succeededFuture(ctx);
      }
    }

    // 提取 Revision 反馈
    if (stepWithCtx.result() instanceof StepResult.Continue cont) {
      Action nextAction = cont.nextAction();
      if (nextAction != null && nextAction.type() == Action.Type.REVISION) {
        String feedback =
            nextAction.parameters().getOrDefault("feedback", "No feedback provided").toString();
        ctx.appendThought("Reflect: " + feedback);
        ctx.put("lastFeedback", feedback);
        ctx.transitionTo(AgentContext.Phase.REVISION);
      }
    }

    // 检查最大迭代
    if (ctx.isMaxIterationsReached()) {
      ctx.transitionTo(AgentContext.Phase.FINISH);
      if (!ctx.containsKey("result") || ctx.get("result").toString().isBlank()) {
        ctx.put("result", "Max iterations reached without final answer.");
      }
      // WAL: FINISHED checkpoint (max iterations)
      if (wal != null) {
        var vars = new java.util.LinkedHashMap<>(ctx.asMap());
        vars.put("iteration", ctx.iteration());
        vars.put("phase", "FINISH");
        vars.put("reason", "max_iterations");
        wal.checkpointOnReActEnd(ctx.sessionId(), Checkpoint.NODE_FINISHED, vars, "");
      }
    }

    return Future.succeededFuture(ctx);
  }

  // ========================================================================
  // 错误处理
  // ========================================================================

  /** 处理 PPAO 循环中的致命错误。 将错误信息记录到上下文中，设置终态，返回 context 而非抛出。 */
  private AgentContext handleFatalError(Throwable error, AgentContext context) {
    logger.error("Agent {} fatal error in PPAO loop", agent.agentId(), error);
    context.put(
        "error",
        error.getMessage() != null
            ? error.getMessage()
            : error.getClass().getSimpleName() + " (no message)");
    context.put("status", "FATAL_ERROR");
    context.transitionTo(AgentContext.Phase.FINISH);

    // WAL: 致命错误 — 紧急 Checkpoint
    if (wal != null) {
      wal.checkpointOnError(
          context.sessionId(),
          "FATAL_ERROR",
          error.getMessage() != null ? error.getMessage() : "Unknown fatal error");
    }

    return context;
  }

  // ========================================================================
  // HumanInTheLoop 支持
  // ========================================================================

  /** 挂起信号：用于 suspendForHuman 和 resumeWithFeedback 之间的协调。 */
  private CompletableFuture<String> humanFeedbackFuture;

  /**
   * 挂起 Agent 执行，等待人类反馈。
   *
   * <p>Agent 在 HITL 场景中调用此方法，返回一个 Future。当人类通过 {@link #resumeWithFeedback(String)} 提供反馈后， Future
   * 完成，Agent 继续执行。
   *
   * @return 包含人类反馈内容的 CompletableFuture
   */
  public CompletableFuture<String> suspendForHuman() {
    logger.info("[HITL] Agent {} suspended for human feedback", agent.agentId());
    this.humanFeedbackFuture = new CompletableFuture<>();
    return this.humanFeedbackFuture;
  }

  /**
   * 注入人类反馈并唤醒挂起的 Agent。
   *
   * <p>当用户在 TUI/CLI 中输入 {@code /feedback <内容>} 时调用此方法，传入反馈内容。 CompletableFuture 完成，Agent 继续 PPAO
   * 循环。
   *
   * @param feedback 人类的反馈内容
   */
  public void resumeWithFeedback(String feedback) {
    if (humanFeedbackFuture != null && !humanFeedbackFuture.isDone()) {
      logger.info("[HITL] Resuming agent {} with feedback: {}", agent.agentId(), feedback);
      humanFeedbackFuture.complete(feedback);
      humanFeedbackFuture = null;
    } else {
      logger.warn("[HITL] No pending human feedback request for agent {}", agent.agentId());
    }
  }

  /**
   * 检查当前是否有挂起的人类反馈请求。
   *
   * @return true 如果有挂起的反馈请求
   */
  public boolean isSuspendedForHuman() {
    return humanFeedbackFuture != null && !humanFeedbackFuture.isDone();
  }

  // ========================================================================
  // 辅助工具
  // ========================================================================

  // ========================================================================
  // 辅助工具
  // ========================================================================

  /**
   * 从 LLM 输出（Observation）中解析结构化工具调用标记。
   *
   * <p>解析格式：
   *
   * <pre>
   * [TOOL_CALL: toolName(param1="value1", param2="value2")]
   * </pre>
   *
   * @param ctx 当前上下文（含 LLM 输出在 "result" 或 "lastObservation" 中）
   * @return 解析出的 Action，若无工具调用则返回 null
   */
  /**
   * 从 LLM 输出中解析下一个结构化工具调用标记。
   *
   * <p>通过 {@code __tool_call_index} 跟踪已消费的调用索引，实现顺序执行多次工具调用。
   */
  private static Action mapToAction(Map<String, Object> plan) {
    String type = (String) plan.getOrDefault("type", "LLM_INFERENCE");
    String target = (String) plan.getOrDefault("target", "gpt-4o-mini");

    return switch (type) {
      case "FINISH" -> Action.finish();
      case "TOOL_CALL" -> {
        @SuppressWarnings("unchecked")
        Map<String, Object> params =
            (Map<String, Object>) plan.getOrDefault("parameters", Map.of());
        yield Action.toolCall(target, params);
      }
      case "REVISION" -> {
        String feedback = (String) plan.getOrDefault("feedback", "Revision requested");
        yield Action.revision(feedback);
      }
      default -> { // LLM_INFERENCE 及其他
        String prompt = (String) plan.getOrDefault("prompt", "Hello!");
        var action = Action.builder().type(Action.Type.LLM_INFERENCE).target(target);
        action.parameter("prompt", prompt);
        // 转发额外参数（enable_thinking, reasoning_effort 等）
        for (var e : plan.entrySet()) {
          String k = e.getKey();
          if (!"type".equals(k)
              && !"target".equals(k)
              && !"prompt".equals(k)
              && !"thought".equals(k)) {
            Object v = e.getValue();
            if (v != null) action.parameter(k, v);
          }
        }
        if (plan.containsKey("thought")) {
          action.parameter("thought", plan.get("thought"));
        }
        yield action.build();
      }
    };
  }

  /**
   * 将 {@link Plan} 转换为 ReAct 兼容的意图 Map（仅取第一步）。
   *
   * <p>Plan 是 PlannerService 的输出，包含多个步骤。 AgentExecutor 只需要第一步作为当前 Action 意图。
   */
  private static Map<String, Object> planToIntent(Plan plan, Map<String, Object> context) {
    if (plan.steps().isEmpty()) {
      return Map.of(
          "type", "LLM_INFERENCE",
          "target", "gpt-4o-mini",
          "prompt", context.getOrDefault("prompt", "Hello!"));
    }

    Plan.Step firstStep = plan.steps().get(0);

    return switch (firstStep.intent()) {
      case FINISH -> Map.of("type", "FINISH", "target", "FINISH");
      case REVISION -> {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("type", "REVISION");
        m.put("target", "REVISION");
        m.put("feedback", firstStep.parameters().getOrDefault("feedback", "Revision requested"));
        yield Map.copyOf(m);
      }
      case SEARCH -> {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("type", "TOOL_CALL");
        m.put("target", firstStep.target());
        if (!firstStep.parameters().isEmpty()) m.put("parameters", firstStep.parameters());
        if (firstStep.thought() != null) m.put("thought", firstStep.thought());
        yield Map.copyOf(m);
      }
      default -> {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("type", "LLM_INFERENCE");
        m.put("intent", firstStep.intent().name());
        m.put("target", firstStep.target() != null ? firstStep.target() : "gpt-4o-mini");
        m.put(
            "prompt",
            firstStep
                .parameters()
                .getOrDefault("prompt", context.getOrDefault("prompt", "Hello!")));
        if (firstStep.thought() != null) m.put("thought", firstStep.thought());
        yield Map.copyOf(m);
      }
    };
  }

  // ========================================================================
  // PPAO 事件分发（Observer 模式）
  // ========================================================================

  private void fireOnThought(String reasoning) {
    for (var listener : listeners) {
      try {
        listener.onThought(reasoning);
      } catch (Exception e) {
        logger.warn("AgentEventListener.onThought threw exception", e);
      }
    }
  }

  private void fireOnAction(String target, Map<String, Object> params) {
    for (var listener : listeners) {
      try {
        listener.onAction(target, params);
      } catch (Exception e) {
        logger.warn("AgentEventListener.onAction threw exception", e);
      }
    }
  }

  private void fireOnObserve(String rawData, String summary, long elapsedMs) {
    for (var listener : listeners) {
      try {
        listener.onObserve(rawData, summary, elapsedMs);
      } catch (Exception e) {
        logger.warn("AgentEventListener.onObserve threw exception", e);
      }
    }
  }

  /**
   * Internal holder for a parallel tool call result. Associates the original {@link Call.ToolCall}
   * and parameters with the {@link ToolResult} and a flag indicating whether it was served from
   * cache.
   */
  private record ParallelToolResult(
      Call.ToolCall toolCall,
      java.util.Map<String, Object> params,
      ToolResult result,
      boolean cached) {}

  /** 从 Call.Response 的 raw metadata 中提取 reasoning_content。 */
  private static String extractReasoningFromRaw(Call.Response response) {
    if (response == null || response.metadata() == null) return "";
    Object raw = response.metadata().get("raw");
    if (raw == null) return "";
    String rawStr = raw.toString();
    int idx = rawStr.indexOf("\"reasoning_content\":\"");
    if (idx < 0) return "";
    idx += 21;
    StringBuilder sb = new StringBuilder();
    while (idx < rawStr.length()) {
      char c = rawStr.charAt(idx);
      if (c == '\\' && idx + 1 < rawStr.length()) {
        sb.append(rawStr.charAt(idx + 1));
        idx += 2;
      } else if (c == '"') {
        break;
      } else {
        sb.append(c);
        idx++;
      }
    }
    return sb.toString();
  }

  /** 从 Call.Response 的 raw metadata 中提取 finish_reason。 */
  private static String extractFinishReasonFromRaw(Call.Response response) {
    if (response == null || response.metadata() == null) return "stop";
    Object raw = response.metadata().get("raw");
    if (raw == null) return "stop";
    String rawStr = raw.toString();
    // Match "finish_reason":"value" from choices[0]
    int idx = rawStr.indexOf("\"finish_reason\":\"");
    if (idx < 0) return "stop";
    idx += 17;
    StringBuilder sb = new StringBuilder();
    while (idx < rawStr.length()) {
      char c = rawStr.charAt(idx);
      if (c == '"') break;
      sb.append(c);
      idx++;
    }
    return sb.toString();
  }

  /** 使用 Jackson 解析 LLM Function Calling 返回的 JSON arguments。 支持嵌套对象、字符串转义（\n, \t, \" 等）。 */
  private static java.util.Map<String, Object> parseToolArgsJson(String json) {
    java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
    if (json == null || json.isBlank()) return result;
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
      if (root.isObject()) {
        var it = root.fieldNames();
        while (it.hasNext()) {
          String key = it.next();
          com.fasterxml.jackson.databind.JsonNode val = root.get(key);
          if (val.isTextual()) {
            result.put(key, val.asText());
          } else if (val.isNumber()) {
            result.put(key, val.asText());
          } else if (val.isBoolean()) {
            result.put(key, val.asText());
          } else if (val.isNull()) {
            // Skip null values: Map.copyOf() in Action.<init> rejects them
          } else {
            result.put(key, val.toString());
          }
        }
      }
    } catch (Exception e) {
      logger.warn(
          "[ToolArgsParser] Failed to parse JSON arguments: {} - {}",
          e.getMessage(),
          json.substring(0, Math.min(200, json.length())));
    }
    return result;
  }
}
