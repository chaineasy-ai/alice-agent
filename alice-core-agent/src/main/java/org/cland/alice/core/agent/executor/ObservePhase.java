package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.LinkedHashMap;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.AgentFacade;
import org.cland.alice.core.agent.lifecycle.Observation;
import org.cland.alice.core.agent.lifecycle.StepWithContext;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.agent.wal.WalSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PPAO Observe 阶段 — 收集并持久化 Macro 级的观测结果。
 *
 * <p>优先使用 {@code __action_log}（累积的多步工具结果）作为观测来源， 然后将结果持久化到 Memory 和 WAL。
 */
public class ObservePhase implements MacroLoopPhase {

  private static final Logger logger = LoggerFactory.getLogger(ObservePhase.class);

  private final AgentFacade agent;
  private final WalSession wal;
  private final AgentEventBus eventBus;
  private final Vertx vertx;

  public ObservePhase(AgentFacade agent, WalSession wal, AgentEventBus eventBus, Vertx vertx) {
    this.agent = agent;
    this.wal = wal;
    this.eventBus = eventBus;
    this.vertx = vertx;
  }

  @Override
  public Future<StepWithContext> execute(AgentContext ctx, StepWithContext input) {
    StepResult result = input.result();

    logger.debug("[PPAO] Observe: collecting macro observation");
    ctx.transitionTo(AgentContext.Phase.OBSERVING);

    // 提取 Observation — 优先使用 __action_log
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

      if (eventBus != null) {
        eventBus.fireOnObserve(
            "[System] " + toolCount + " tool calls executed",
            actionLog.length() + " chars from " + toolCount + " tool results",
            0L);
      }
    } else {
      Observation obs = input.observation();
      if (obs != null) {
        ctx.appendThought("Observed: " + obs.summary());
        ctx.put("lastObservation", obs);
      }
    }

    // 持久化到 Memory
    persistToMemory(ctx, result);

    // WAL: Macro ReAct 循环结束
    writeObserveWal(ctx);

    ctx.transitionTo(AgentContext.Phase.VERIFYING_POST);
    return Future.succeededFuture(input);
  }

  private void persistToMemory(AgentContext ctx, StepResult result) {
    if (agent.memory() != null) {
      vertx.executeBlocking(
          () -> {
            agent.memory().persist(ctx.sessionId(), result.toString());
            return null;
          });
    }
  }

  private void writeObserveWal(AgentContext ctx) {
    if (wal == null) return;
    String stateNode = ctx.currentPhase().name();
    var vars = new LinkedHashMap<>(ctx.asMap());
    vars.put("iteration", ctx.iteration());
    vars.put("phase", stateNode);
    vars.put("messageCount", wal.messageCount(ctx.sessionId()));
    wal.checkpointOnReActEnd(ctx.sessionId(), stateNode, vars, "");
  }

  @Override
  public String phaseName() {
    return "OBSERVE";
  }
}
