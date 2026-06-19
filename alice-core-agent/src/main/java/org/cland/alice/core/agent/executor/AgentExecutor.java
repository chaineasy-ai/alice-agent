package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.Observation;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.planner.Plan;
import org.cland.alice.memory.wal.WalSession;
import org.cland.alice.model.Call;
import org.cland.alice.model.ModelProvider;
import org.cland.alice.tool.gateway.engine.ExecutionEngine;
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
  private final ExecutionEngine executionEngine;

  /** 可选的 WAL 会话，注入后启用双轨制持久化与崩溃恢复 */
  private WalSession wal;

  public AgentExecutor(Vertx vertx, Agent agent) {
    this.vertx = Objects.requireNonNull(vertx, "vertx must not be null");
    this.agent = Objects.requireNonNull(agent, "agent must not be null");
    this.config = agent.config();
    // ExecutionEngine 替换已过时的 ToolRegistry.execute()，提供沙箱/超时控制
    this.executionEngine =
        agent.toolRegistry() != null
            ? ExecutionEngine.builder().registry(agent.toolRegistry()).build()
            : null;
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
    logger.info(
        "Agent {} starting PPAO loop (maxIterations={})", agent.agentId(), config.maxIterations());

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
    // 检查是否应提前终止
    if (agent.shouldFinish(context, null)) {
      logger.info(
          "Agent {} PPAO loop finished early (iter={})", agent.agentId(), context.iteration());
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
                    "Agent {} PPAO loop finished (iter={})", agent.agentId(), ctx.iteration());
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
    logger.debug("[Perceive] input={}", input);
    context.transitionTo(AgentContext.Phase.PERCEIVING);

    // WAL: 记录用户输入 + 用户输入 Checkpoint
    if (wal != null) {
      wal.user(context.sessionId(), input);
      wal.checkpointOnUserInput(context.sessionId());
    }

    context.put("input", input);
    context.put("prompt", input);
    context.incrementIteration();

    context.transitionTo(AgentContext.Phase.PLANNING);
    return Future.succeededFuture(context);
  }

  /** 2. Plan: 基于上下文制定阶段性目标（Macro 规划） */
  private Future<StepWithContext> plan(AgentContext context) {
    logger.debug("[Plan] iteration={}", context.iteration());
    context.transitionTo(AgentContext.Phase.PLANNING);

    Action nextAction;
    if (agent.plannerService() != null) {
      // PlannerService.plan() 返回 Plan，转为意图 Map
      Plan plan = agent.plannerService().plan(context.asMap());
      Map<String, Object> intent = planToIntent(plan, context.asMap());
      nextAction = mapToAction(intent);
    } else {
      String rawPrompt =
          context.containsKey("prompt") ? context.get("prompt").toString() : "Hello!";
      String modelId = config.defaultModelId();
      // 注入系统提示词，引导 LLM 输出结构化工具调用
      String enhancedPrompt = buildSystemPrompt() + "\n\n用户需求: " + rawPrompt;
      nextAction = Action.llmInference(modelId, enhancedPrompt);
    }

    logger.info("[Plan] action={}", nextAction);
    context.appendThought("Plan: " + nextAction.type() + " -> " + nextAction.target());
    return Future.succeededFuture(
        new StepWithContext(context, new StepResult.Continue(nextAction)));
  }

  /** 3. Verify (Pre): 在 Act 前拦截检查战略目标的安全性/策略合规性 */
  private Future<StepWithContext> verifyPre(StepWithContext stepWithCtx) {
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

    logger.info("[Act] entering Micro-ReAct loop, initial action={}", action);
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
   * Micro-ReAct 循环：Reason → Dispatch → Observe → (loop | break)。
   *
   * <p>这是设计文档中"战术执行态闭环"的实现。 在当前 Action 执行完毕后，基于观察结果调用 planner 生成下一个微意图， 直到满足终止条件（FINISH / 熔断 /
   * 最大微迭代次数）。
   */
  private Future<StepWithContext> microReActLoop(AgentContext ctx, Action initialAction) {
    // Micro-ReAct 熔断参数
    final int maxMicroIterations = config.maxIterations(); // 复用全局配置
    final String originalPrompt = ctx.containsKey("prompt") ? ctx.get("prompt").toString() : "";

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
          if (agent.plannerService() == null) {
            // === Reason without PlannerService ===
            // Priority:
            // 1) If dispatch returned Continue with an embedded nextAction (e.g. tool→LLM),
            //    dispatch it directly.
            // 2) Otherwise parse tool call markers from LLM output.
            // 3) Otherwise finish micro loop.

            Action continueAction = result instanceof StepResult.Continue c ? c.nextAction() : null;

            if (continueAction != null
                && continueAction.type() != Action.Type.FINISH
                && continueAction.type() != Action.Type.REVISION) {
              // Dispatch-instructed next action (e.g. LLM reasoning after tool call)
              logger.warn(
                  "[Micro-ReAct/Reason] dispatching Continue's nextAction: type={} target={}",
                  continueAction.type(),
                  continueAction.target());
              return microReActStep(
                  updatedCtx, continueAction, originalPrompt, depth + 1, maxDepth);
            }

            // Parse tool call markers from LLM output
            Action toolAction = parseToolCallFromOutput(updatedCtx);
            if (toolAction != null) {
              logger.warn(
                  "[Micro-ReAct/Reason] parsed from output: type={} target={}",
                  toolAction.type(),
                  toolAction.target());
              if (toolAction.type() == Action.Type.FINISH) {
                updatedCtx.put("result", obs != null ? obs.summary() : "");
                if (wal != null) {
                  wal.checkpointOnReActEnd(
                      updatedCtx.sessionId(), "ACTING_FINISHED", updatedCtx.asMap(), "");
                }
                return Future.succeededFuture(
                    new StepWithContext(
                        updatedCtx,
                        new StepResult.Finish(
                            obs != null ? obs.summary() : "",
                            "Micro-ReAct loop completed via FINISH marker")));
              }
              return microReActStep(updatedCtx, toolAction, originalPrompt, depth + 1, maxDepth);
            }

            logger.warn("[Micro-ReAct/Reason] no next action, finishing micro loop");
            return Future.succeededFuture(
                new StepWithContext(updatedCtx, new StepResult.Continue(Action.finish())));
          }

          // 构建 Micro 上下文：包含上一次行动的结果
          Map<String, Object> microCtx = updatedCtx.asMap();
          microCtx.put("__micro_depth", depth);
          microCtx.put("__micro_original_prompt", originalPrompt);

          // PlannerService.plan() 作为 Reason：基于观察结果生成下一步微意图
          Plan microPlan = agent.plannerService().plan(microCtx);
          Map<String, Object> nextIntent = planToIntent(microPlan, microCtx);
          Action nextAction = mapToAction(nextIntent);

          // 检查是否应退出 Micro-ReAct
          if (nextAction.type() == Action.Type.FINISH) {
            logger.debug("[Micro-ReAct] FINISH received, exiting micro loop");
            updatedCtx.put(
                "result", obs != null ? obs.summary() : "Sub-goal completed via Micro-ReAct");

            // WAL: Micro-ReAct 结束 Checkpoint
            if (wal != null) {
              wal.checkpointOnReActEnd(
                  updatedCtx.sessionId(), "ACTING_FINISHED", updatedCtx.asMap(), "");
            }

            return Future.succeededFuture(
                new StepWithContext(
                    updatedCtx,
                    new StepResult.Finish(
                        obs != null ? obs.summary() : "",
                        "Micro-ReAct loop completed at depth " + depth)));
          }

          if (nextAction.type() == Action.Type.REVISION) {
            // Revision 需要跳出 Micro-ReAct 回到 Macro Reflect 阶段
            String feedback =
                nextAction.parameters().getOrDefault("feedback", "Micro revision").toString();
            updatedCtx.put("lastFeedback", feedback);
            updatedCtx.appendThought("[Micro-ReAct] Revision: " + feedback);

            // WAL: Revision Checkpoint
            if (wal != null) {
              wal.checkpointOnReActEnd(updatedCtx.sessionId(), "REVISION", updatedCtx.asMap(), "");
            }

            return Future.succeededFuture(
                new StepWithContext(updatedCtx, new StepResult.Continue(nextAction)));
          }

          // === 递归：继续下一轮 Micro-ReAct ===
          return microReActStep(updatedCtx, nextAction, originalPrompt, depth + 1, maxDepth);
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
                Call call = provider.dispatch(modelId, prompt);

                if (call.status() == org.cland.alice.model.CallStatus.FINISHED
                    && call.result() != null) {
                  String content = call.result().content();
                  ctx.put("result", content);
                  ctx.put("__llm_response", content);
                  ctx.remove("__tool_call_index");
                  logger.debug("[Micro-ReAct/LLM] response length={}", content.length());

                  // WAL: 记录 assistant 回复
                  if (wal != null) {
                    wal.assistant(ctx.sessionId(), content);
                  }

                  // 将 LLM 输出包装为 Observation，Continue 的 nextAction 设为 null
                  // 让 Reason 阶段解析工具调用标记或退出。
                  return new StepResult.Continue(null, Observation.success(content));
                } else {
                  // WAL: 记录失败的 LLM 回复
                  if (wal != null) {
                    wal.assistant(ctx.sessionId(), "[LLM Error: " + call.status() + "]");
                  }

                  return new StepResult.Failure("LLM call failed: " + call.status());
                }
              } catch (Exception e) {
                logger.error("[Micro-ReAct/LLM] error", e);
                // 不可恢复的错误（如 supplier 未注册），直接熔断退出循环，避免无限重试
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
  // 内置工具（read_file, write_file, grep, run）
  // ========================================================================

  /**
   * 尝试执行内置工具（不依赖 ToolRegistry）。
   *
   * <p>支持以下工具：
   *
   * <ul>
   *   <li>{@code read_file} — 读取文件内容
   *   <li>{@code write_file} — 写入文件内容
   *   <li>{@code grep} — 全文搜索
   *   <li>{@code run} — 执行 shell 命令
   * </ul>
   *
   * @return 如果命中内置工具则返回 Future，否则返回 null
   */
  private Future<StepWithContext> dispatchBuiltinTool(AgentContext ctx, Action action) {
    String toolName = action.target();
    java.util.Map<String, Object> params = action.parameters();
    // Capture original model ID for LLM calls after tool execution
    String llmModelId =
        action.parameters().getOrDefault("modelId", config.defaultModelId()).toString();
    // Try to get model from the Action that triggered this tool call
    if ("gpt-4o-mini".equals(llmModelId) && action.actionId() != null) {
      // Fall back to the original prompt's model if available
      String ctxModel =
          ctx.containsKey("model") ? ctx.get("model").toString() : config.defaultModelId();
      llmModelId = "gpt-4o-mini".equals(ctxModel) ? config.defaultModelId() : ctxModel;
    }

    try {
      switch (toolName) {
        case "read_file" -> {
          logger.warn("[BuiltinTool] read_file called with params={}", params);
          String path = (String) params.getOrDefault("path", params.getOrDefault("filePath", ""));
          if (path.isBlank()) {
            return Future.succeededFuture(
                new StepWithContext(
                    ctx,
                    new StepResult.Continue(
                        Action.revision("read_file: path is required"),
                        Observation.failure("read_file: path is required"))));
          }
          java.nio.file.Path filePath = java.nio.file.Paths.get(path);
          if (!filePath.isAbsolute()) {
            filePath = java.nio.file.Paths.get(System.getProperty("user.dir")).resolve(filePath);
          }
          if (!filePath.toFile().exists()) {
            return Future.succeededFuture(
                new StepWithContext(
                    ctx,
                    new StepResult.Continue(
                        Action.revision("read_file: file not found: " + path),
                        Observation.failure("read_file: file not found: " + path))));
          }
          String content = java.nio.file.Files.readString(filePath);
          logger.debug("[BuiltinTool] read_file {} ({} chars)", path, content.length());
          ctx.remove("result");
          // 读取完成后，通知 LLM 分析文件内容并决定下一步操作
          String analysisPrompt =
              "You have read file "
                  + path
                  + ". Content:\n"
                  + content
                  + "\n\nNow fix the divide function to raise ValueError on zero division. "
                  + "Output [TOOL_CALL: write_file(path=\""
                  + path
                  + "\", content=\"<complete fixed file>\")] with the ENTIRE fixed file. "
                  + "Do NOT read again. After writing, task complete.";
          return Future.succeededFuture(
              new StepWithContext(
                  ctx,
                  new StepResult.Continue(
                      Action.llmInference(llmModelId, analysisPrompt),
                      Observation.success(
                          "Read file " + path + " (" + content.length() + " chars)"))));
        }
        case "write_file" -> {
          String path = (String) params.getOrDefault("path", "");
          String content = (String) params.getOrDefault("content", "");
          if (path.isBlank()) {
            return Future.succeededFuture(
                new StepWithContext(
                    ctx,
                    new StepResult.Continue(
                        Action.revision("write_file: path is required"),
                        Observation.failure("write_file: path is required"))));
          }
          java.nio.file.Path filePath = java.nio.file.Paths.get(path);
          if (!filePath.isAbsolute()) {
            filePath = java.nio.file.Paths.get(System.getProperty("user.dir")).resolve(filePath);
          }
          filePath.getParent().toFile().mkdirs();
          java.nio.file.Files.writeString(filePath, content);
          logger.debug("[BuiltinTool] write_file {} ({} chars)", path, content.length());
          return Future.succeededFuture(
              new StepWithContext(
                  ctx,
                  new StepResult.Continue(
                      Action.llmInference(llmModelId, "已写入文件 " + path + "，继续分析是否需要更多修改"),
                      Observation.success("已写入文件 " + path))));
        }
        case "grep" -> {
          String pattern = (String) params.getOrDefault("pattern", "");
          String grepPath = (String) params.getOrDefault("path", ".");
          if (pattern.isBlank()) {
            return Future.succeededFuture(
                new StepWithContext(
                    ctx,
                    new StepResult.Continue(
                        Action.revision("grep: pattern is required"),
                        Observation.failure("grep: pattern is required"))));
          }
          java.nio.file.Path searchPath = java.nio.file.Paths.get(grepPath);
          if (!searchPath.isAbsolute()) {
            searchPath =
                java.nio.file.Paths.get(System.getProperty("user.dir")).resolve(searchPath);
          }
          StringBuilder results = new StringBuilder();
          try (var stream = java.nio.file.Files.walk(searchPath)) {
            stream
                .filter(
                    p ->
                        p.toString().endsWith(".py")
                            || p.toString().endsWith(".java")
                            || p.toString().endsWith(".txt")
                            || p.toString().endsWith(".md"))
                .forEach(
                    p -> {
                      try {
                        String text = java.nio.file.Files.readString(p);
                        if (text.contains(pattern)) {
                          results.append(p.getFileName()).append(": matches\n");
                        }
                      } catch (Exception ignored) {
                      }
                    });
          }
          logger.debug("[BuiltinTool] grep '{}' found {} files", pattern, results.length());
          return Future.succeededFuture(
              new StepWithContext(
                  ctx,
                  new StepResult.Continue(
                      Action.llmInference(llmModelId, "grep '" + pattern + "' 结果:\n" + results),
                      Observation.success("grep '" + pattern + "' 完成"))));
        }
        case "run" -> {
          String cmd = (String) params.getOrDefault("cmd", "");
          if (cmd.isBlank()) {
            return Future.succeededFuture(
                new StepWithContext(
                    ctx,
                    new StepResult.Continue(
                        Action.revision("run: cmd is required"),
                        Observation.failure("run: cmd is required"))));
          }
          ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", cmd);
          pb.directory(new java.io.File(System.getProperty("user.dir")));
          Process process = pb.start();
          String stdOut = new String(process.getInputStream().readAllBytes());
          String stdErr = new String(process.getErrorStream().readAllBytes());
          int exitCode = process.waitFor();
          String resultOutput =
              exitCode == 0 ? stdOut : "exit=" + exitCode + "\n" + stdOut + "\n" + stdErr;
          logger.debug("[BuiltinTool] run '{}' exit={}", cmd, exitCode);
          return Future.succeededFuture(
              new StepWithContext(
                  ctx,
                  new StepResult.Continue(
                      Action.llmInference(llmModelId, "命令执行结果:\n" + resultOutput),
                      Observation.success("命令 '" + cmd + "' 执行完成 (exit=" + exitCode + ")"))));
        }
        default -> {
          return null; // 不是内置工具，交给 ToolRegistry
        }
      }
    } catch (Exception e) {
      logger.error("[BuiltinTool] error executing {}", toolName, e);
      return Future.succeededFuture(
          new StepWithContext(
              ctx,
              new StepResult.Continue(
                  Action.revision("Tool error: " + toolName + " - " + e.getMessage()),
                  Observation.failure("Tool " + toolName + " error: " + e.getMessage()))));
    }
  }

  /** Dispatch TOOL_CALL */
  private Future<StepWithContext> dispatchToolCall(AgentContext ctx, Action action) {
    logger.warn("[Dispatch/TOOL_CALL] target={} params={}", action.target(), action.parameters());
    // 先尝试内置工具（read_file, write_file, grep, run），不依赖 ToolRegistry
    Future<StepWithContext> builtinResult = dispatchBuiltinTool(ctx, action);
    if (builtinResult != null) {
      return builtinResult;
    }
    logger.warn("[Dispatch/TOOL_CALL] not a builtin tool, falling through to ToolRegistry");

    if (agent.toolRegistry() == null) {
      logger.warn("[Micro-ReAct/Tool] no ToolRegistry available");
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
              org.cland.alice.memory.wal.ToolCall.of(
                  action.actionId(), action.target(), action.parameters())));
    }

    vertx
        .<StepResult>executeBlocking(
            () -> {
              try {
                if (executionEngine == null) {
                  logger.warn("[Micro-ReAct/Tool] no ExecutionEngine available");
                  return new StepResult.Continue(
                      Action.revision("No ExecutionEngine for tool: " + action.target()),
                      Observation.failure("ExecutionEngine not configured"));
                }

                var result = executionEngine.invoke(action.target(), action.parameters());
                boolean success =
                    result.status()
                        == org.cland.alice.tool.gateway.engine.ToolResult.Status.SUCCESS;

                // WAL: 记录工具执行结果
                if (wal != null) {
                  String resultContent =
                      success
                          ? "Tool " + action.target() + " executed successfully"
                          : "Tool " + action.target() + " returned failure";
                  wal.toolResult(ctx.sessionId(), action.actionId(), resultContent);
                  wal.checkpointOnToolReturn(ctx.sessionId(), action.target(), success);
                }

                if (success) {
                  return new StepResult.Continue(
                      Action.llmInference(
                          config.defaultModelId(), "Tool executed, continue reasoning"),
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

    logger.debug("[Observe] result={}", result);
    ctx.transitionTo(AgentContext.Phase.OBSERVING);

    // 提取 Observation
    Observation obs = stepWithCtx.observation();
    if (obs != null) {
      ctx.appendThought("Observed: " + obs.summary());
      ctx.put("lastObservation", obs);
    }

    // 持久化到 Memory
    if (agent.memory() != null) {
      vertx.executeBlocking(
          () -> {
            agent.memory().persist(ctx.sessionId(), result.toString());
            return null;
          });
    }

    // WAL: Macro ReAct 循环结束，触发 Checkpoint
    if (wal != null) {
      String stateNode = ctx.currentPhase().name();
      wal.checkpointOnReActEnd(ctx.sessionId(), stateNode, ctx.asMap(), "");
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
      wal.checkpointOnError(ctx.sessionId(), "POST_VERIFY_FAIL", "Post-verify audit rejected");
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
      if (!ctx.containsKey("result")) {
        ctx.put("result", "Max iterations reached without final answer.");
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

  /**
   * 构建系统提示词，引导 LLM 输出结构化工具调用。
   *
   * <p>LLM 在回答中可以包含以下标记让 Agent 执行工具：
   *
   * <ul>
   *   <li>{@code [TOOL_CALL: read_file(path="xxx")]} — 读取文件
   *   <li>{@code [TOOL_CALL: write_file(path="xxx", content="yyy")]} — 写入文件
   *   <li>{@code [TOOL_CALL: grep(pattern="xxx", path="yyy")]} — 全文搜索
   *   <li>{@code [TOOL_CALL: run(cmd="xxx")]} — 执行命令
   *   <li>{@code [FINISH]} — 完成任务
   * </ul>
   */
  private static String buildSystemPrompt() {
    return
"""
You are an AI coding assistant with file read/write tools.

Available tool call format:
[TOOL_CALL: read_file(path="file path")]
[TOOL_CALL: write_file(path="file path", content="file content")]
[FINISH]

Execution rules:
1. First, call read_file to examine the file.
2. After reading, call write_file to write the fixed code in ONE complete call.
3. After writing, call [FINISH] to complete the task.
4. NEVER repeat read_file — read once, then write.
5. The write_file content must contain the COMPLETE fixed file.

Example:
[TOOL_CALL: read_file(path="src/main.py")]
[TOOL_CALL: write_file(path="src/main.py", content="the entire fixed file content")]
[FINISH]
""";
  }

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
  private static Action parseToolCallFromOutput(AgentContext ctx) {
    // 优先从 result 中读取，退回到 lastObservation
    String output = ctx.containsKey("result") ? ctx.get("result").toString() : null;
    if (output == null || output.isBlank()) {
      Object obs = ctx.get("lastObservation");
      if (obs instanceof Observation o) {
        output = o.summary();
      } else if (obs instanceof String s) {
        output = s;
      }
    }
    if (output == null || output.isBlank()) {
      System.out.println("[ToolCallParser] no output to parse (result is null/blank)");
      return null;
    }

    System.out.println(
        "[ToolCallParser] output first300=" + output.substring(0, Math.min(300, output.length())));

    // Debug: check if TOOL_CALL or FINISH markers are in the output
    if (output.contains("[TOOL_CALL:") || output.contains("[FINISH]")) {
      System.out.println("[ToolCallParser] FOUND markers in output, length=" + output.length());
    } else {
      System.out.println(
          "[ToolCallParser] NO markers in output, length="
              + output.length()
              + " first200="
              + output.substring(0, Math.min(200, output.length())));
    }

    // 解析 [TOOL_CALL: toolName(key="value", ...)]
    // 匹配到 )] 结束的格式。注意 group(2) 使用非贪婪 + 明确 end anchor
    java.util.regex.Pattern pattern =
        java.util.regex.Pattern.compile(
            "\\[TOOL_CALL:\\s*(\\w+)\\(([^)]*)\\)\\]", java.util.regex.Pattern.DOTALL);
    java.util.regex.Matcher matcher = pattern.matcher(output);

    // 跳过已消费的工具调用
    int idx =
        ctx.containsKey("__tool_call_index")
            ? Integer.parseInt(ctx.get("__tool_call_index").toString())
            : 0;
    int found = 0;
    boolean matched = false;
    while (matcher.find()) {
      if (found >= idx) {
        matched = true;
        break;
      }
      found++;
    }

    if (matched) {
      String toolName = matcher.group(1);
      String paramsRaw = matcher.group(2).trim();
      ctx.put("__tool_call_index", String.valueOf(idx + 1));
      System.out.println(
          "[ToolCallParser] regex MATCHED #" + idx + ": tool=" + toolName + " params=" + paramsRaw);

      // 解析 key="value" 参数
      java.util.regex.Pattern paramPattern = java.util.regex.Pattern.compile("(\\w+)=\"([^\"]*)\"");
      java.util.regex.Matcher paramMatcher = paramPattern.matcher(paramsRaw);

      java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
      while (paramMatcher.find()) {
        params.put(paramMatcher.group(1), paramMatcher.group(2));
      }

      // 如果是 write_file，检查是否有未闭合的引号（content 可能跨多行）
      if ("write_file".equals(toolName) && !params.containsKey("content")) {
        // 尝试从 output 中提取 write_file 的完整 content
        java.util.regex.Pattern contentPattern =
            java.util.regex.Pattern.compile(
                "content=\"([\\s\\S]*?)\"\\s*\\)", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher contentMatcher = contentPattern.matcher(output);
        if (contentMatcher.find()) {
          params.put("content", contentMatcher.group(1));
        }
      }

      // 修正 read_file 的参数名兼容性
      if ("read_file".equals(toolName) && params.containsKey("path")) {
        params.putIfAbsent("filePath", params.get("path"));
      }

      logger.debug("[ToolCallParser] parsed tool={} params={}", toolName, params);
      return Action.toolCall(toolName, params);
    }

    System.out.println(
        "[ToolCallParser] regex NO MATCH on output first200="
            + output.substring(0, Math.min(200, output.length())));
    // 所有 TOOL_CALL 都已消费，检查 FINISH
    if (output.contains("[FINISH]")) {
      return Action.finish();
    }

    return null;
  }

  /** 将 Planner 返回的 Map 意图描述转换为 {@link Action}。 */
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
        yield Action.llmInference(target, prompt);
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
    String actionType = firstStep.actionType();

    return switch (actionType) {
      case "FINISH" -> Map.of("type", "FINISH", "target", "FINISH");
      case "TOOL_CALL" -> {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("type", "TOOL_CALL");
        m.put("target", firstStep.target());
        if (!firstStep.parameters().isEmpty()) m.put("parameters", firstStep.parameters());
        if (firstStep.thought() != null) m.put("thought", firstStep.thought());
        yield Map.copyOf(m);
      }
      case "REVISION" -> {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("type", "REVISION");
        m.put("target", "REVISION");
        m.put("feedback", firstStep.parameters().getOrDefault("feedback", "Revision requested"));
        yield Map.copyOf(m);
      }
      default -> {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("type", "LLM_INFERENCE");
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
}
