package org.cland.alice.core.agent.guardrail

import org.cland.alice.core.agent.kernel.graph.EffectGateway
import org.cland.alice.core.agent.kernel.graph.EffectOutcome
import org.cland.alice.core.agent.kernel.graph.GoalRef
import org.cland.alice.core.agent.kernel.graph.GraphNode
import org.cland.alice.core.agent.kernel.graph.Ledger
import org.cland.alice.core.agent.kernel.graph.NodeKind
import org.cland.alice.core.agent.kernel.graph.SessionOutcome
import org.cland.alice.core.agent.kernel.graph.StandardSkeleton
import org.cland.alice.core.agent.kernel.graph.ToolCallReq
import org.cland.alice.core.agent.result.StepResult
import org.cland.alice.guardrail.Verificator
import spock.lang.Specification
import spock.lang.Title

/**
 * VerifyPostGatePolicy — legacy GuardrailVerificatorAdapter post-verify 语义 → 图内核 GatePolicy 的搬运（D8·维持原语义）。
 *
 * 用例：
 *   VP-P01: 无验证器恒放行（等同 legacy guardrail==null）
 *   VP-P02: 正常答案放行 / 规则命中拦截（guard("reject")）
 *   VP-P02b: 无 answer 产物回退 lastObservation（budget-guard 退出路径）
 *   VP-P03: 与 legacy audit(StepResult.Finish) 逐例 parity（零语义漂移）
 *   VP-P04: 真实策略驱动 StandardSkeleton 修订回路（拦截 → 修订 → 二答通过）
 *   VP-P05: 任意 Verificator 委托生效（generic delegation）
 */
@Title("VerifyPostGatePolicy — vp gate 适配")
class VerifyPostGatePolicySpec extends Specification {

    private static final GraphNode VP = GraphNode.of("vp", NodeKind.GATE)

    private static Ledger ledgerWithAnswer(String answer) {
        def ledger = new Ledger()
        if (answer != null) {
            ledger.recordArtifact("answer", answer)
        }
        return ledger
    }

    // ── VP-P01 ───────────────────────────────────────────────────────

    def "VP-P01: null verificator passes (legacy guardrail==null)"() {
        given:
        def policy = new VerifyPostGatePolicy(null)

        expect:
        policy.verify(VP, ledgerWithAnswer("anything"), "").pass()
        policy.verify(VP, null, "obs").pass()
    }

    // ── VP-P02 ───────────────────────────────────────────────────────

    def "VP-P02: normal answer passes; hallucination/error pattern blocks with port reject"() {
        given:
        def policy = new VerifyPostGatePolicy(new GuardrailVerificatorAdapter(false))

        expect: "正常答案放行"
        policy.verify(VP, ledgerWithAnswer("最终回答：订单已支付"), "").pass()

        and: "命中 HallucinationDetector 空结果模式 → 拦截 reject"
        def blocked = policy.verify(VP, ledgerWithAnswer("no data found"), "")
        !blocked.pass()
        blocked.guardPort() == StandardSkeleton.PORT_VP_REJECT

        and: "命中错误模式 → 拦截 reject"
        def errBlocked = policy.verify(VP, ledgerWithAnswer("error: connection refused"), "")
        !errBlocked.pass()
        errBlocked.guardPort() == StandardSkeleton.PORT_VP_REJECT
    }

    def "VP-P02b: missing answer artifact falls back to lastObservation"() {
        given:
        def policy = new VerifyPostGatePolicy(new GuardrailVerificatorAdapter(false))

        expect:
        policy.verify(VP, new Ledger(), "ok summary").pass()
        !policy.verify(VP, new Ledger(), "no data").pass()
    }

    // ── VP-P03 ───────────────────────────────────────────────────────

    def "VP-P03: parity with legacy audit(StepResult.Finish) across answers"() {
        given: "同一 adapter 实例：legacy 参考路径 vs policy 路径"
        def adapter = new GuardrailVerificatorAdapter(false)
        def policy = new VerifyPostGatePolicy(adapter)

        expect: "policy 判定 == legacy audit(StepResult.Finish) 判定（零语义漂移）"
        adapter.audit(new StepResult.Finish(answer)) == policy.verify(VP, ledgerWithAnswer(answer), "").pass()

        where:
        answer << [
                "最终回答：已完成",
                "正常内容",
                "",
                "no data found",
                "error: boom",
                "timeout",
                "null",
        ]
    }

    // ── VP-P04 ───────────────────────────────────────────────────────

    def "VP-P04: real policy drives skeleton revision loop (block -> revise -> pass)"() {
        given: "规则后检拦截首答（error: 模式），二答正常"
        def adapter = new GuardrailVerificatorAdapter(false)
        def answers = ["error: first attempt", "最终回答：完成"]
        def skeleton = new StandardSkeleton(
                "test task",
                { String task, Ledger l ->
                    StandardSkeleton.StrategyPlan.of([GoalRef.of("g1", "goal one")])
                } as StandardSkeleton.StrategizeBrain,
                { GoalRef g, Ledger l, String obs ->
                    new StandardSkeleton.ActorStep.Done(answers.remove(0))
                } as StandardSkeleton.ActorBrain,
                { GoalRef g, Ledger l, String obs ->
                    StandardSkeleton.Arbitration.pass()
                } as StandardSkeleton.ArbitrationBrain,
                new VerifyPostGatePolicy(adapter),
                { ToolCallReq c -> EffectOutcome.ok("unused") } as EffectGateway,
                2,
                10)

        when:
        def outcome = skeleton.run()

        then: "拦截经 rev-budget 修订回路后，二答通过并成为会话产物"
        outcome.status() == SessionOutcome.Status.FINISHED
        outcome.artifacts().get("answer") == "最终回答：完成"
        def trace = outcome.trace()
        trace.any { it.contains("vp=BLOCK(reject)") }
        trace.any { it.contains("ledger: revision[g1]=1") }
        trace.count { it.contains("tao(COMPOSITE)") } == 2   // 同 goal 重跑一次
    }

    // ── VP-P05 ───────────────────────────────────────────────────────

    def "VP-P05: arbitrary Verificator delegation is honored"() {
        given:
        def verificator = Mock(Verificator)
        def policy = new VerifyPostGatePolicy(verificator)

        when:
        def verdict = policy.verify(VP, ledgerWithAnswer("CUSTOM"), "")

        then: "委托收到的正是 legacy Finish 形态；判定被原样映射"
        1 * verificator.audit({ it instanceof StepResult.Finish && ((StepResult.Finish) it).answer() == "CUSTOM" }) >> false
        !verdict.pass()
        verdict.guardPort() == StandardSkeleton.PORT_VP_REJECT
    }
}
