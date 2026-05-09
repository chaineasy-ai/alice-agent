package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.Map;
import java.util.Objects;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.AgentCore;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.Observation;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.model.Call;
import org.cland.alice.model.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent PPAO 循环的响应式执行器。
 *
 * <p>基于 Vert.x 的 {@link Future} 实现异步 PPAO 闭环：
 *
 * <pre>
 *   Perceive -> Plan -> Verify(Pre) -> Act -> Observe -> Verify(Post) -> Reflect -> (loop | finish)
 * </pre>
 *
 * <p>对应设计文档中的 AgentExecutor 类，整个 PPAO Loop 被建模为一个 Future 链， 每个阶段接收并传递 {@link AgentContext} 作为状态载体。
 */
public class AgentExecutor {

  private static final Logger logger = LoggerFactory.getLogger(AgentExecutor.class);

  private final Vertx vertx;
  private final AgentCore agentCore;
  private final AgentConfig config;

  public AgentExecutor(Vertx vertx, AgentCore agentCore) {
    this.vertx = Objects.requireNonNull(vertx, "vertx must not be null");
    this.agentCore = Objects.requireNonNull(agentCore, "agentCore must not be null");
    this.config = agentCore.config();
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
  // 核心循环
  // ========================================================================

  /**
   * 启动整个 PPAO 递归循环。
   *
   * <p>1. Perceive 输入，初始化上下文 2. 进入 loopBody 递归 3. 任何 fatal error 被 handleFatalError 捕获
   */
  private Future<AgentContext> executeLoop(String input, AgentContext context) {
    logger.info(
        "Agent {} starting PPAO loop (maxIterations={})",
        agentCore.agentId(),
        config.maxIterations());

    return perceive(input, context)
        .compose(this::loopBody)
        .otherwise(err -> handleFatalError(err, context));
  }

  /**
   * PPAO 递归循环体。
   *
   * <p>每一轮迭代执行：Plan -> Verify(Pre) -> Act -> Observe -> Verify(Post) -> Reflect
   * 然后根据终止条件决定是退出还是递归进入下一轮。
   */
  private Future<AgentContext> loopBody(AgentContext context) {
    // 检查是否应提前终止
    if (agentCore.shouldFinish(context, null)) {
      logger.info(
          "Agent {} PPAO loop finished early (iter={})", agentCore.agentId(), context.iteration());
      return Future.succeededFuture(context);
    }

    return Future.succeededFuture(context)
        .compose(this::plan) // context -> Pair(context, Continue)
        .compose(this::verifyPre) // pair -> pair (or revision)
        .compose(this::act) // pair -> pair (with result)
        .compose(this::observe) // pair -> pair (with observation)
        .compose(this::verifyPost) // pair -> pair (or revision)
        .compose(this::reflect) // pair -> context
        .compose(
            ctx -> {
              // 递归：如果不应终止则继续下一轮
              if (agentCore.shouldFinish(ctx, null)) {
                logger.info(
                    "Agent {} PPAO loop finished (iter={})", agentCore.agentId(), ctx.iteration());
                return Future.succeededFuture(ctx);
              }
              return loopBody(ctx);
            });
  }

  // ========================================================================
  // 内部记录：将 Context 和 StepResult 捆绑传递
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
  // PPAO 各阶段
  // ========================================================================

  /** 1. Perceive: 感知输入，构建上下文 */
  private Future<AgentContext> perceive(String input, AgentContext context) {
    logger.debug("[Perceive] input={}", input);
    context.transitionTo(AgentContext.Phase.PERCEIVING);

    context.put("input", input);
    context.put("prompt", input);
    context.incrementIteration();

    context.transitionTo(AgentContext.Phase.PLANNING);
    return Future.succeededFuture(context);
  }

  /** 2. Plan: 基于上下文规划下一步 Action */
  private Future<StepWithContext> plan(AgentContext context) {
    logger.debug("[Plan] iteration={}", context.iteration());
    context.transitionTo(AgentContext.Phase.PLANNING);

    Action nextAction;
    if (agentCore.planner() != null) {
      // 通过 Planner 的 Map 接口获取规划意图，再转换为 Action
      Map<String, Object> plan = agentCore.planner().proposeNext(context.asMap());
      nextAction = mapToAction(plan);
    } else {
      String prompt = context.containsKey("prompt") ? context.get("prompt").toString() : "Hello!";
      String modelId = config.defaultModelId();
      nextAction = Action.llmInference(modelId, prompt);
    }

    logger.info("[Plan] action={}", nextAction);
    context.appendThought("Plan: " + nextAction.type() + " -> " + nextAction.target());
    return Future.succeededFuture(
        new StepWithContext(context, new StepResult.Continue(nextAction)));
  }

  /** 3. Verify (Pre): 在 Act 前拦截检查 */
  private Future<StepWithContext> verifyPre(StepWithContext stepWithCtx) {
    Action action = stepWithCtx.nextAction();
    AgentContext ctx = stepWithCtx.context();

    if (action == null) {
      return Future.succeededFuture(stepWithCtx);
    }

    logger.debug("[Verify/Pre] action={}", action);
    ctx.transitionTo(AgentContext.Phase.VERIFYING_PRE);

    if (agentCore.verifyPre(action)) {
      logger.debug("[Verify/Pre] approved");
      return Future.succeededFuture(stepWithCtx);
    }

    logger.warn("[Verify/Pre] blocked action={}", action);
    Action revision = Action.revision("Blocked by pre-verify: " + action);
    ctx.appendThought("Pre-verify blocked: " + action.type());
    return Future.succeededFuture(new StepWithContext(ctx, new StepResult.Continue(revision)));
  }

  /** 4. Act: 执行 Action（根据类型分发） */
  private Future<StepWithContext> act(StepWithContext stepWithCtx) {
    Action action = stepWithCtx.nextAction();
    AgentContext ctx = stepWithCtx.context();

    if (action == null) {
      return Future.succeededFuture(stepWithCtx);
    }

    logger.info("[Act] action={}", action);
    ctx.transitionTo(AgentContext.Phase.ACTING);

    return switch (action.type()) {
      case LLM_INFERENCE -> actLlmInference(ctx, action);
      case TOOL_CALL -> actToolCall(ctx, action);
      case FINISH ->
          Future.succeededFuture(
              new StepWithContext(
                  ctx,
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

  /** 执行 LLM 推理 Action */
  private Future<StepWithContext> actLlmInference(AgentContext ctx, Action action) {
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
                  logger.debug("[Act/LLM] response length={}", content.length());
                  return new StepResult.Continue(Action.finish(), Observation.success(content));
                } else {
                  return new StepResult.Failure("LLM call failed: " + call.status());
                }
              } catch (Exception e) {
                logger.error("[Act/LLM] error", e);
                return new StepResult.Failure("LLM call error: " + e.getMessage(), e);
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

  /** 执行工具调用 Action */
  private Future<StepWithContext> actToolCall(AgentContext ctx, Action action) {
    if (agentCore.toolRegistry() == null) {
      logger.warn("[Act/Tool] no ToolRegistry available");
      return Future.succeededFuture(
          new StepWithContext(
              ctx,
              new StepResult.Continue(
                  Action.revision("No ToolRegistry available for tool: " + action.target()),
                  Observation.failure("ToolRegistry not configured"))));
    }

    Promise<StepWithContext> promise = Promise.promise();

    vertx
        .<StepResult>executeBlocking(
            () -> {
              try {
                boolean success =
                    agentCore.toolRegistry().execute(action.target(), action.parameters());
                if (success) {
                  return new StepResult.Continue(
                      Action.llmInference(config.defaultModelId(), "Tool executed, continue"),
                      Observation.success("Tool " + action.target() + " executed"));
                } else {
                  return new StepResult.Continue(
                      Action.revision("Tool execution failed: " + action.target()),
                      Observation.failure("Tool " + action.target() + " failed"));
                }
              } catch (Exception e) {
                return new StepResult.Failure("Tool error: " + e.getMessage(), e);
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

  /** 5. Observe: 收集并持久化观测结果 */
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
    if (agentCore.memory() != null) {
      vertx.executeBlocking(
          () -> {
            agentCore.memory().persist(ctx.sessionId(), result.toString());
            return null;
          });
    }

    ctx.transitionTo(AgentContext.Phase.VERIFYING_POST);
    return Future.succeededFuture(stepWithCtx);
  }

  /** 6. Verify (Post): 审计观测结果 */
  private Future<StepWithContext> verifyPost(StepWithContext stepWithCtx) {
    AgentContext ctx = stepWithCtx.context();
    StepResult result = stepWithCtx.result();

    logger.debug("[Verify/Post] result={}", result);

    if (agentCore.verifyPost(result)) {
      logger.debug("[Verify/Post] audit passed");
      if (agentCore.shouldFinish(ctx, result)) {
        ctx.transitionTo(AgentContext.Phase.FINISH);
      }
      return Future.succeededFuture(stepWithCtx);
    }

    logger.warn("[Verify/Post] audit failed, forcing revision");
    ctx.appendThought("Post-verify failed");
    Action revision = Action.revision("Post-verify audit rejected: " + result);
    return Future.succeededFuture(
        new StepWithContext(
            ctx,
            new StepResult.Continue(revision, Observation.blocked("Post-verify audit failed"))));
  }

  /** 7. Reflect: 注入验证反馈，准备下一轮规划 */
  private Future<AgentContext> reflect(StepWithContext stepWithCtx) {
    AgentContext ctx = stepWithCtx.context();
    logger.debug("[Reflect] phase={}", ctx.currentPhase());

    if (ctx.currentPhase() != AgentContext.Phase.FINISH) {
      ctx.transitionTo(AgentContext.Phase.REFLECTING);
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
    logger.error("Agent {} fatal error in PPAO loop", agentCore.agentId(), error);
    context.put("error", error.getMessage());
    context.put("status", "FATAL_ERROR");
    context.transitionTo(AgentContext.Phase.FINISH);
    return context;
  }

  // ========================================================================
  // 辅助工具
  // ========================================================================

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
}
