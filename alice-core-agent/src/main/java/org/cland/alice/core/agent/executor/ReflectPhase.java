package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import java.util.LinkedHashMap;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.AgentFacade;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.StepWithContext;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.agent.wal.Checkpoint;
import org.cland.alice.core.agent.wal.WalSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PPAO Reflect 阶段 — 注入验证反馈，准备下一轮 Macro 规划。
 *
 * <p>检查 FINISH action、Revision 反馈和最大迭代限制，决定下一轮是继续还是终止。
 */
public class ReflectPhase implements MacroLoopPhase {

  private static final Logger logger = LoggerFactory.getLogger(ReflectPhase.class);

  private final AgentFacade agent;
  private final WalSession wal;

  public ReflectPhase(AgentFacade agent, WalSession wal) {
    this.agent = agent;
    this.wal = wal;
  }

  @Override
  public Future<StepWithContext> execute(AgentContext ctx, StepWithContext input) {
    logger.debug("[Reflect] phase={}", ctx.currentPhase());

    if (ctx.currentPhase() == AgentContext.Phase.FINISH) {
      return Future.succeededFuture(input);
    }

    ctx.transitionTo(AgentContext.Phase.REFLECTING);

    // 检查 Continue with FINISH action
    if (input.result() instanceof StepResult.Continue cont) {
      Action nextAction = cont.nextAction();
      if (nextAction != null && nextAction.type() == Action.Type.FINISH) {
        ctx.transitionTo(AgentContext.Phase.FINISH);
        if (!ctx.containsKey("result") || ctx.get("result").toString().isBlank()) {
          ctx.put("result", "Agent completed without explicit result.");
        }
        writeFinishWal(ctx, "finish_action");
        return Future.succeededFuture(input);
      }
    }

    // 提取 Revision 反馈
    if (input.result() instanceof StepResult.Continue cont) {
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
      writeFinishWal(ctx, "max_iterations");
    }

    return Future.succeededFuture(input);
  }

  private void writeFinishWal(AgentContext ctx, String reason) {
    if (wal == null) return;
    var vars = new LinkedHashMap<>(ctx.asMap());
    vars.put("iteration", ctx.iteration());
    vars.put("phase", "FINISH");
    vars.put("reason", reason);
    wal.checkpointOnReActEnd(ctx.sessionId(), Checkpoint.NODE_FINISHED, vars, "");
  }

  @Override
  public String phaseName() {
    return "REFLECT";
  }
}
