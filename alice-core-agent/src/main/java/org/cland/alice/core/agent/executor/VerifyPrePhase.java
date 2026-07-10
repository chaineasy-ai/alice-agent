package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.AgentFacade;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.StepWithContext;
import org.cland.alice.core.agent.result.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PPAO Verify(Pre) 阶段 — 在 Act 前拦截检查战略目标的安全性/策略合规性。
 *
 * <p>委托给 {@link AgentFacade#verifyPre(Action)}，由 Guardrail 模块决策。
 */
public class VerifyPrePhase implements MacroLoopPhase {

  private static final Logger logger = LoggerFactory.getLogger(VerifyPrePhase.class);

  private final AgentFacade agent;

  public VerifyPrePhase(AgentFacade agent) {
    this.agent = agent;
  }

  @Override
  public Future<StepWithContext> execute(AgentContext ctx, StepWithContext input) {
    Action action = input.nextAction();
    if (action == null) {
      return Future.succeededFuture(input);
    }

    logger.debug("[Verify/Pre] checking action={}", action);
    ctx.transitionTo(AgentContext.Phase.VERIFYING_PRE);

    if (agent.verifyPre(action)) {
      logger.debug("[Verify/Pre] approved");
      return Future.succeededFuture(input);
    }

    logger.warn("[Verify/Pre] blocked action={}", action);
    Action revision = Action.revision("Blocked by pre-verify: " + action);
    ctx.appendThought("Pre-verify blocked: " + action.type());
    return Future.succeededFuture(new StepWithContext(ctx, new StepResult.Continue(revision)));
  }

  @Override
  public String phaseName() {
    return "VERIFY_PRE";
  }
}
