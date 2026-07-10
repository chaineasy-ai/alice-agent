package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import java.util.Map;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.AgentFacade;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.PlanToIntentConverter;
import org.cland.alice.core.agent.lifecycle.StepWithContext;
import org.cland.alice.core.agent.prompt.PromptManager;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.planner.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PPAO Plan 阶段 — 基于上下文制定阶段性目标。
 *
 * <p>使用 {@link AgentFacade#plannerService()} 生成宏规划，或回退到 Core Loop Prompt 直接调用 LLM。
 */
public class PlanPhase implements MacroLoopPhase {

  private static final Logger logger = LoggerFactory.getLogger(PlanPhase.class);

  private final AgentFacade agent;

  public PlanPhase(AgentFacade agent) {
    this.agent = agent;
  }

  @Override
  public Future<StepWithContext> execute(AgentContext context, StepWithContext input) {
    logger.info("[PPAO] Plan: iteration={}", context.iteration());
    context.transitionTo(AgentContext.Phase.PLANNING);

    Action nextAction;
    if (agent.plannerService() != null) {
      Plan plan = agent.plannerService().plan(context.asMap());
      Map<String, Object> intent = PlanToIntentConverter.planToIntent(plan, context.asMap());
      nextAction = PlanToIntentConverter.mapToAction(intent);
    } else {
      String rawPrompt =
          context.containsKey("prompt") ? context.get("prompt").toString() : "Hello!";
      String modelId = agent.config().defaultModelId();
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

  @Override
  public String phaseName() {
    return "PLAN";
  }
}
