package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.AgentFacade;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.Observation;
import org.cland.alice.core.agent.lifecycle.StepWithContext;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.agent.wal.WalSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PPAO Verify(Post) 阶段 — 审计宏观执行结果。
 *
 * <p>委托给 {@link AgentFacade#verifyPost(StepResult)}，由 Guardrail 模块决策。 审计失败时强制 Revision 并写入 WAL 告警。
 */
public class VerifyPostPhase implements MacroLoopPhase {

  private static final Logger logger = LoggerFactory.getLogger(VerifyPostPhase.class);

  private final AgentFacade agent;
  private final WalSession wal;

  public VerifyPostPhase(AgentFacade agent, WalSession wal) {
    this.agent = agent;
    this.wal = wal;
  }

  @Override
  public Future<StepWithContext> execute(AgentContext ctx, StepWithContext input) {
    StepResult result = input.result();

    logger.debug("[Verify/Post] result={}", result);

    if (agent.verifyPost(result)) {
      logger.debug("[Verify/Post] audit passed");
      if (agent.shouldFinish(ctx, result)) {
        ctx.transitionTo(AgentContext.Phase.FINISH);
      }
      return Future.succeededFuture(input);
    }

    logger.warn("[Verify/Post] audit failed, forcing revision");
    ctx.appendThought("Post-verify failed");
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

  @Override
  public String phaseName() {
    return "VERIFY_POST";
  }
}
