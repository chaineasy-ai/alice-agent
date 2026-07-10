package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.StepWithContext;
import org.cland.alice.core.agent.result.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PPAO Act 阶段 — 执行战略目标，内部嵌入战术级 Micro-ReAct 循环。
 *
 * <p>委托给 {@link MicroReActEngine} 执行 Reason → Dispatch → Observe 战术循环。 LLM_INFERENCE 和 TOOL_CALL
 * 类型的 Action 进入 Micro-ReAct； FINISH/REVISION/OBSERVE/WAIT 等类型就地处理。
 */
public class ActPhase implements MacroLoopPhase {

  private static final Logger logger = LoggerFactory.getLogger(ActPhase.class);

  private final MicroReActEngine microEngine;
  private final Vertx vertx;

  public ActPhase(MicroReActEngine microEngine, Vertx vertx) {
    this.microEngine = microEngine;
    this.vertx = vertx;
  }

  @Override
  public Future<StepWithContext> execute(AgentContext ctx, StepWithContext input) {
    Action action = input.nextAction();
    if (action == null) {
      return Future.succeededFuture(input);
    }

    logger.info("[PPAO] Act: initial action={}", action);
    ctx.transitionTo(AgentContext.Phase.ACTING);

    return switch (action.type()) {
      case LLM_INFERENCE -> microEngine.execute(ctx, action);
      case TOOL_CALL -> microEngine.execute(ctx, action);
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
        yield Future.succeededFuture(new StepWithContext(ctx, input.result()));
      }
      case OBSERVE ->
          Future.succeededFuture(
              new StepWithContext(ctx, new StepResult.Continue(Action.finish())));
      case WAIT -> {
        Promise<StepWithContext> promise = Promise.promise();
        vertx.setTimer(1000, id -> promise.complete(new StepWithContext(ctx, input.result())));
        yield promise.future();
      }
    };
  }

  @Override
  public String phaseName() {
    return "ACT";
  }
}
