package org.cland.alice.guardrail.validators

import org.cland.alice.core.planner.Plan
import org.cland.alice.guardrail.AuditResult
import org.cland.alice.guardrail.CorrectionSuggestion
import org.cland.alice.guardrail.GuardrailService
import org.cland.alice.guardrail.RiskLevel
import spock.lang.Specification
import spock.lang.Title

import java.math.BigDecimal

/**
 * Spock spec for NumericResultValidator — 「数值结果可信性」后验（承 #528 副产物 · #530）。
 *
 * 回归锚点 = #528 三条实测边界：
 *   123456789012*987654321 差 6188 · 2**53+1 丢 1 · 123456789012345678+1 错。
 */
@Title("NumericResultValidator — numeric result credibility (#530)")
class NumericResultValidatorSpec extends Specification {

    private static Plan calculatorPlan(String expression) {
        return Plan.builder()
                .type(Plan.Type.FAST_PATH)
                .summary("calc")
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "calculator", [expression: expression]))
                .build()
    }

    private static Plan toolPlan(String toolName) {
        return Plan.builder()
                .type(Plan.Type.FAST_PATH)
                .summary("tool " + toolName)
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, toolName))
                .build()
    }

    private static Map<String, Object> observation(String rawData, String status = "SUCCESS") {
        return ["status": status, "summary": "obs", "rawData": rawData] as Map<String, Object>
    }

    // ── #528 回归：静默精度错必须被抓 ────────────────────────────────

    def "catches #528 >2^53 multiplication silent defect"() {
        given:
        def v = new NumericResultValidator()

        when:
        def result = v.check(observation("121932631124487127040"),
                calculatorPlan("123456789012*987654321"))

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.INVALID
        result.reason().contains("recomputation")
        result.reason().contains("121932631124487120852")
    }

    def "accepts the correct >2^53 multiplication result"() {
        given:
        def v = new NumericResultValidator()

        expect:
        v.check(observation("121932631124487120852"),
                calculatorPlan("123456789012*987654321")).isPassed()
    }

    def "catches 2**53+1 off-by-one"() {
        given:
        def v = new NumericResultValidator()

        when:
        def result = v.check(observation("9007199254740992"), calculatorPlan("2**53+1"))

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.INVALID
    }

    def "catches large integer addition defect"() {
        given:
        def v = new NumericResultValidator()

        when:
        def result = v.check(observation("123456789012345680"),
                calculatorPlan("123456789012345678+1"))

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.INVALID
    }

    def "exact integer comparison is not masked by relative tolerance"() {
        given:
        def v = new NumericResultValidator()

        expect: "big integers differing by 1 must be treated as a mismatch (not ~1e-9)"
        !v.check(observation("121932631124487120851"),
                calculatorPlan("123456789012*987654321")).isPassed()
    }

    // ── 无回归的正控 ────────────────────────────────────────────────

    def "allows correct small arithmetic"() {
        given:
        def v = new NumericResultValidator()

        expect:
        v.check(observation("60"), calculatorPlan("(12+8)*3")).isPassed()
    }

    def "tolerates fractional division"() {
        given:
        def v = new NumericResultValidator()

        expect:
        v.check(observation("2.5"), calculatorPlan("10/4")).isPassed()
    }

    def "supports python-style power precedence"() {
        given:
        def v = new NumericResultValidator()

        expect:
        v.check(observation("512"), calculatorPlan("2**3**2")).isPassed()
        v.check(observation("-4"), calculatorPlan("-2**2")).isPassed()
    }

    def "reads numeric result from a Map rawData (result key)"() {
        given:
        def v = new NumericResultValidator()
        def obs = ["status": "SUCCESS", "rawData": [result: "60"]] as Map<String, Object>

        expect:
        v.check(obs, calculatorPlan("(12+8)*3")).isPassed()
    }

    // ── 软告警（无表达式可复算时）────────────────────────────────────

    def "warns on integer beyond 2^53 when no expression is available"() {
        given:
        def v = new NumericResultValidator()

        when:
        def result = v.check(observation("9007199254740993"), toolPlan("id_generator"))

        then:
        result.isPassed()
        result.risk() == RiskLevel.MEDIUM
        result.reason().contains("2^53")
    }

    def "warns when magnitude exceeds configured limit"() {
        given:
        def v = new NumericResultValidator()

        when:
        def result = v.check(observation("10000000000000000000"), toolPlan("big_counter"))

        then:
        result.isPassed()
        result.risk() == RiskLevel.MEDIUM
        result.reason().contains("magnitude")
    }

    def "does not warn on ordinary small integers"() {
        given:
        def v = new NumericResultValidator()

        when:
        def result = v.check(observation("42"), toolPlan("counter"))

        then:
        result.isPassed()
        result.risk() == RiskLevel.LOW
    }

    // ── 非目标 / 边界：不得误报 ─────────────────────────────────────

    def "ignores non-numeric rawData"() {
        given:
        def v = new NumericResultValidator()

        expect:
        v.check(observation("completed"), toolPlan("search")).isPassed()
    }

    def "ignores failed observations (delegated to status handling)"() {
        given:
        def v = new NumericResultValidator()

        expect:
        v.check(observation("121932631124487127040", "FAILURE"),
                calculatorPlan("123456789012*987654321")).isPassed()
    }

    def "ignores plans without a tool step"() {
        given:
        def v = new NumericResultValidator()
        def plan = Plan.fastPath("just answer", Plan.Intent.ANALYZE, "gpt-4o")

        expect:
        v.check(observation("9007199254740993"), plan).isPassed()
    }

    def "does not false-positive on unsupported expression syntax"() {
        given:
        def v = new NumericResultValidator()

        expect: "2**0.5 is a non-integer power ⇒ unevaluable ⇒ no mismatch claim"
        v.check(observation("1.4142135623730951"), calculatorPlan("2**0.5")).isPassed()
    }

    def "null observation or plan is tolerated"() {
        given:
        def v = new NumericResultValidator()

        expect:
        v.check(null, calculatorPlan("1+1")).isPassed()
        v.check(observation("2"), null).isPassed()
    }

    // ── 关键数值工具交叉复算（NumericRecomputer）─────────────────────

    def "uses a registered recomputer for a named tool"() {
        given:
        def recomputer = { Map<String, Object> p -> new BigDecimal("100") } as NumericResultValidator.NumericRecomputer
        def v = new NumericResultValidator(
                new BigDecimal(NumericResultValidator.DEFAULT_MAX_MAGNITUDE),
                ["weird_adder": recomputer])
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("weird")
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "weird_adder", [a: 1, b: 2]))
                .build()

        when:
        def result = v.check(observation("99"), plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.INVALID
    }

    def "recomputer returning null falls back to no mismatch"() {
        given:
        def recomputer = { Map<String, Object> p -> null } as NumericResultValidator.NumericRecomputer
        def v = new NumericResultValidator(
                new BigDecimal(NumericResultValidator.DEFAULT_MAX_MAGNITUDE),
                ["weird_adder": recomputer])
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("weird")
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "weird_adder", [a: 1]))
                .build()

        expect:
        v.check(observation("42"), plan).isPassed()
    }

    // ── 构造参数校验 ───────────────────────────────────────────────

    def "rejects null maxMagnitude / recomputers"() {
        when: new NumericResultValidator((BigDecimal) null)
        then: thrown(NullPointerException)

        when: new NumericResultValidator(BigDecimal.ONE, null)
        then: thrown(NullPointerException)
    }

    // ── GuardrailService 端到端：告警必须上浮不丢 ────────────────────

    def "GuardrailService surfaces numeric credibility warning"() {
        given:
        def service = new GuardrailService()
        service.registerPostValidator(new NumericResultValidator())
        def plan = toolPlan("id_generator")

        when:
        def result = service.verifyResult(observation("9007199254740993"), plan)

        then:
        result.isPassed()
        result.risk() == RiskLevel.MEDIUM
        result.reason().contains("2^53")
    }

    def "GuardrailService rejects numeric mismatch end to end"() {
        given:
        def service = new GuardrailService()
        service.registerPostValidator(new NumericResultValidator())

        when:
        def result = service.verifyResult(observation("121932631124487127040"),
                calculatorPlan("123456789012*987654321"))

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.INVALID
    }
}
