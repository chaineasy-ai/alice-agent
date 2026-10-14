package org.cland.alice.guardrail.validators

import org.cland.alice.core.planner.Plan
import org.cland.alice.guardrail.AuditResult
import org.cland.alice.guardrail.CorrectionSuggestion
import spock.lang.Specification
import spock.lang.Title

/**
 * Spock spec for RequiredToolCallValidator — 「需工具任务必须出现工具调用」（承 #528 机制发现 · #530）。
 *
 * 机制与产品口径分工：本类只强制机制；requiredTools 的来源（任务意图元数据）归 #516 / compass。
 */
@Title("RequiredToolCallValidator — required tool invocation (#530)")
class RequiredToolCallValidatorSpec extends Specification {

    def "no requiredTools metadata is a no-op"() {
        given:
        def v = new RequiredToolCallValidator()
        def plan = Plan.fastPath("just answer", Plan.Intent.ANALYZE, "gpt-4o")

        expect:
        v.check(plan).isPassed()
    }

    def "required tool that is invoked passes"() {
        given:
        def v = new RequiredToolCallValidator()
        def plan = Plan.builder()
                .type(Plan.Type.FAST_PATH).summary("calc")
                .metadata([requiredTools: ["calculator"]])
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "calculator"))
                .addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH"))
                .build()

        expect:
        v.check(plan).isPassed()
    }

    def "required tool skipped is rejected with REPLAN"() {
        given:
        def v = new RequiredToolCallValidator()
        def plan = Plan.builder()
                .type(Plan.Type.FAST_PATH).summary("mental math")
                .metadata([requiredTools: ["calculator"]])
                .addStep(Plan.Step.of(Plan.Intent.ANALYZE, "gpt-4o"))
                .addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH"))
                .build()

        when:
        def result = v.check(plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.REJECT
        result.reason().contains("calculator")
        result.suggestion().type() == CorrectionSuggestion.Type.REPLAN
    }

    def "supports comma/semicolon/space separated string metadata"() {
        given:
        def v = new RequiredToolCallValidator()
        def plan = Plan.builder()
                .type(Plan.Type.FAST_PATH).summary("multi")
                .metadata([requiredTools: "calculator, text_stats; grep"])
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "calculator"))
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "text_stats"))
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "grep"))
                .build()

        expect:
        v.check(plan).isPassed()
    }

    def "reports all missing required tools"() {
        given:
        def v = new RequiredToolCallValidator()
        def plan = Plan.builder()
                .type(Plan.Type.FAST_PATH).summary("partial")
                .metadata([requiredTools: ["calculator", "text_stats"]])
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "calculator"))
                .build()

        when:
        def result = v.check(plan)

        then:
        !result.isPassed()
        result.reason().contains("text_stats")
        !result.reason().contains("calculator\"") // calculator was invoked
    }

    def "blank / empty / missing requiredTools is a no-op"() {
        given:
        def v = new RequiredToolCallValidator()

        expect:
        v.check(Plan.builder().type(Plan.Type.FAST_PATH).summary("empty")
                .metadata([requiredTools: []]).build()).isPassed()
        v.check(Plan.builder().type(Plan.Type.FAST_PATH).summary("blank")
                .metadata([requiredTools: "  "]).build()).isPassed()
        v.check(Plan.builder().type(Plan.Type.FAST_PATH).summary("missing").build()).isPassed()
        v.check(null).isPassed()
    }

    def "metadata helper builds the requiredTools key without typo"() {
        given:
        def v = new RequiredToolCallValidator()
        def plan = Plan.builder()
                .type(Plan.Type.FAST_PATH).summary("helper")
                .metadata(RequiredToolCallValidator.metadata(["calculator"]))
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "calculator"))
                .build()

        expect:
        v.check(plan).isPassed()
    }
}
