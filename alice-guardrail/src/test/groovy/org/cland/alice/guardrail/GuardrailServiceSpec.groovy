package org.cland.alice.guardrail

import org.cland.alice.core.planner.Plan
import org.cland.alice.guardrail.validators.HallucinationDetector
import org.cland.alice.guardrail.validators.PermissionSandboxValidator
import spock.lang.Specification
import spock.lang.Title

/**
 * Spock spec for GuardrailService — covering GRD-P01 (verifyPlan) and GRD-P02 (verifyResult).
 *
 * Hole tests (洞测试):
 *   GRD-P01: GuardrailService.verifyPlan() pre-validation
 *   GRD-P02: GuardrailService.verifyResult() post-validation
 */
@Title("GuardrailService — Plan and Result verification endpoints")
class GuardrailServiceSpec extends Specification {

    // ── GRD-P01: verifyPlan ──────────────────────────────────────────

    def "GRD-P01: verifyPlan returns ALLOW for a legal, safe plan"() {
        given:
        def service = new GuardrailService()
        def plan = Plan.fastPath("say hello", "LLM_INFERENCE", "greeting")

        when:
        def result = service.verifyPlan(plan)

        then:
        result.isPassed()
        result.status() == AuditResult.Status.ALLOW
    }

    def "GRD-P01: verifyPlan returns MANUAL_CONFIRM for high-risk action"() {
        given:
        def service = new GuardrailService()
        def plan = Plan.builder()
                .type(Plan.Type.FAST_PATH)
                .summary("delete data")
                .addStep("TOOL_CALL", "DROP TABLE users")
                .build()

        when:
        def result = service.verifyPlan(plan)

        then:
        result.needsManualConfirm()
        result.status() == AuditResult.Status.MANUAL_CONFIRM
    }

    def "GRD-P01: verifyPlan returns REJECT when a PreValidator blocks the plan"() {
        given:
        def service = new GuardrailService()
        service.registerPreValidator(new PermissionSandboxValidator())
        def plan = Plan.fastPath("access system", "TOOL_CALL", "/etc/passwd")

        when:
        def result = service.verifyPlan(plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.REJECT
    }

    def "GRD-P01: verifyPlan returns REJECT for null plan"() {
        given:
        def service = new GuardrailService()

        when:
        def result = service.verifyPlan(null)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.REJECT
    }

    // ── GRD-P02: verifyResult ────────────────────────────────────────

    def "GRD-P02: verifyResult returns ALLOW for a valid observation"() {
        given:
        def service = new GuardrailService()
        def plan = Plan.fastPath("query data", "TOOL_CALL", "database")
        def observation = [
                "status" : "SUCCESS",
                "summary": "Query returned 5 rows",
                "rawData": '{"rows": [{"id": 1}]}'
        ] as Map<String, Object>

        when:
        def result = service.verifyResult(observation, plan)

        then:
        result.isPassed()
        result.status() == AuditResult.Status.ALLOW
    }

    def "GRD-P02: verifyResult returns INVALID for FAILURE observation"() {
        given:
        def service = new GuardrailService()
        def plan = Plan.fastPath("query data", "TOOL_CALL", "database")
        def observation = [
                "status" : "FAILURE",
                "summary": "Connection timeout",
                "rawData": ""
        ] as Map<String, Object>

        when:
        def result = service.verifyResult(observation, plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.INVALID
    }

    def "GRD-P02: verifyResult returns INVALID for null observation"() {
        given:
        def service = new GuardrailService()

        when:
        def result = service.verifyResult(null, null)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.INVALID
    }

    def "GRD-P02: verifyResult invokes PostValidator chain and reports failures"() {
        given:
        def service = new GuardrailService()
        def hallucinationDetector = new HallucinationDetector()
        service.registerPostValidator(hallucinationDetector)
        def plan = Plan.fastPath("search", "TOOL_CALL", "web")
        def observation = [
                "status" : "SUCCESS",
                "summary": "search completed",
                "rawData": "no results found"
        ] as Map<String, Object>

        when:
        def result = service.verifyResult(observation, plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.INVALID
    }
}
