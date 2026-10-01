package org.cland.alice.core.agent.guardrail;

import org.cland.alice.core.agent.kernel.graph.GatePolicy;
import org.cland.alice.core.agent.kernel.graph.GateResult;
import org.cland.alice.core.agent.kernel.graph.GoalRef;
import org.cland.alice.core.agent.kernel.graph.GraphNode;
import org.cland.alice.core.agent.kernel.graph.Ledger;
import org.cland.alice.core.agent.kernel.graph.StandardSkeleton;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.guardrail.Verificator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * verifyPost(g) 门策略适配器 —— 将 legacy {@link Verificator#audit(Object)} 的 post-verify 语义 桥接到图内核 {@link
 * GatePolicy}（D8：模型判据提交 + 规则后检 + 迭代预算兜底，<b>维持原语义</b>）。
 *
 * <p><b>等价关系</b>：图内核 {@code N_VP} gate 判定 == legacy {@code Agent.verifyPost(StepResult.Finish)} ——
 * 把账本产物 {@code answer} 还原为 {@link StepResult.Finish}，交由注入的 {@link Verificator#audit(Object)} 审计；通过
 * → {@link GateResult#passed()}（pass 边 → ARBITRATE），否则 → {@link GateResult#guard(String)
 * guard(PORT_VP_REJECT)}（reject 边 → rev-budget 修订回路，迭代预算兜底即骨架既有注解 gate）。
 *
 * <p><b>目标上下文（P7）</b>：goal 由 {@link Ledger} 游标携带（{@link Ledger#currentGoal()}），记入判定日志；本类只做
 * 「语义搬运」，不引入结构化判据、不改 guardrail 规则内容（D8）。
 *
 * <p><b>装配门（与 legacy 同构）</b>：由 {@code Agent.graphKernel()} 在 {@code config.postVerifyEnabled() &&
 * guardrail != null} 时注入；{@code verificator == null} 时恒放行（等同 legacy {@code guardrail == null}）。
 *
 * <p><b>观测回退</b>：账本无 {@code answer} 产物时（如效果预算熔断直接退出 goal），以解释器 {@code lastObservation} 为审计对象 —— 等价
 * legacy {@code Continue(Observation)} 的后检对象。
 */
public final class VerifyPostGatePolicy implements GatePolicy {

  private static final Logger logger = LoggerFactory.getLogger(VerifyPostGatePolicy.class);

  /** 账本产物键：TAO 收敛时写入的候选最终回答（见 StandardSkeleton N_THOUGHT）。 */
  private static final String ARTIFACT_ANSWER = "answer";

  private final Verificator verificator;

  /**
   * @param verificator legacy post-verify 验证器（通常为 {@link GuardrailVerificatorAdapter}）；可为 null（恒放行）
   */
  public VerifyPostGatePolicy(Verificator verificator) {
    this.verificator = verificator;
  }

  @Override
  public GateResult verify(GraphNode gate, Ledger ledger, String lastObservation) {
    if (verificator == null) {
      return GateResult.passed();
    }
    GoalRef goal = ledger != null ? ledger.currentGoal() : null;
    String answer = resolveAnswer(ledger, lastObservation);
    boolean passed = verificator.audit(new StepResult.Finish(answer));
    if (logger.isDebugEnabled()) {
      logger.debug(
          "[VerifyPostGatePolicy] verifyPost(g={}) {} ({} chars)",
          goal != null ? goal.id() : "<none>",
          passed ? "PASS" : "BLOCK(" + StandardSkeleton.PORT_VP_REJECT + ")",
          answer.length());
    }
    return passed ? GateResult.passed() : GateResult.guard(StandardSkeleton.PORT_VP_REJECT);
  }

  /** 还原 legacy vp 所见的 answer：优先账本产物，回退解释器最后观测。 */
  private static String resolveAnswer(Ledger ledger, String lastObservation) {
    if (ledger != null) {
      Object answer = ledger.artifacts().get(ARTIFACT_ANSWER);
      if (answer != null) {
        return String.valueOf(answer);
      }
    }
    return lastObservation != null ? lastObservation : "";
  }
}
